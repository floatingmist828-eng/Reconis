package cn.reconis.reconciliation;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.io.*;
import java.time.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.*;

@Service
public class ImportService {
  public static final List<String> HEADERS =
      List.of(
          "schema_version",
          "merchant_code",
          "channel_env",
          "event_type",
          "out_trade_no",
          "channel_trade_no",
          "out_request_no",
          "amount_cent",
          "occurred_at");
  private final Db db;
  private final FileStore files;
  private final Access access;
  private final cn.reconis.payment.PaymentGateway gateway;

  public ImportService(
      Db db, FileStore files, Access access, cn.reconis.payment.PaymentGateway gateway) {
    this.db = db;
    this.files = files;
    this.access = access;
    this.gateway = gateway;
  }

  public List<Map<String, Object>> parse(byte[] bytes, String format, String kind)
      throws Exception {
    return parse(bytes, format, kind, false);
  }

  private List<Map<String, Object>> parse(
      byte[] bytes, String format, String kind, boolean allowEmpty) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (format.equals("xlsx")) {
      try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
        var sheet = book.getSheetAt(0);
        if (sheet.getLastRowNum() > 10000) throw Api.fail(422, "每次最多导入10000行");
        var head = sheet.getRow(0);
        if (head == null) throw Api.fail(422, "缺少表头");
        var headers = new ArrayList<String>();
        for (var c : head) headers.add(c.getStringCellValue());
        List<String> expected =
            kind.equals("merchants")
                ? List.of("merchant_code", "name", "category_code", "contact")
                : HEADERS;
        if (!headers.equals(expected)) throw Api.fail(422, "表头与模板不一致，请下载模板");
        var formatter = new DataFormatter();
        for (int n = 1; n <= sheet.getLastRowNum(); n++) {
          var row = sheet.getRow(n);
          if (row == null) continue;
          Map<String, Object> item = new LinkedHashMap<>();
          for (int i = 0; i < headers.size(); i++) {
            var cell = row.getCell(i);
            if (cell != null && cell.getCellType() == CellType.FORMULA)
              throw Api.fail(422, "导入不接受公式单元格");
            item.put(headers.get(i), cell == null ? "" : formatter.formatCellValue(cell));
          }
          if (item.values().stream().allMatch(value -> value.toString().isBlank())) continue;
          item.put("line", n + 1);
          rows.add(item);
        }
      }
    } else if (format.equals("xml")) {
      var f = DocumentBuilderFactory.newInstance();
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
      var root = doc.getDocumentElement();
      String expected = kind.equals("merchants") ? "merchants" : "statement";
      if (!root.getTagName().equals(expected)) throw Api.fail(422, "XML根节点必须为 " + expected);
      if (kind.equals("statement")
          && (!"1".equals(root.getAttribute("schemaVersion"))
              || !gateway.environment.equals(root.getAttribute("channelEnv"))))
        throw Api.fail(422, "XML账单版本或环境不一致");
      if (kind.equals("statement")) {
        for (int i = 0; i < root.getChildNodes().getLength(); i++) {
          Node child = root.getChildNodes().item(i);
          if (child instanceof Element element && !element.getTagName().equals("event"))
            throw Api.fail(422, "XML账单只允许event数据行，不能把未知内容当作空账单");
          if ((child.getNodeType() == Node.TEXT_NODE
                  || child.getNodeType() == Node.CDATA_SECTION_NODE)
              && !child.getTextContent().isBlank()) throw Api.fail(422, "XML账单包含无法识别的文本内容");
        }
      }
      NodeList nodes = root.getElementsByTagName(kind.equals("merchants") ? "merchant" : "event");
      if (nodes.getLength() > 10000) throw Api.fail(422, "每次最多导入10000行");
      for (int n = 0; n < nodes.getLength(); n++) {
        var e = (Element) nodes.item(n);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("line", n + 1);
        if (kind.equals("merchants")) {
          item.put("merchant_code", e.getAttribute("code"));
          item.put("name", tag(e, "name"));
          item.put("category_code", tag(e, "categoryCode"));
          item.put("contact", tag(e, "contact"));
        } else {
          item.put("schema_version", root.getAttribute("schemaVersion"));
          item.put("channel_env", root.getAttribute("channelEnv"));
          item.put("merchant_code", e.getAttribute("merchantCode"));
          item.put("event_type", e.getAttribute("type"));
          item.put("out_trade_no", tag(e, "outTradeNo"));
          item.put("channel_trade_no", tag(e, "channelTradeNo"));
          item.put("out_request_no", tag(e, "outRequestNo"));
          item.put("amount_cent", tag(e, "amountCent"));
          item.put("occurred_at", tag(e, "occurredAt"));
        }
        rows.add(item);
      }
    } else throw Api.fail(422, "仅接受 XLSX 或 XML");
    if (rows.isEmpty() && !allowEmpty) throw Api.fail(422, "文件没有有效数据行；空账单必须明确声明账期完整");
    return rows;
  }

  private String tag(Element e, String key) {
    var nodes = e.getElementsByTagName(key);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
  }

  @Transactional(rollbackFor = Exception.class)
  @SuppressWarnings("unchecked")
  public Map<String, Object> automaticStatement(byte[] bytes, String format, String day) throws Exception {
    var result = (Map<String, Object>) statement(bytes, format, null, day, day, "DECLARED_COMPLETE", false);
    if (!((List<?>) result.get("errors")).isEmpty())
      throw Api.fail(422, "自动账单校验失败，整个导入事务已撤销");
    return result;
  }

  @Transactional
  public Object statement(
      byte[] bytes,
      String format,
      Long merchant,
      String start,
      String end,
      String completeness,
      boolean preview)
      throws Exception {
    if (merchant == null) access.role("PLATFORM_FINANCE");
    else access.merchant(merchant, "OWNER", "FINANCE");
    if (!Set.of("UNKNOWN", "DECLARED_COMPLETE").contains(completeness))
      throw Api.fail(422, "输入完整性声明不合法");
    if (bytes.length == 0 || bytes.length > AlipayBillParser.MAX_BYTES)
      throw Api.fail(422, "账单须为非空文件且不超过20MB");
    boolean nativeBill = Set.of("csv", "zip").contains(format);
    boolean declaredComplete = completeness.equals("DECLARED_COMPLETE");
    var rows =
        nativeBill
            ? AlipayBillParser.parse(bytes, format, gateway.environment, declaredComplete)
            : parse(bytes, format, "statement", declaredComplete);
    var selected =
        merchant == null
            ? null
            : db.need(
                "SELECT id,merchant_code,name FROM merchant WHERE id=#{p.id}",
                Api.params("id", merchant));
    var errors = new ArrayList<Map<String, Object>>();
    var valid = new ArrayList<Map<String, Object>>();
    var keys = new HashSet<String>();
    int duplicates = 0;
    LocalDateTime from, until;
    try {
      from =
          LocalDate.parse(start)
              .atStartOfDay(ZoneId.of("Asia/Shanghai"))
              .withZoneSameInstant(ZoneOffset.UTC)
              .toLocalDateTime();
      until =
          LocalDate.parse(end)
              .plusDays(1)
              .atStartOfDay(ZoneId.of("Asia/Shanghai"))
              .withZoneSameInstant(ZoneOffset.UTC)
              .toLocalDateTime();
    } catch (java.time.DateTimeException ex) {
      throw Api.fail(422, "账期必须是有效日期，格式为yyyy-MM-dd");
    }
    if (!until.isAfter(from)) throw Api.fail(422, "日期范围不合法");
    var owners = new HashMap<String, Optional<Map<String, Object>>>();
    for (var r : rows) {
      try {
        if (r.containsKey("parseError")) throw Api.fail(422, r.get("parseError").toString());
        if (!"1".equals(r.get("schema_version"))
            || !gateway.environment.equals(r.get("channel_env"))) throw Api.fail(422, "账单版本或环境不一致");
        if (!nativeBill
            && selected != null
            && !selected.get("merchantCode").equals(r.get("merchant_code")))
          throw Api.fail(422, "账单商户编号与所选商户不一致");
        String type = Api.required(r, "event_type");
        if (!Set.of("RECEIPT", "REFUND").contains(type)) throw Api.fail(422, "event_type 不合法");
        if (type.equals("RECEIPT") && !Api.text(r, "out_request_no").isBlank())
          throw Api.fail(422, "收款行不能同时携带退款请求号，请核对业务类型");
        long amount = Api.number(r, "amount_cent");
        if (amount <= 0) throw Api.fail(422, "金额须为正整数分");
        var at = Api.utc(Api.required(r, "occurred_at"));
        if (at.isBefore(from) || !at.isBefore(until)) throw Api.fail(422, "发生时间不在所选账期内");
        for (String field : List.of("out_trade_no", "out_request_no", "channel_trade_no"))
          if (Api.text(r, field).length() > (field.equals("channel_trade_no") ? 100 : 64))
            throw Api.fail(422, "渠道标识长度超限");
        Long owner = resolveMerchant(r, merchant, owners);
        r.put("merchantId", owner);
        String eventId = Api.text(r, type.equals("RECEIPT") ? "out_trade_no" : "out_request_no");
        if (!eventId.isBlank() && !keys.add(type + ":" + eventId)) duplicates++;
        r.put("amount", amount);
        r.put("at", at);
        valid.add(r);
      } catch (org.springframework.web.server.ResponseStatusException
          | IllegalArgumentException e) {
        var error = new LinkedHashMap<String, Object>();
        error.put("line", r.get("line"));
        if (r.containsKey("sourceFile")) error.put("sourceFile", r.get("sourceFile"));
        if (r.containsKey("sourceLine")) error.put("sourceLine", r.get("sourceLine"));
        error.put(
            "reason",
            e instanceof org.springframework.web.server.ResponseStatusException x
                ? Objects.toString(x.getReason())
                : "字段格式错误");
        errors.add(error);
      }
    }
    var groups = new TreeMap<Long, List<Map<String, Object>>>();
    for (var row : valid) {
      long key = row.get("merchantId") == null ? 0L : Db.id(row, "merchantId");
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
    }
    boolean emptyComplete = declaredComplete && rows.isEmpty();
    if (emptyComplete && merchant != null) groups.put(merchant, new ArrayList<>());
    if (merchant == null && declaredComplete && errors.isEmpty() && !groups.containsKey(0L)) {
      // Include merchants absent from a complete shared-channel bill, so they produce LOCAL_ONLY.
      for (var local :
          db.list(
              "SELECT DISTINCT merchant_id FROM v_transaction_fact WHERE"
                  + " channel_occurred_at>=#{p.start} AND channel_occurred_at<#{p.end} AND"
                  + " channel_env=#{p.env}",
              Api.params("start", from, "end", until, "env", gateway.environment)))
        groups.computeIfAbsent(Db.id(local, "merchantId"), ignored -> new ArrayList<>());
    }
    if (emptyComplete && groups.isEmpty()) groups.put(0L, new ArrayList<>());
    int unassigned = groups.getOrDefault(0L, List.of()).size();
    var partitions = new ArrayList<Map<String, Object>>();
    for (var group : groups.entrySet()) {
      long key = group.getKey();
      Map<String, Object> m =
          key == 0
              ? null
              : db.need(
                  "SELECT id,name,merchant_code FROM merchant WHERE id=#{p.id}",
                  Api.params("id", key));
      partitions.add(
          Api.params(
              "merchantId",
              key == 0 ? null : key,
              "merchantName",
              m == null ? (group.getValue().isEmpty() ? "平台空账单（无交易）" : "平台待核验归属") : m.get("name"),
              "scopeType",
              key == 0 ? "PLATFORM_UNASSIGNED" : "MERCHANT",
              "rows",
              group.getValue().size()));
    }
    Map<String, Object> result =
        new LinkedHashMap<>(
            Map.of(
                "rows",
                rows.size(),
                "accepted",
                valid.size(),
                "duplicates",
                duplicates,
                "errors",
                errors,
                "preview",
                valid.stream().limit(8).toList(),
                "partitions",
                partitions,
                "unassigned",
                unassigned,
                "canImport",
                !valid.isEmpty() || emptyComplete,
                "emptyComplete",
                emptyComplete));
    if (preview) return result;
    if (valid.isEmpty() && !emptyComplete) throw Api.fail(422, "没有可以导入的有效行");
    String hash = FileStore.sha(bytes);
    // File identity and interpretation identity are separate: changing the range or completeness
    // creates a new immutable input. Routing is included so newly identifiable rows can be imported
    // again.
    String routing =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                valid.stream()
                    .map(
                        r ->
                            List.of(
                                r.get("line"),
                                Objects.toString(r.get("merchantId"), "unassigned"),
                                Api.text(r, "out_trade_no"),
                                Api.text(r, "out_request_no"),
                                Api.text(r, "channel_trade_no")))
                    .toList());
    String optionsHash =
        optionsHash(
            format,
            merchant,
            start,
            end,
            completeness,
            gateway.environment,
            routing + "|scope:" + groups.keySet());
    db.exec(
        "DECLARE @r INT; EXEC @r=sys.sp_getapplock"
            + " @Resource=#{p.resource},@LockMode='Exclusive',@LockOwner='Transaction',@LockTimeout=10000;"
            + " IF @r<0 THROW 51060,N'同一账单正在导入，请稍后重试',1;",
        Api.params("resource", "statement:" + hash + ":" + optionsHash));
    String uri = null;
    var batches = new ArrayList<Map<String, Object>>();
    boolean allReused = true;
    String sourceType =
        nativeBill ? "ALIPAY_" + format.toUpperCase(Locale.ROOT) : format.toUpperCase(Locale.ROOT);
    for (var partition : partitions) {
      Long owner = partition.get("merchantId") == null ? null : Db.id(partition, "merchantId");
      var old =
          db.one(
              "SELECT id,batch_no,status FROM reconciliation_batch WHERE ((merchant_id=#{p.m}) OR"
                  + " (merchant_id IS NULL AND #{p.m} IS NULL)) AND scope_type=#{p.scope} AND"
                  + " file_sha256=#{p.hash} AND ingest_options_sha256=#{p.options} AND"
                  + " statement_source_batch_id IS NULL",
              Api.params(
                  "m",
                  owner,
                  "scope",
                  partition.get("scopeType"),
                  "hash",
                  hash,
                  "options",
                  optionsHash));
      var item = new LinkedHashMap<>(partition);
      if (old != null) {
        item.putAll(old);
        item.put("reused", true);
        batches.add(item);
        continue;
      }
      allReused = false;
      if (uri == null) uri = files.write("imports", "." + format, bytes);
      String complete =
          errors.isEmpty() && unassigned == 0 && declaredComplete ? "DECLARED_COMPLETE" : "UNKNOWN";
      String batchNo = Api.no("BAT");
      long batch =
          db.insert(
              "INSERT"
                  + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,source_file_uri,file_sha256,ingest_options_sha256,completeness,created_by)"
                  + " OUTPUT INSERTED.id"
                  + " VALUES(#{p.no},#{p.scope},#{p.m},#{p.start},#{p.end},#{p.type},#{p.uri},#{p.hash},#{p.options},#{p.complete},#{p.actor})",
              Api.params(
                  "no",
                  batchNo,
                  "scope",
                  partition.get("scopeType"),
                  "m",
                  owner,
                  "start",
                  from,
                  "end",
                  until,
                  "type",
                  sourceType,
                  "uri",
                  uri,
                  "hash",
                  hash,
                  "options",
                  optionsHash,
                  "complete",
                  complete,
                  "actor",
                  access.actor().id()));
      for (var r : groups.get(owner == null ? 0L : owner))
        db.exec(
            "INSERT"
                + " statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,out_request_no,"
                + "amount_cent,channel_occurred_at,raw_line_file_uri)"
                + " VALUES(#{p.batch},#{p.line},#{p.type},#{p.trade},#{p.channel},#{p.refund},#{p.amount},#{p.at},#{p.uri})",
            Api.params(
                "batch",
                batch,
                "line",
                r.get("line"),
                "type",
                r.get("event_type"),
                "trade",
                r.get("out_trade_no"),
                "channel",
                r.get("channel_trade_no"),
                "refund",
                r.get("out_request_no"),
                "amount",
                r.get("amount"),
                "at",
                r.get("at"),
                "uri",
                uri));
      item.putAll(Api.params("id", batch, "batchNo", batchNo, "status", "READY", "reused", false));
      batches.add(item);
      audit(
          batch,
          owner,
          "STATEMENT_IMPORT",
          partition.get("rows")
              + " rows; "
              + errors.size()
              + " errors; "
              + partition.get("scopeType"));
    }
    if (uri != null)
      files.jsonWrite(
          uri + ".validation.json",
          Map.of(
              "errors",
              errors,
              "duplicates",
              duplicates,
              "optionsHash",
              optionsHash,
              "start",
              start,
              "end",
              end,
              "completeness",
              completeness,
              "partitions",
              partitions));
    result.put("batches", batches);
    result.put("batch", batches.getFirst());
    result.put("reused", allReused);
    return result;
  }

  static String optionsHash(
      String format,
      Long merchant,
      String start,
      String end,
      String completeness,
      String environment,
      String routing) {
    String options =
        String.join(
            "|",
            "statement-v2",
            format,
            Objects.toString(merchant, "AUTO"),
            start,
            end,
            completeness,
            environment,
            routing);
    return FileStore.sha(options.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  Long resolveMerchant(
      Map<String, Object> row, Long selected, Map<String, Optional<Map<String, Object>>> cache) {
    String trade = Api.text(row, "out_trade_no"),
        channel = Api.text(row, "channel_trade_no"),
        refund = Api.text(row, "out_request_no");
    var candidates = new ArrayList<Map<String, Object>>();
    String projection =
        "SELECT p.id"
            + " payment_id,p.out_trade_no,p.channel_trade_no,p.channel_env,p.app_id,o.merchant_id,m.merchant_code"
            + " FROM payment_attempt p JOIN v_order o ON o.id=p.order_id JOIN merchant m ON"
            + " m.id=o.merchant_id";
    if (!trade.isBlank())
      add(
          candidates,
          lookup(cache, "P:" + trade, projection + " WHERE p.out_trade_no=#{p.key}", trade));
    if (!channel.isBlank())
      add(
          candidates,
          lookup(
              cache,
              "C:" + channel,
              projection
                  + " WHERE p.channel_trade_no=#{p.key} AND p.channel_env=#{p.env} AND"
                  + " p.app_id=#{p.app}",
              channel));
    if ("REFUND".equals(row.get("event_type")) && !refund.isBlank())
      add(
          candidates,
          lookup(
              cache,
              "R:" + refund,
              projection
                  + " JOIN refund_request r ON r.payment_id=p.id WHERE r.out_request_no=#{p.key}",
              refund));
    if (candidates.isEmpty()) {
      row.put("ownership", selected == null ? "待核验：本地未找到关联支付或退款" : "所选商户声明，尚无本地关联");
      return selected;
    }
    var owner = candidates.getFirst();
    if (candidates.stream().anyMatch(c -> Db.id(c, "paymentId") != Db.id(owner, "paymentId")))
      throw Api.fail(422, "支付号、渠道号与退款号指向不同原支付，不能导入");
    if (!gateway.environment.equals(owner.get("channelEnv"))
        || !gateway.appId.equals(owner.get("appId"))) throw Api.fail(422, "交易不属于当前渠道环境或应用");
    if (!trade.isBlank() && !trade.equals(owner.get("outTradeNo")))
      throw Api.fail(422, "退款号或渠道号对应的原支付与账单不一致");
    if (!channel.isBlank()
        && owner.get("channelTradeNo") != null
        && !channel.equals(owner.get("channelTradeNo"))) throw Api.fail(422, "渠道交易号与原支付不一致");
    long merchant = Db.id(owner, "merchantId");
    if (selected != null && selected != merchant) throw Api.fail(403, "账单含其他商户的支付或退款");
    String declared = Api.text(row, "merchant_code");
    if (!declared.isBlank() && !declared.equals(owner.get("merchantCode")))
      throw Api.fail(422, "文件声明的商户编号与本地交易实际归属不一致");
    if (trade.isBlank()) row.put("out_trade_no", owner.get("outTradeNo"));
    row.put("ownership", "已核对原支付及退款关联");
    return merchant;
  }

  private Map<String, Object> lookup(
      Map<String, Optional<Map<String, Object>>> cache, String cacheKey, String sql, String key) {
    return cache
        .computeIfAbsent(
            cacheKey,
            ignored ->
                Optional.ofNullable(
                    db.one(
                        sql,
                        Api.params("key", key, "env", gateway.environment, "app", gateway.appId))))
        .orElse(null);
  }

  private static void add(List<Map<String, Object>> rows, Map<String, Object> row) {
    if (row != null) rows.add(row);
  }

  @Transactional
  @SuppressWarnings("unchecked")
  public Object identify(long id, boolean preview) throws Exception {
    access.role("PLATFORM_FINANCE");
    var batch = access.batch(id);
    if ("AUTO_JOB".equals(batch.get("sourceType"))) throw Api.fail(422, "自动获取任务不是待识别账单");
    if (!"PLATFORM_UNASSIGNED".equals(batch.get("scopeType")))
      throw Api.fail(422, "仅平台待核验账单需要重新识别归属");
    String uri = Objects.toString(batch.get("sourceFileUri"), "");
    if (uri.isBlank()) throw Api.fail(422, "该批次没有可重新识别的原始文件");
    String source = batch.get("sourceType").toString();
    String format =
        source.startsWith("ALIPAY_")
            ? source.substring(7).toLowerCase(Locale.ROOT)
            : source.toLowerCase(Locale.ROOT);
    LocalDateTime from = ((java.sql.Timestamp) batch.get("periodStart")).toLocalDateTime();
    LocalDateTime until = ((java.sql.Timestamp) batch.get("periodEnd")).toLocalDateTime();
    String start =
        from.atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .toString();
    String end =
        until
            .minusNanos(1)
            .atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .toString();
    // Re-import rather than rewrite: original rows, historical results and their cases remain
    // evidence.
    var result =
        (Map<String, Object>)
            statement(
                java.nio.file.Files.readAllBytes(files.resolve(uri)),
                format,
                null,
                start,
                end,
                "DECLARED_COMPLETE".equals(batch.get("completeness"))
                    ? "DECLARED_COMPLETE"
                    : "UNKNOWN",
                preview);
    result.put("identifiedFromBatchId", id);
    if (!preview) {
      String json =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(
                  Api.normalize(Api.params("sourceBatchId", id, "batches", result.get("batches"))));
      db.exec(
          "INSERT"
              + " audit_log(event_category,actor_user_id,object_type,object_id,action,result,summary,after_json,request_id)"
              + " VALUES('IMPORT_EXPORT',#{p.actor},'RECONCILIATION_BATCH',#{p.id},'BATCH_REIDENTIFIED','SUCCESS',"
              + "N'原件重新识别归属；历史输入和工单范围不变',#{p.after},#{p.request})",
          Api.params(
              "actor",
              access.actor().id(),
              "id",
              id,
              "after",
              json,
              "request",
              RequestContext.requestId()));
    }
    return result;
  }

  @Transactional
  public Object merchants(byte[] bytes, String format, boolean preview) throws Exception {
    access.role("ADMIN");
    var rows = parse(bytes, format, "merchants");
    var errors = new ArrayList<Map<String, Object>>();
    int accepted = 0;
    for (var r : rows) {
      try {
        for (String key : List.of("merchant_code", "name", "category_code", "contact"))
          Api.required(r, key);
        if (!Api.text(r, "merchant_code").matches("[A-Za-z0-9_-]{1,32}"))
          throw Api.fail(422, "商户编号无效");
        if (!preview) {
          var old =
              db.one(
                  "SELECT id FROM merchant WHERE merchant_code=#{p.code}",
                  Api.params("code", r.get("merchant_code")));
          if (old == null)
            db.exec(
                "INSERT merchant(merchant_code,name,category_code,contact)"
                    + " VALUES(#{p.code},#{p.name},#{p.category},#{p.contact})",
                Api.params(
                    "code",
                    r.get("merchant_code"),
                    "name",
                    r.get("name"),
                    "category",
                    r.get("category_code"),
                    "contact",
                    r.get("contact")));
          else
            db.exec(
                "UPDATE merchant SET"
                    + " proposed_name=#{p.name},proposed_category=#{p.category},proposed_contact=#{p.contact}"
                    + " WHERE id=#{p.id}",
                Api.params(
                    "id",
                    old.get("id"),
                    "name",
                    r.get("name"),
                    "category",
                    r.get("category_code"),
                    "contact",
                    r.get("contact")));
        }
        accepted++;
      } catch (org.springframework.web.server.ResponseStatusException e) {
        errors.add(Map.of("line", r.get("line"), "reason", Objects.toString(e.getReason())));
      }
    }
    if (!preview) audit(null, null, "MERCHANT_IMPORT", accepted + " candidates pending review");
    return Map.of(
        "rows",
        rows.size(),
        "accepted",
        accepted,
        "errors",
        errors,
        "preview",
        rows.stream().limit(8).toList());
  }

  private void audit(Long id, Long merchant, String action, String summary) {
    db.exec(
        "INSERT"
            + " audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,request_id)"
            + " VALUES('IMPORT_EXPORT',#{p.actor},#{p.merchant},'IMPORT',#{p.id},#{p.action},'SUCCESS',#{p.summary},#{p.req})",
        Api.params(
            "actor",
            access.actor().id(),
            "merchant",
            merchant,
            "id",
            id,
            "action",
            action,
            "summary",
            summary,
            "req",
            RequestContext.requestId()));
  }
}
