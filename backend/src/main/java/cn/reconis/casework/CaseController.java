package cn.reconis.casework;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.nio.file.Files;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {
  private final Db db;
  private final Access access;
  private final FileStore files;
  private final FullTextReadiness fulltext;

  public CaseController(Db db, Access access, FileStore files, FullTextReadiness fulltext) {
    this.db = db;
    this.access = access;
    this.files = files;
    this.fulltext = fulltext;
  }

  @GetMapping
  Object list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String classification,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "1") int page) {
    var p = Api.params("q", "%" + q + "%", "status", status, "classification", classification);
    String where =
        access.scope("c.merchant_id", p)
            + " AND (title LIKE #{p.q} OR case_no LIKE #{p.q}) AND (#{p.status}='' OR"
            + " status=#{p.status}) AND (#{p.classification}='' OR"
            + " classification=#{p.classification})";
    if (mine) where += " AND assignee_id=#{p.actor}";
    return Api.ok(
        db.page(
            "FROM v_case c",
            where,
            "CASE WHEN status='CLOSED' THEN 1 ELSE 0 END,due_at,id DESC",
            p,
            page,
            20));
  }

  @GetMapping("/search")
  Object search(@RequestParam String q, @RequestParam(defaultValue = "") String status) {
    access.actor();
    q = q.strip();
    if (q.length() > 400) throw Api.fail(422, "检索内容最多400字");
    if (q.isBlank()) return Api.ok(Map.of("items", List.of(), "engine", "SQL_SERVER_FREETEXTTABLE"));
    var p = Api.params("q", q, "status", status);
    return Api.ok(fulltext.query(() ->
        Map.of(
            "engine",
            "SQL_SERVER_FREETEXTTABLE",
            "indexPopulationStatus",
            Map.of("status", -1),
            "readiness", fulltext.status(),
            "items",
            db.search(
                "SELECT TOP 30 c.*,ft.[RANK] relevance FROM"
                    + " FREETEXTTABLE(exception_task,(title,description,analysis_text,resolution_text),#{p.q},LANGUAGE"
                    + " 2052) ft JOIN v_case c ON c.id=ft.[KEY] WHERE "
                    + access.scope("c.merchant_id", p)
                    + " AND (#{p.status}='' OR c.status=#{p.status}) ORDER BY ft.[RANK] DESC,c.id"
                    + " DESC",
                p))));
  }

  @GetMapping("/search/status")
  Object searchStatus() { access.actor(); return Api.ok(fulltext.status()); }

  @GetMapping("/{id}")
  Object detail(@PathVariable long id) {
    var row = access.caseRow(id);
    var p = Api.params("id", row.get("latestResultId"), "case", id);
    row.put(
        "source",
        db.need(
            "SELECT r.*,b.batch_no,b.merchant_id,m.name"
                + " merchant_name,l.order_id,l.order_no,l.store_name,COALESCE(l.out_trade_no,s.out_trade_no)"
                + " out_trade_no,l.amount_cent local_amount_cent,s.amount_cent"
                + " channel_amount_cent,s.out_request_no,s.channel_trade_no FROM"
                + " reconciliation_result r JOIN reconciliation_batch b ON b.id=r.batch_id LEFT"
                + " JOIN merchant m ON m.id=b.merchant_id LEFT JOIN v_transaction_fact l ON"
                + " l.id=r.entry_id LEFT JOIN statement_line s ON s.id=r.statement_line_id WHERE"
                + " r.id=#{p.id}",
            p));
    row.put(
        "history",
        db.list(
            "SELECT a.*,u.display_name actor_name FROM audit_log a LEFT JOIN app_user u ON"
                + " u.id=a.actor_user_id WHERE object_type='CASE' AND object_id=#{p.case} ORDER BY"
                + " a.id DESC",
            p));
    row.put(
        "matchedEvidence",
        db.list(
            "SELECT result_id id,batch_no,observed_at FROM v_case_matched_evidence"
                + " WHERE case_id=#{p.case} ORDER BY observed_at DESC,result_id DESC",
            p));
    row.put(
        "attributionEvidence",
        row.get("merchantId") == null
            ? db.list(
                "SELECT batch_id,batch_no,merchant_id,merchant_name,result_id,classification FROM"
                    + " v_case_attribution_evidence WHERE case_id=#{p.case} ORDER BY observed_at"
                    + " DESC,result_id DESC",
                p)
            : List.of());
    return Api.ok(row);
  }

  @GetMapping("/{id}/recommendations")
  Object recommendations(@PathVariable long id) {
    var current = access.caseRow(id);
    String text =
        Objects.toString(current.get("title"), "")
            + " "
            + Objects.toString(current.get("description"), "");
    if (text.length() > 400) text = text.substring(0, 400);
    var p =
        Api.params(
            "id",
            id,
            "q",
            text,
            "classification",
            current.get("classification"),
            "reason",
            current.get("reasonCode"));
    return Api.ok(fulltext.query(() ->
        db.search(
            "WITH candidates AS(SELECT"
                + " c.id,c.case_no,c.title,c.classification,c.reason_code,c.resolution_text,c.closed_at,COALESCE(ft.[RANK],0)"
                + " text_rank FROM v_case c LEFT JOIN"
                + " FREETEXTTABLE(exception_task,(title,description,analysis_text,resolution_text),#{p.q},LANGUAGE"
                + " 2052) ft ON ft.[KEY]=c.id WHERE "
                + access.scope("c.merchant_id", p)
                + " AND c.id<>#{p.id} AND c.status='CLOSED' AND NULLIF(c.resolution_text,N'') IS"
                + " NOT NULL AND (c.classification=#{p.classification} OR ft.[RANK]>0)) SELECT TOP"
                + " 5 *,0.50*CASE WHEN classification=#{p.classification} THEN 1 ELSE 0"
                + " END+0.20*CASE WHEN reason_code=#{p.reason} THEN 1 ELSE 0"
                + " END+COALESCE(0.30*text_rank/NULLIF(MAX(text_rank) OVER(),0.0),0) score,CASE"
                + " WHEN classification=#{p.classification} AND text_rank>0 THEN N'同类差错，处理文本相关'"
                + " WHEN classification=#{p.classification} THEN N'同类案例参考' ELSE N'处理文本相关' END"
                + " recommendation_reason FROM candidates ORDER BY score DESC,closed_at DESC,id"
                + " DESC",
            p)));
  }

  @GetMapping("/{id}/assignees")
  Object assignees(@PathVariable long id) {
    var c = access.caseRow(id);
    access.caseWrite(c, "OWNER", "FINANCE");
    if (c.get("merchantId") == null)
      return Api.ok(
          db.list(
              "SELECT id,user_id=id,display_name,platform_role role_code FROM app_user WHERE"
                  + " status='ACTIVE' AND platform_role='PLATFORM_FINANCE' ORDER BY"
                  + " display_name,id",
              Map.of()));
    return Api.ok(
        db.list(
            "SELECT mm.id,mm.user_id,u.display_name,mm.role_code FROM merchant_member mm JOIN"
                + " app_user u ON u.id=mm.user_id WHERE mm.merchant_id=#{p.merchant} AND"
                + " mm.status='ACTIVE' AND u.status='ACTIVE' AND u.platform_role NOT IN"
                + " ('ADMIN','AUDITOR') AND mm.role_code IN ('OWNER','FINANCE','CASHIER') ORDER BY"
                + " u.display_name,u.id",
            Api.params("merchant", c.get("merchantId"))));
  }

  private String version(Map<String, Object> body) {
    String value = Api.required(body, "version");
    if (!value.matches("[0-9a-fA-F]{16}")) throw Api.fail(422, "工单版本格式不正确，请刷新");
    return value;
  }

  @PostMapping("/{id}/assign")
  Object assign(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var c = access.caseRow(id);
    access.caseWrite(c, "OWNER", "FINANCE");
    long user = Api.number(b, "assigneeId");
    int changed =
        db.exec(
            "UPDATE exception_task SET"
                + " assignee_id=#{p.user},status='ASSIGNED',updated_at=SYSUTCDATETIME() WHERE"
                + " id=#{p.id} AND status IN ('OPEN','ASSIGNED','PROCESSING')"
                + " AND version=CONVERT(BINARY(8),#{p.version},2)"
                + " AND EXISTS(SELECT 1 FROM app_user u WHERE u.id=#{p.user} AND u.status='ACTIVE'"
                + " AND ((#{p.merchant} IS NULL AND u.platform_role='PLATFORM_FINANCE')"
                + " OR (#{p.merchant} IS NOT NULL AND u.platform_role NOT IN ('ADMIN','AUDITOR')"
                + " AND EXISTS(SELECT 1 FROM merchant_member mm WHERE mm.user_id=u.id"
                + " AND mm.merchant_id=#{p.merchant} AND mm.status='ACTIVE'"
                + " AND mm.role_code IN ('OWNER','FINANCE','CASHIER')))))",
            Api.params(
                "id", id, "user", user, "merchant", c.get("merchantId"), "version", version(b)));
    if (changed == 0) throw Api.fail(409, "工单已变化或处理人已无办理资格，请刷新");
    return Api.ok(Map.of("message", "任务已分派"));
  }

  private Map<String, Object> handler(long id) {
    var c = access.caseRow(id);
    access.caseWrite(c, "OWNER", "FINANCE", "CASHIER");
    if (!Objects.equals(c.get("assigneeId"), access.actor().id()))
      throw Api.fail(403, "仅当前处理人可编辑和提交");
    return c;
  }

  @PatchMapping("/{id}")
  Object edit(@PathVariable long id, @RequestBody Map<String, Object> b) {
    handler(id);
    int n =
        db.exec(
            "UPDATE exception_task SET"
                + " analysis_text=#{p.analysis},resolution_text=#{p.resolution},status='PROCESSING',updated_at=SYSUTCDATETIME()"
                + " WHERE id=#{p.id} AND assignee_id=#{p.actor} AND status IN"
                + " ('ASSIGNED','PROCESSING') AND version=CONVERT(BINARY(8),#{p.version},2)",
            Api.params(
                "id",
                id,
                "analysis",
                Api.required(b, "analysisText"),
                "resolution",
                Api.required(b, "resolutionText"),
                "version",
                version(b),
                "actor",
                access.actor().id()));
    if (n == 0) throw Api.fail(409, "工单状态或版本已改变，请刷新");
    return Api.ok(Map.of("message", "处理记录已保存"));
  }

  @PostMapping("/{id}/evidence")
  Object evidence(
      @PathVariable long id, @RequestParam MultipartFile file, @RequestParam String version)
      throws Exception {
    var c = handler(id);
    String expectedVersion = version(Map.of("version", version));
    if (!Set.of("ASSIGNED", "PROCESSING").contains(c.get("status")))
      throw Api.fail(409, "仅待处理或处理中的工单可上传证据");
    if (file.isEmpty()) throw Api.fail(422, "请选择非空证据文件");
    String name = Objects.toString(file.getOriginalFilename(), "evidence");
    if (!name.toLowerCase().matches(".*\\.(pdf|png|jpg|jpeg|txt|xlsx|xml)$"))
      throw Api.fail(422, "证据支持 PDF、图片、文本和账单文件");
    byte[] evidenceBytes = file.getBytes();
    String uri =
        files.write(
            "evidence", name.substring(name.lastIndexOf('.')).toLowerCase(), evidenceBytes);
    String fileId = uri.substring(uri.lastIndexOf('/') + 1);
    boolean attached = false;
    try {
      files.jsonWrite(uri + ".meta.json", Map.of("caseId", id, "name", name, "uri", uri,
          "sha256", FileStore.sha(evidenceBytes)));
      int changed =
          db.exec(
              "UPDATE exception_task SET evidence_file_uri=#{p.uri},updated_at=SYSUTCDATETIME()"
                  + " WHERE id=#{p.id} AND assignee_id=#{p.actor} AND status IN"
                  + " ('ASSIGNED','PROCESSING') AND version=CONVERT(BINARY(8),#{p.version},2)",
              Api.params(
                  "id", id, "uri", uri, "actor", access.actor().id(), "version", expectedVersion));
      if (changed == 0) throw Api.fail(409, "工单状态、处理人或版本已变化，请刷新");
      attached = true;
    } finally {
      if (!attached) {
        Files.deleteIfExists(files.resolve(uri));
        Files.deleteIfExists(files.resolve(uri + ".meta.json"));
      }
    }
    return Api.ok(Map.of("fileId", fileId));
  }

  @PostMapping("/{id}/submit")
  Object submit(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var c = handler(id);
    if (Api.text(c, "analysisText").isBlank() || Api.text(c, "resolutionText").isBlank())
      throw Api.fail(422, "先保存问题分析和处理结论");
    if (db.exec(
            "UPDATE exception_task SET"
                + " status='PENDING_REVIEW',submitted_by=#{p.actor},updated_at=SYSUTCDATETIME()"
                + " WHERE id=#{p.id} AND assignee_id=#{p.actor} AND status='PROCESSING'"
                + " AND version=CONVERT(BINARY(8),#{p.version},2)"
                + " AND NULLIF(LTRIM(RTRIM(analysis_text)),N'') IS NOT NULL"
                + " AND NULLIF(LTRIM(RTRIM(resolution_text)),N'') IS NOT NULL",
            Api.params("id", id, "actor", access.actor().id(), "version", version(b)))
        == 0) throw Api.fail(409, "工单状态、处理人或版本已变化，请刷新");
    return Api.ok(Map.of("message", "已提交独立复核"));
  }

  @PostMapping("/{id}/review")
  Object review(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var c = access.caseRow(id);
    access.caseWrite(c, "OWNER", "REVIEWER");
    if (!(b.get("approve") instanceof Boolean)) throw Api.fail(422, "请选择复核决定");
    boolean approve = Boolean.TRUE.equals(b.get("approve"));
    String code = approve ? Api.required(b, "resolutionCode") : "";
    Long evidenceResult =
        Api.text(b, "evidenceResultId").isBlank() ? null : Api.number(b, "evidenceResultId");
    Long attributionBatch =
        Api.text(b, "attributionBatchId").isBlank() ? null : Api.number(b, "attributionBatchId");
    if (evidenceResult != null && (!approve || !code.equals("RECONCILED")))
      throw Api.fail(422, "仅通过重新匹配结论时可引用结果证据");
    if (attributionBatch != null && (!approve || !code.equals("OWNERSHIP_CONFIRMED")))
      throw Api.fail(422, "仅确认商户归属结案时可引用归属批次");
    db.exec(
        "DECLARE @expected BINARY(8)=CONVERT(BINARY(8),#{p.version},2); EXEC usp_review_exception"
            + " @case_id=#{p.id},@reviewer=#{p.actor},@approve=#{p.approve},@resolution_code=#{p.code},@reason=#{p.reason},@evidence_result=#{p.result},@expected_version=@expected,@attribution_batch=#{p.attribution}",
        Api.params(
            "id",
            id,
            "actor",
            access.actor().id(),
            "approve",
            approve,
            "code",
            code,
            "reason",
            Api.required(b, "reason"),
            "result",
            evidenceResult,
            "attribution",
            attributionBatch,
            "version",
            version(b)));
    return Api.ok(Map.of("message", "复核完成，历史对账分类保持原记录"));
  }
}
