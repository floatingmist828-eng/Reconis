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
    if (rows.isEmpty()) throw Api.fail(422, "文件没有有效数据行");
    return rows;
  }

  private String tag(Element e, String key) {
    var nodes = e.getElementsByTagName(key);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
  }

  @Transactional
  public Object statement(
      byte[] bytes,
      String format,
      long merchant,
      String start,
      String end,
      String completeness,
      boolean preview)
      throws Exception {
    access.merchant(merchant, "OWNER", "FINANCE");
    var m = db.need("SELECT * FROM merchant WHERE id=#{p.id}", Api.params("id", merchant));
    var rows = parse(bytes, format, "statement");
    var errors = new ArrayList<Map<String, Object>>();
    var valid = new ArrayList<Map<String, Object>>();
    var keys = new HashSet<String>();
    int duplicates = 0;
    var from =
        LocalDate.parse(start)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
    var until =
        LocalDate.parse(end)
            .plusDays(1)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
    if (!until.isAfter(from)) throw Api.fail(422, "日期范围不合法");
    for (var r : rows) {
      try {
        if (!"1".equals(r.get("schema_version"))
            || !gateway.environment.equals(r.get("channel_env"))
            || !m.get("merchantCode").equals(r.get("merchant_code")))
          throw Api.fail(422, "版本、环境或商户编号不一致");
        String type = Api.required(r, "event_type");
        if (!Set.of("RECEIPT", "REFUND").contains(type)) throw Api.fail(422, "event_type 不合法");
        long amount = Api.number(r, "amount_cent");
        if (amount <= 0) throw Api.fail(422, "金额须为正整数分");
        var at = Api.utc(Api.required(r, "occurred_at"));
        if (at.isBefore(from) || !at.isBefore(until)) throw Api.fail(422, "发生时间不在所选账期内");
        String trade = Api.text(r, "out_trade_no");
        if (trade.length() > 64
            || Api.text(r, "out_request_no").length() > 64
            || Api.text(r, "channel_trade_no").length() > 100) throw Api.fail(422, "渠道标识长度超限");
        if (!trade.isBlank()) {
          var owner =
              db.one(
                  "SELECT o.merchant_id FROM payment_attempt p JOIN v_order o ON o.id=p.order_id"
                      + " WHERE p.out_trade_no=#{p.no}",
                  Api.params("no", trade));
          if (owner != null && Db.id(owner, "merchantId") != merchant)
            throw Api.fail(403, "账单含其他商户的交易");
        }
        String key =
            type + ":" + Api.text(r, type.equals("RECEIPT") ? "out_trade_no" : "out_request_no");
        if (!keys.add(key)) duplicates++;
        r.put("amount", amount);
        r.put("at", at);
        valid.add(r);
      } catch (org.springframework.web.server.ResponseStatusException
          | IllegalArgumentException e) {
        errors.add(
            Map.of(
                "line",
                r.get("line"),
                "reason",
                e instanceof org.springframework.web.server.ResponseStatusException x
                    ? Objects.toString(x.getReason())
                    : "字段格式错误"));
      }
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
                valid.stream().limit(8).toList()));
    if (preview) return result;
    if (valid.isEmpty()) throw Api.fail(422, "没有可以导入的有效行");
    String hash = FileStore.sha(bytes);
    var old =
        db.one(
            "SELECT id,batch_no,status FROM reconciliation_batch WHERE merchant_id=#{p.m} AND"
                + " file_sha256=#{p.hash} AND statement_source_batch_id IS NULL",
            Api.params("m", merchant, "hash", hash));
    if (old != null) {
      result.put("batch", old);
      result.put("reused", true);
      return result;
    }
    String uri = files.write("imports", "." + format, bytes);
    String complete =
        errors.isEmpty() && completeness.equals("DECLARED_COMPLETE")
            ? "DECLARED_COMPLETE"
            : "UNKNOWN";
    long batch =
        db.insert(
            "INSERT"
                + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,source_file_uri,file_sha256,completeness,created_by)"
                + " OUTPUT INSERTED.id"
                + " VALUES(#{p.no},'MERCHANT',#{p.m},#{p.start},#{p.end},#{p.type},#{p.uri},#{p.hash},#{p.complete},#{p.actor})",
            Api.params(
                "no",
                Api.no("BAT"),
                "m",
                merchant,
                "start",
                from,
                "end",
                until,
                "type",
                format.toUpperCase(),
                "uri",
                uri,
                "hash",
                hash,
                "complete",
                complete,
                "actor",
                access.actor().id()));
    for (var r : valid)
      db.exec(
          "INSERT"
              + " statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,out_request_no,amount_cent,channel_occurred_at,raw_line_file_uri)"
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
    result.put("batch", Map.of("id", batch));
    files.jsonWrite(uri + ".errors.json", Map.of("errors", errors, "duplicates", duplicates));
    audit(batch, "STATEMENT_IMPORT", valid.size() + " rows; " + errors.size() + " errors");
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
    if (!preview) audit(null, "MERCHANT_IMPORT", accepted + " candidates pending review");
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

  private void audit(Long id, String action, String summary) {
    db.exec(
        "INSERT"
            + " audit_log(event_category,actor_user_id,object_type,object_id,action,result,summary,request_id)"
            + " VALUES('IMPORT_EXPORT',#{p.actor},'IMPORT',#{p.id},#{p.action},'SUCCESS',#{p.summary},#{p.req})",
        Api.params(
            "actor",
            access.actor().id(),
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
