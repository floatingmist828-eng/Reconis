package cn.reconis.casework;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;

/** Builds a permission-scoped dossier. File verification never upgrades a missing historical hash. */
@Service
public class CaseEvidenceService {
  private static final int MAX_ROWS = 500;
  private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
  private static final long MAX_ARCHIVE_BYTES = 80L * 1024 * 1024;
  private final Db db;
  private final Access access;
  private final FileStore files;
  private final ObjectMapper json = new ObjectMapper();

  public CaseEvidenceService(Db db, Access access, FileStore files) {
    this.db = db;
    this.access = access;
    this.files = files;
  }

  record FileReference(String uri, String expectedSha256, String kind, String sourceId,
                       String sourceTime, boolean restricted, boolean caseAttachment) {}
  record Build(Map<String, Object> manifest, Map<String, byte[]> content) {}
  public record Archive(byte[] bytes, String filename, String sha256) {}

  public Map<String, Object> inspect(long caseId) {
    return build(caseId, false).manifest();
  }

  public Archive archive(long caseId) {
    var built = build(caseId, true);
    byte[] bytes = zip(built);
    access.caseRow(caseId); // Permission is checked again at delivery, not inherited from an old export.
    db.exec(
        "INSERT audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,"
            + "action,result,summary,request_id) VALUES('IMPORT_EXPORT',#{p.actor},#{p.merchant},"
            + "'CASE_DOSSIER',#{p.id},'DOSSIER_EXPORT','SUCCESS',#{p.summary},#{p.request})",
        Api.params("actor", access.actor().id(), "merchant", caseMap(built).get("merchantId"),
            "id", caseId, "summary", "证据档案 " + caseId + " / " + FileStore.sha(bytes),
            "request", RequestContext.requestId()));
    return new Archive(bytes, "Reconis-case-" + caseId + "-evidence.zip", FileStore.sha(bytes));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> caseMap(Build build) {
    return (Map<String, Object>) build.manifest().get("case");
  }

  private Build build(long caseId, boolean includeBytes) {
    String started = Instant.now().toString();
    var c = new LinkedHashMap<>(access.caseRow(caseId));
    var p = Api.params("case", caseId, "event", c.get("sourceEventKey"),
        "caseMerchant", c.get("merchantId"));
    var history = bounded(db.list(
        "SELECT TOP 501 a.id,a.actor_user_id,u.display_name actor_name,a.action,a.result,a.summary,"
            + "a.before_json,a.after_json,a.occurred_at,a.request_id FROM audit_log a LEFT JOIN "
            + "app_user u ON u.id=a.actor_user_id WHERE a.object_type='CASE' AND a.object_id=#{p.case}"
            + " AND " + access.scope("a.scope_merchant_id", p) + " ORDER BY a.id", p));
    Set<Long> reviewedResults = new LinkedHashSet<>();
    addId(reviewedResults, c.get("originResultId"));
    addId(reviewedResults, c.get("latestResultId"));
    var references = new ArrayList<FileReference>();
    var availabilityGaps = new ArrayList<Map<String, Object>>();
    addAttachment(references, Api.text(c, "evidenceFileUri"), "CASE_ATTACHMENT", caseId,
        c.get("updatedAt"));
    for (var h : history) {
      for (String key : List.of("beforeJson", "afterJson")) {
        var snapshot = snapshot(h.get(key));
        addId(reviewedResults, snapshot.get("latest_result_id"));
        addId(reviewedResults, snapshot.get("target_result_id"));
        addAttachment(references, Api.text(snapshot, "evidence_file_uri"), "HISTORICAL_ATTACHMENT",
            Db.id(h, "id"), h.get("occurredAt"));
      }
    }
    var results = bounded(db.list(
        "SELECT TOP 501 r.*,b.batch_no,b.merchant_id,b.observed_at,b.period_start,b.period_end,"
            + "b.statement_source_batch_id,e.amount_cent local_amount_cent,s.amount_cent channel_amount_cent "
            + "FROM reconciliation_result r JOIN reconciliation_batch b ON b.id=r.batch_id "
            + "LEFT JOIN transaction_entry e ON e.id=r.entry_id "
            + "LEFT JOIN statement_line s ON s.id=r.statement_line_id WHERE "
            + access.scope("b.merchant_id", p)
            + " AND (#{p.caseMerchant} IS NULL OR b.merchant_id=#{p.caseMerchant})"
            + " AND (r.source_event_key=#{p.event} OR r.id IN ("
            + ids(reviewedResults, "review", p) + ")) ORDER BY r.id", p));
    Set<Long> batchIds = new LinkedHashSet<>(), statementIds = new LinkedHashSet<>(),
        entryIds = new LinkedHashSet<>();
    for (var r : results) {
      addId(batchIds, r.get("batchId"));
      addId(batchIds, r.get("statementSourceBatchId"));
      addId(statementIds, r.get("statementLineId"));
      addId(entryIds, r.get("entryId"));
      r.put("origin", sameId(r.get("id"), c.get("originResultId")));
      r.put("currentEvidence", sameId(r.get("id"), c.get("latestResultId")));
      r.put("referencedInWorkflow", reviewedResults.contains(Db.id(r, "id")));
    }
    var batches = bounded(db.list(
        "SELECT TOP 501 b.* FROM reconciliation_batch b WHERE b.id IN ("
            + ids(batchIds, "batch", p) + ") AND " + access.scope("b.merchant_id", p)
            + " ORDER BY b.id", p));
    for (var b : batches) {
      String uri = Api.text(b, "sourceFileUri");
      if (!uri.isBlank()) references.add(new FileReference(uri, Api.text(b, "fileSha256"),
          "ORIGINAL_BILL", text(b.get("id")), timestamp(b.get("createdAt")),
          !access.platform() && !"TRADE_QUERY".equals(b.get("sourceType")), false));
      else availabilityGaps.add(Api.params("kind", "ORIGINAL_BILL", "sourceId", b.get("id"),
          "message", "批次未保存原始文件引用（来源类型：" + b.get("sourceType") + "）"));
    }
    var statements = bounded(db.list(
        "SELECT TOP 501 s.* FROM statement_line s JOIN reconciliation_batch b ON b.id=s.import_batch_id "
            + "WHERE s.id IN (" + ids(statementIds, "statement", p) + ") AND "
            + access.scope("b.merchant_id", p) + " ORDER BY s.id", p));
    var entries = bounded(db.list(
        "SELECT TOP 501 e.* FROM v_transaction_fact e WHERE e.id IN ("
            + ids(entryIds, "entry", p) + ") AND " + access.scope("e.merchant_id", p)
            + " ORDER BY e.id", p));
    Set<Long> orderIds = new LinkedHashSet<>();
    for (var e : entries) addId(orderIds, e.get("orderId"));
    // A channel-only difference has no ledger entry, but can still identify an authorized order.
    var fromStatements = db.list(
        "SELECT DISTINCT o.id FROM v_order o JOIN payment_attempt pay ON pay.order_id=o.id "
            + "JOIN statement_line s ON s.out_trade_no=pay.out_trade_no "
            + "WHERE s.id IN (" + ids(statementIds, "orderStatement", p) + ") AND "
            + access.scope("o.merchant_id", p)
            + " AND (#{p.caseMerchant} IS NULL OR o.merchant_id=#{p.caseMerchant})", p);
    for (var o : fromStatements) addId(orderIds, o.get("id"));
    var orders = bounded(db.list(
        "SELECT TOP 501 o.* FROM v_order o WHERE o.id IN (" + ids(orderIds, "order", p)
            + ") AND " + access.scope("o.merchant_id", p) + " ORDER BY o.id", p));
    // Repeat the scope on each relation; knowing a source id never grants access to its children.
    String orderScope = "o.id IN (" + ids(orderIds, "childOrder", p) + ") AND "
        + access.scope("o.merchant_id", p);
    var items = bounded(db.list(
        "SELECT TOP 501 i.* FROM order_item i JOIN v_order o ON o.id=i.order_id WHERE "
            + orderScope + " ORDER BY i.order_id,i.line_no", p));
    var payments = bounded(db.list(
        "SELECT TOP 501 pay.id,pay.order_id,pay.out_trade_no,pay.channel_code,pay.channel_env,"
            + "pay.channel_trade_no,pay.status,pay.is_primary,pay.query_count,pay.confirmed_at,"
            + "pay.created_at,pay.updated_at FROM payment_attempt pay JOIN v_order o ON o.id=pay.order_id "
            + "WHERE " + orderScope + " ORDER BY pay.id", p));
    var refunds = bounded(db.list(
        "SELECT TOP 501 r.id,r.payment_id,r.out_request_no,r.amount_cent,r.reason,r.status,r.applicant_id,"
            + "r.reviewed_by,r.reviewed_at,r.review_reason,r.succeeded_at,r.created_at,r.updated_at "
            + "FROM refund_request r JOIN payment_attempt pay ON pay.id=r.payment_id "
            + "JOIN v_order o ON o.id=pay.order_id WHERE " + orderScope + " ORDER BY r.id", p));
    Set<Long> paymentIds = new LinkedHashSet<>(), refundIds = new LinkedHashSet<>();
    for (var payment : payments) addId(paymentIds, payment.get("id"));
    for (var refund : refunds) addId(refundIds, refund.get("id"));
    var channelHistory = bounded(db.list(
        "SELECT TOP 501 a.id,a.object_type,a.object_id,a.action,a.result,a.summary,a.before_json,"
            + "a.after_json,a.occurred_at,a.request_id FROM audit_log a WHERE "
            + access.scope("a.scope_merchant_id", p)
            + " AND ((a.object_type='PAYMENT' AND a.object_id IN (" + ids(paymentIds, "auditPayment", p)
            + ")) OR (a.object_type='REFUND' AND a.object_id IN (" + ids(refundIds, "auditRefund", p)
            + "))) ORDER BY a.id", p));
    // Include all ledger facts of the linked orders, so partial refunds remain visible in context.
    var ledger = bounded(db.list(
        "SELECT TOP 501 e.* FROM v_transaction_fact e JOIN v_order o ON o.id=e.order_id WHERE "
            + orderScope + " ORDER BY e.id", p));
    for (var e : ledger) {
      String uri = Api.text(e, "evidenceFileUri");
      if (!uri.isBlank()) references.add(new FileReference(uri, "", "CHANNEL_RESPONSE",
          text(e.get("id")), timestamp(e.get("recordedAt")), false, false));
      else availabilityGaps.add(Api.params("kind", "CHANNEL_RESPONSE", "sourceId", e.get("id"),
          "message", "该入账事实没有留存渠道响应文件引用"));
    }
    var notifications = bounded(db.list(
        "SELECT TOP 501 n.* FROM payment_notification n JOIN payment_attempt pay ON pay.id=n.payment_id "
            + "JOIN v_order o ON o.id=pay.order_id WHERE " + orderScope + " ORDER BY n.id", p));
    for (var n : notifications) {
      String uri = Api.text(n, "payloadFileUri");
      if (!uri.isBlank()) references.add(new FileReference(uri, Api.text(n, "payloadHash"),
          "PAYMENT_NOTIFICATION", text(n.get("id")), timestamp(n.get("receivedAt")), false, false));
      else availabilityGaps.add(Api.params("kind", "PAYMENT_NOTIFICATION", "sourceId", n.get("id"),
          "message", "该通知未保存原始载荷文件，档案仅保留通知状态与散列记录"));
    }
    var content = new LinkedHashMap<String, byte[]>();
    var fileEntries = verifyFiles(caseId, references, includeBytes, content);
    var current = access.caseRow(caseId);
    if (!Objects.deepEquals(c.get("version"), current.get("version")))
      throw Api.fail(409, "工单在整理档案期间发生变化，请刷新后重新生成");
    long failed = fileEntries.stream().filter(f -> Set.of("MISSING", "HASH_MISMATCH", "INVALID_PATH",
        "UNREADABLE", "TOO_LARGE", "METADATA_MISMATCH").contains(f.get("verification"))).count();
    long restricted = fileEntries.stream().filter(f -> "RESTRICTED".equals(f.get("verification"))).count();
    long unverified = fileEntries.stream().filter(f -> "NO_BASELINE_HASH".equals(f.get("verification"))).count();
    var manifest = new LinkedHashMap<String, Object>();
    manifest.put("schemaVersion", "1");
    manifest.put("collectionStartedAt", started);
    manifest.put("collectionCompletedAt", Instant.now().toString());
    manifest.put("scope", Api.params("caseId", caseId, "merchantId", c.get("merchantId"),
        "actorId", access.actor().id(), "role", access.actor().role(),
        "environment", files.root.getFileName().toString().equals("demo") ? "DEMO" : "ALIPAY_SANDBOX",
        "sourceEventKey", c.get("sourceEventKey"), "moneyUnit", "CNY_CENT",
        "timeZone", "UTC", "collectionMode", "CURRENT_AUTHORIZED_RECORDS",
        "filter", "本工单同一事件的授权对账版本、复核引用及关联订单；原件按当前角色授权",
        "channelEvidenceScope", "仅包含通知、入账及查询差异批次中留存的渠道文件；未留存的响应无法重建",
        "historicalRule", "对账结果保留原分类；订单与渠道状态为采集时状态，非历史时间点重建"));
    manifest.put("case", c);
    manifest.put("diagnosis", diagnosis(c, results));
    manifest.put("orders", orders);
    manifest.put("orderItems", items);
    manifest.put("payments", payments);
    manifest.put("refunds", refunds);
    manifest.put("ledger", ledger);
    manifest.put("notifications", notifications);
    manifest.put("channelHistory", channelHistory);
    manifest.put("statementLines", statements);
    manifest.put("batches", batches);
    manifest.put("reconciliationVersions", results);
    manifest.put("workflowHistory", history);
    manifest.put("files", fileEntries);
    manifest.put("availabilityGaps", availabilityGaps);
    manifest.put("integrity", Api.params("status", failed > 0 ? "INCOMPLETE" : fileEntries.isEmpty()
        ? "NO_FILES" : restricted + unverified > 0 || !availabilityGaps.isEmpty()
        ? "QUALIFIED" : "VERIFIED", "failedFiles", failed, "restrictedFiles", restricted,
        "withoutBaselineHash", unverified, "totalFiles", fileEntries.size(),
        "meaning", "VERIFIED仅表示当前授权范围内已列文件与留存散列一致，不证明渠道内容真实或档案未遗漏其他来源"));
    return new Build(manifest, content);
  }

  private void addAttachment(List<FileReference> refs, String uri, String kind, long source, Object time) {
    if (!uri.isBlank()) refs.add(new FileReference(uri, "", kind, Long.toString(source),
        timestamp(time), false, true));
  }

  List<Map<String, Object>> verifyFiles(long caseId, List<FileReference> refs, boolean includeBytes,
                                      Map<String, byte[]> content) {
    var grouped = new LinkedHashMap<String, List<FileReference>>();
    for (var ref : refs) grouped.computeIfAbsent(ref.uri(), ignored -> new ArrayList<>()).add(ref);
    var result = new ArrayList<Map<String, Object>>();
    long total = 0;
    for (var group : grouped.entrySet()) {
      String uri = group.getKey();
      var item = new LinkedHashMap<String, Object>();
      item.put("sourceUri", uri);
      item.put("sources", group.getValue().stream().map(r -> Api.params("kind", r.kind(),
          "id", r.sourceId(), "at", r.sourceTime())).toList());
      item.put("included", false);
      if (group.getValue().stream().anyMatch(FileReference::restricted)) {
        item.put("verification", "RESTRICTED");
        item.put("message", "原始账单可能包含其他商户记录；仅平台财务和审计可导出整份原件，授权行已列入档案");
        result.add(item);
        continue;
      }
      try {
        Path path = safePath(uri);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
          item.put("verification", "MISSING");
        } else {
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
              || !path.toRealPath().startsWith(files.root.toRealPath())) {
            item.put("verification", "INVALID_PATH");
            result.add(item);
            continue;
          }
          long size = Files.size(path);
          if (size > MAX_FILE_BYTES || size + total > MAX_ARCHIVE_BYTES) {
            item.put("verification", "TOO_LARGE");
            result.add(item);
            continue;
          }
          var expected = new LinkedHashSet<String>();
          group.getValue().stream().map(FileReference::expectedSha256).filter(s -> !s.isBlank())
              .map(s -> s.strip().toLowerCase(Locale.ROOT)).forEach(expected::add);
          if (group.getValue().stream().anyMatch(FileReference::caseAttachment)) {
            Path metadata = safePath(uri + ".meta.json");
            if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
                || !metadata.toRealPath().startsWith(files.root.toRealPath())
                || Files.size(metadata) > 65536) {
              item.put("verification", "METADATA_MISMATCH");
              result.add(item);
              continue;
            }
            var meta = files.jsonRead(uri + ".meta.json");
            if (!Long.toString(caseId).equals(text(meta.get("caseId")))
                || !uri.equals(meta.get("uri"))) {
              item.put("verification", "METADATA_MISMATCH");
              result.add(item);
              continue;
            }
            String baseline = Api.text(meta, "sha256");
            if (!baseline.isBlank()) expected.add(baseline.toLowerCase(Locale.ROOT));
            item.put("originalName", meta.get("name"));
          }
          byte[] bytes;
          // A bounded read also protects against a file growing between size() and open().
          try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes((int) MAX_FILE_BYTES + 1);
          }
          if (bytes.length > MAX_FILE_BYTES || bytes.length + total > MAX_ARCHIVE_BYTES) {
            item.put("verification", "TOO_LARGE");
            result.add(item);
            continue;
          }
          String actual = FileStore.sha(bytes);
          String status = expected.isEmpty() ? "NO_BASELINE_HASH"
              : expected.size() == 1 && expected.contains(actual) ? "VERIFIED" : "HASH_MISMATCH";
          item.put("verification", status);
          item.put("expectedSha256", expected);
          item.put("sha256", actual);
          item.put("bytes", bytes.length);
          total += bytes.length;
          // Corrupt files are retained under a clearly marked name for investigation, never as verified.
          String archivePath = "files/" + (status.equals("HASH_MISMATCH") ? "hash-mismatch/" : "") + uri;
          item.put("archivePath", archivePath);
          if (includeBytes) {
            content.put(archivePath, bytes);
            item.put("included", true);
          }
        }
      } catch (org.springframework.web.server.ResponseStatusException e) {
        item.put("verification", e.getStatusCode().value() == 403 ? "INVALID_PATH" : "UNREADABLE");
      } catch (IOException e) {
        item.put("verification", "UNREADABLE");
      }
      result.add(item);
    }
    return result;
  }

  private Path safePath(String uri) {
    if (!uri.matches("(?:imports|evidence|channel)/[a-zA-Z0-9_.-]+") || uri.contains(".."))
      throw Api.fail(403, "证据路径无效");
    return files.resolve(uri);
  }

  static Map<String, Object> diagnosis(Map<String, Object> c, List<Map<String, Object>> results) {
    var facts = new ArrayList<Map<String, Object>>();
    facts.add(Api.params("kind", "RECORDED_FACT", "text", "工单起因分类为“" + label(c.get("classification"))
        + "”；当前状态为“" + label(c.get("status")) + "”", "sourceType", "CASE", "sourceId", c.get("id")));
    for (var r : results) {
      var fact = Api.params("kind", "RECORDED_FACT", "text", "批次 " + r.get("batchNo")
          + " 的对账分类为“" + label(r.get("classification")) + "”", "sourceType", "RECONCILIATION_RESULT",
          "sourceId", r.get("id"), "localAmountCent", r.get("localAmountCent"),
          "channelAmountCent", r.get("channelAmountCent"));
      if (r.get("localAmountCent") != null && r.get("channelAmountCent") != null)
        fact.put("differenceCent", new java.math.BigInteger(text(r.get("localAmountCent")))
            .subtract(new java.math.BigInteger(text(r.get("channelAmountCent")))).toString());
      facts.add(fact);
    }
    String classification = Api.text(c, "classification");
    String hypothesis = switch (classification) {
      case "AMOUNT_MISMATCH" -> "可能存在账单金额口径、重复成功支付或渠道记录差异，尚需原始凭据核实。";
      case "LOCAL_ONLY" -> "可能存在账单出账延迟、账期范围或输入完整性差异；仅缺少渠道行不能证明资金损失。";
      case "CHANNEL_ONLY" -> "可能存在本地通知遗漏、入账恢复尚未完成或商户归属识别问题。";
      case "BILL_DUPLICATE" -> "可能为文件重复行或渠道重复记录，需先确认唯一交易标识。";
      case "EXTRA_SUCCESS" -> "同一订单可能存在额外成功支付，需确认是否应退回多收款项。";
      default -> "需结合交易标识、账期、渠道查询和原始账单核实，当前分类不等于最终责任结论。";
    };
    return Api.params("facts", facts, "hypotheses", List.of(Api.params("kind", "UNCONFIRMED_HYPOTHESIS",
        "text", hypothesis)), "nextChecks", List.of("核对账单账期、完整性声明和原件散列校验结果",
        "按支付号或退款请求号比对渠道响应、通知验签结果及本地入账事实",
        "保留处理证据并由另一人员复核；重新匹配结案须引用更新的匹配结果"),
        "conclusion", Api.params("status", "CLOSED".equals(c.get("status")) ? "REVIEWED" : "PENDING_REVIEW",
            "resolutionCode", c.get("resolutionCode"), "text", c.get("resolutionText"),
            "reviewedBy", c.get("reviewedBy"), "closedAt", c.get("closedAt")));
  }

  byte[] zip(Build build) {
    try {
      var out = new ByteArrayOutputStream();
      byte[] manifest = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(Api.normalize(build.manifest()));
      try (var zip = new ZipOutputStream(out, java.nio.charset.StandardCharsets.UTF_8)) {
        writeEntry(zip, "manifest.json", manifest);
        writeEntry(zip, "manifest.sha256", (FileStore.sha(manifest) + "  manifest.json\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var file : build.content().entrySet()) writeEntry(zip, file.getKey(), file.getValue());
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw Api.fail(500, "证据档案生成失败");
    }
  }

  private static void writeEntry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(bytes);
    out.closeEntry();
  }

  private List<Map<String, Object>> bounded(List<Map<String, Object>> rows) {
    if (rows.size() > MAX_ROWS) throw Api.fail(422, "此工单关联记录超过单次档案上限（500条），请按批次导出核查");
    return rows;
  }

  private String ids(Set<Long> ids, String prefix, Map<String, Object> p) {
    if (ids.isEmpty()) return "NULL";
    if (ids.size() > MAX_ROWS) throw Api.fail(422, "工单关联范围超过单次档案上限");
    var placeholders = new ArrayList<String>();
    int n = 0;
    for (Long id : ids) {
      String key = prefix + n++;
      p.put(key, id);
      placeholders.add("#{p." + key + "}");
    }
    return String.join(",", placeholders);
  }

  private Map<String, Object> snapshot(Object value) {
    if (value == null || value.toString().isBlank()) return Map.of();
    try {
      var result = json.readValue(value.toString(), new TypeReference<Map<String, Object>>() {});
      return result == null ? Map.of() : result;
    } catch (IOException e) {
      throw Api.fail(409, "历史审核记录格式异常，无法完整整理证据档案");
    }
  }

  private static void addId(Set<Long> ids, Object id) {
    if (id != null && id.toString().matches("[0-9]+")) ids.add(Long.parseLong(id.toString()));
  }

  private static boolean sameId(Object a, Object b) {
    return a != null && b != null && a.toString().equals(b.toString());
  }

  private static String timestamp(Object value) { return Objects.toString(Api.normalize(value), ""); }
  private static String text(Object value) { return Objects.toString(value, ""); }
  private static String label(Object value) {
    return switch (text(value)) {
      case "MATCHED" -> "账实相符";
      case "AMOUNT_MISMATCH" -> "金额不符";
      case "LOCAL_ONLY" -> "本地单边";
      case "CHANNEL_ONLY" -> "渠道单边";
      case "BILL_DUPLICATE" -> "账单重复";
      case "KEY_UNRESOLVED" -> "标识待核实";
      case "PENDING_CONFIRMATION" -> "待确认";
      case "EXTRA_SUCCESS" -> "额外成功支付";
      case "OPEN" -> "待分派";
      case "ASSIGNED" -> "已分派";
      case "PROCESSING" -> "处理中";
      case "PENDING_REVIEW" -> "待复核";
      case "CLOSED" -> "已结案";
      default -> text(value);
    };
  }
}
