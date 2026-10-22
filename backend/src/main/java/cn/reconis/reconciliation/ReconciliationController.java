package cn.reconis.reconciliation;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ReconciliationController {
  private final Db db;
  private final Access access;
  private final ImportService importer;

  public ReconciliationController(Db db, Access access, ImportService importer) {
    this.db = db;
    this.access = access;
    this.importer = importer;
  }

  @PostMapping("/reconciliation/imports")
  Object upload(
      @RequestParam MultipartFile file,
      @RequestParam(required = false) Long merchantId,
      @RequestParam String start,
      @RequestParam String end,
      @RequestParam(defaultValue = "UNKNOWN") String completeness,
      @RequestParam(defaultValue = "true") boolean preview)
      throws Exception {
    return Api.ok(
        importer.statement(
            file.getBytes(), extension(file), merchantId, start, end, completeness, preview));
  }

  @PostMapping("/merchants/imports")
  Object merchants(
      @RequestParam MultipartFile file, @RequestParam(defaultValue = "true") boolean preview)
      throws Exception {
    return Api.ok(importer.merchants(file.getBytes(), extension(file), preview));
  }

  private String extension(MultipartFile f) {
    String name = Objects.toString(f.getOriginalFilename(), "").toLowerCase();
    if (name.endsWith(".xlsx")) return "xlsx";
    if (name.endsWith(".xml")) return "xml";
    if (name.endsWith(".csv")) return "csv";
    if (name.endsWith(".zip")) return "zip";
    throw Api.fail(422, "请选择 XLSX、XML 或支付宝交易账单 CSV / ZIP 文件");
  }

  @GetMapping("/reconciliation/batches")
  Object batches(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "") String q) {
    var p = Api.params("q", "%" + q + "%");
    return Api.ok(
        db.page(
            "FROM (SELECT b.*,m.name merchant_name FROM reconciliation_batch b LEFT JOIN merchant m"
                + " ON m.id=b.merchant_id) x",
            access.scope("x.merchant_id", p) + " AND source_type<>'AUTO_JOB' AND batch_no LIKE #{p.q}",
            "id DESC",
            p,
            page,
            20));
  }

  @PostMapping("/reconciliation/batches/{id}/run")
  Object run(@PathVariable long id) {
    var b = access.batch(id);
    batchWrite(b);
    try {
      db.exec("EXEC usp_run_reconciliation @batch_id=#{p.id}", Api.params("id", id));
    } catch (Exception e) {
      db.exec(
          "UPDATE reconciliation_batch SET status='FAILED' WHERE id=#{p.id} AND"
              + " status<>'COMPLETED'",
          Api.params("id", id));
      throw e;
    }
    return Api.ok(Map.of("id", id, "message", "对账完成，结果已固化"));
  }

  @PostMapping("/reconciliation/batches/{id}/rerun")
  Object rerun(@PathVariable long id) {
    var b = access.batch(id);
    batchWrite(b);
    if (!"COMPLETED".equals(b.get("status"))) throw Api.fail(409, "请先完成原批次对账，再创建重跑批次");
    var p = Api.params("id", id, "no", Api.no("BAT"), "actor", access.actor().id());
    long next =
        db.insert(
            "INSERT"
                + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,statement_source_batch_id,baseline_batch_id,source_file_uri,completeness,created_by)"
                + " OUTPUT INSERTED.id SELECT"
                + " #{p.no},scope_type,merchant_id,period_start,period_end,source_type,COALESCE(statement_source_batch_id,id),id,source_file_uri,completeness,#{p.actor}"
                + " FROM reconciliation_batch WHERE id=#{p.id}",
            p);
    try {
      db.exec("EXEC usp_run_reconciliation @batch_id=#{p.id}", Api.params("id", next));
    } catch (Exception e) {
      db.exec(
          "UPDATE reconciliation_batch SET status='FAILED' WHERE id=#{p.id} AND"
              + " status<>'COMPLETED'",
          Api.params("id", next));
      throw e;
    }
    return Api.ok(Map.of("id", next, "baselineBatchId", id));
  }

  private void batchWrite(Map<String, Object> batch) {
    if ("AUTO_JOB".equals(batch.get("sourceType"))) throw Api.fail(422, "请在自动对账中心管理获取任务");
    if (batch.get("merchantId") == null) access.role("PLATFORM_FINANCE");
    else access.merchant(Db.id(batch, "merchantId"), "OWNER", "FINANCE");
  }

  @PostMapping("/reconciliation/batches/{id}/identify")
  Object identify(@PathVariable long id, @RequestParam(defaultValue = "true") boolean preview)
      throws Exception {
    return Api.ok(importer.identify(id, preview));
  }

  @GetMapping("/reconciliation/batches/{id}/results")
  Object results(
      @PathVariable long id,
      @RequestParam(defaultValue = "") String classification,
      @RequestParam(defaultValue = "1") int page) {
    var batch = access.batch(id);
    var p = Api.params("id", id, "classification", classification);
    var result =
        db.page(
            "FROM (SELECT r.*,l.amount_cent local_amount_cent,s.amount_cent"
                + " channel_amount_cent,s.event_type,s.out_trade_no,s.out_request_no,l.order_id,l.order_no,l.store_name,l.channel_occurred_at"
                + " local_occurred_at,s.channel_occurred_at channel_occurred_at FROM"
                + " reconciliation_result r LEFT JOIN v_transaction_fact l ON l.id=r.entry_id LEFT"
                + " JOIN statement_line s ON s.id=r.statement_line_id) x",
            "batch_id=#{p.id} AND (#{p.classification}='' OR classification=#{p.classification})",
            "id",
            p,
            page,
            30);
    var response = new LinkedHashMap<>(result);
    response.put("batch", batch);
    response.put(
        "identifications",
        "PLATFORM_UNASSIGNED".equals(batch.get("scopeType"))
            ? db.list(
                "SELECT TOP 20 a.id,a.occurred_at,a.after_json,u.display_name actor_name FROM"
                    + " audit_log a LEFT JOIN app_user u ON u.id=a.actor_user_id WHERE"
                    + " a.object_type='RECONCILIATION_BATCH' AND a.object_id=#{p.id} AND"
                    + " a.action='BATCH_REIDENTIFIED' ORDER BY a.id DESC",
                p)
            : List.of());
    response.put(
        "distribution",
        db.list(
            "SELECT classification,COUNT_BIG(*) count FROM reconciliation_result WHERE"
                + " batch_id=#{p.id} GROUP BY classification",
            p));
    return Api.ok(response);
  }

  @GetMapping("/reconciliation/compare")
  Object compare(@RequestParam long baseline, @RequestParam long current) {
    var a = access.batch(baseline);
    var b = access.batch(current);
    if (!Objects.equals(a.get("merchantId"), b.get("merchantId"))
        || !Objects.equals(a.get("scopeType"), b.get("scopeType")))
      throw Api.fail(422, "请选择同一商户或同一平台待核验范围的批次");
    return Api.ok(
        db.list(
            "WITH a AS(SELECT source_event_key,classification,ROW_NUMBER() OVER(PARTITION BY"
                + " source_event_key ORDER BY id) n FROM reconciliation_result WHERE"
                + " batch_id=#{p.a}),b AS(SELECT source_event_key,classification,ROW_NUMBER()"
                + " OVER(PARTITION BY source_event_key ORDER BY id) n FROM reconciliation_result"
                + " WHERE batch_id=#{p.b}) SELECT COALESCE(a.source_event_key,b.source_event_key)"
                + " event_key,a.classification before_classification,b.classification"
                + " after_classification,CASE WHEN a.classification=b.classification THEN"
                + " 'UNCHANGED' WHEN a.classification IS NULL THEN 'NEW' WHEN"
                + " b.classification='MATCHED' THEN 'RESOLVED' ELSE 'CHANGED' END change_type FROM"
                + " a FULL OUTER JOIN b ON a.source_event_key=b.source_event_key AND a.n=b.n ORDER"
                + " BY event_key",
            Api.params("a", baseline, "b", current)));
  }
}
