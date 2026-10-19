package cn.reconis.casework;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {
  private final Db db;
  private final Access access;
  private final FileStore files;

  public CaseController(Db db, Access access, FileStore files) {
    this.db = db;
    this.access = access;
    this.files = files;
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
    if (q.isBlank()) return Api.ok(List.of());
    var p = Api.params("q", q, "status", status);
    return Api.ok(
        Map.of(
            "engine",
            "SQL_SERVER_FREETEXTTABLE",
            "indexPopulationStatus",
            db.need(
                "SELECT COALESCE(FULLTEXTCATALOGPROPERTY('ReconisCases','PopulateStatus'),-1)"
                    + " status",
                Map.of()),
            "items",
            db.list(
                "SELECT TOP 30 c.*,ft.[RANK] relevance FROM"
                    + " FREETEXTTABLE(exception_task,(title,description,analysis_text,resolution_text),#{p.q},LANGUAGE"
                    + " 2052) ft JOIN v_case c ON c.id=ft.[KEY] WHERE "
                    + access.scope("c.merchant_id", p)
                    + " AND (#{p.status}='' OR c.status=#{p.status}) ORDER BY ft.[RANK] DESC,c.id"
                    + " DESC",
                p)));
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable long id) {
    var row = access.caseRow(id);
    var p = Api.params("id", row.get("latestResultId"), "case", id);
    row.put(
        "source",
        db.need(
            "SELECT r.*,b.batch_no,b.merchant_id,m.name"
                + " merchant_name,l.order_id,l.order_no,l.store_name,l.out_trade_no,l.amount_cent"
                + " local_amount_cent,s.amount_cent"
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
            "SELECT r.id,b.batch_no FROM reconciliation_result r JOIN reconciliation_batch b ON"
                + " b.id=r.batch_id WHERE r.source_event_key=#{p.key} AND"
                + " r.classification='MATCHED' AND b.merchant_id=#{p.merchant} AND"
                + " b.status='COMPLETED' ORDER BY b.id DESC",
            Api.params("key", row.get("sourceEventKey"), "merchant", row.get("merchantId"))));
    return Api.ok(row);
  }

  @GetMapping("/{id}/recommendations")
  Object recommendations(@PathVariable long id) {
    var current = access.caseRow(id);
    String text =
        Objects.toString(current.get("title"), "")
            + " "
            + Objects.toString(current.get("description"), "");
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
    return Api.ok(
        db.list(
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
                + " END+0.30*text_rank/NULLIF(MAX(text_rank) OVER(),0.0) score,CASE WHEN"
                + " classification=#{p.classification} AND text_rank>0 THEN N'同类差错，处理文本相关' WHEN"
                + " classification=#{p.classification} THEN N'同类案例参考' ELSE N'处理文本相关' END"
                + " recommendation_reason FROM candidates ORDER BY COALESCE(0.50*CASE WHEN"
                + " classification=#{p.classification} THEN 1 ELSE 0 END+0.20*CASE WHEN"
                + " reason_code=#{p.reason} THEN 1 ELSE 0 END+0.30*text_rank/NULLIF(MAX(text_rank)"
                + " OVER(),0.0),0.50*CASE WHEN classification=#{p.classification} THEN 1 ELSE 0"
                + " END) DESC,closed_at DESC,id DESC",
            p));
  }

  @PostMapping("/{id}/assign")
  Object assign(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var c = access.caseRow(id);
    access.caseWrite(c, "OWNER", "FINANCE");
    long user = Api.number(b, "assigneeId");
    if (c.get("merchantId") != null)
      db.need(
          "SELECT mm.id FROM merchant_member mm JOIN app_user u ON u.id=mm.user_id WHERE"
              + " merchant_id=#{p.m} AND user_id=#{p.u} AND mm.status='ACTIVE' AND"
              + " u.status='ACTIVE'",
          Api.params("m", c.get("merchantId"), "u", user));
    db.exec(
        "UPDATE exception_task SET"
            + " assignee_id=#{p.user},status='ASSIGNED',updated_at=SYSUTCDATETIME() WHERE"
            + " id=#{p.id} AND status NOT IN ('CLOSED','PENDING_REVIEW')",
        Api.params("id", id, "user", user));
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
                + " WHERE id=#{p.id} AND status IN ('ASSIGNED','PROCESSING') AND"
                + " version=CONVERT(BINARY(8),#{p.version},2)",
            Api.params(
                "id",
                id,
                "analysis",
                Api.required(b, "analysisText"),
                "resolution",
                Api.required(b, "resolutionText"),
                "version",
                Api.required(b, "version")));
    if (n == 0) throw Api.fail(409, "工单状态或版本已改变，请刷新");
    return Api.ok(Map.of("message", "处理记录已保存"));
  }

  @PostMapping("/{id}/evidence")
  Object evidence(@PathVariable long id, @RequestParam MultipartFile file) throws Exception {
    handler(id);
    String name = Objects.toString(file.getOriginalFilename(), "evidence");
    if (!name.toLowerCase().matches(".*\\.(pdf|png|jpg|jpeg|txt|xlsx|xml)$"))
      throw Api.fail(422, "证据支持 PDF、图片、文本和账单文件");
    String uri =
        files.write(
            "evidence", name.substring(name.lastIndexOf('.')).toLowerCase(), file.getBytes());
    String fileId = uri.substring(uri.lastIndexOf('/') + 1);
    files.jsonWrite(uri + ".meta.json", Map.of("caseId", id, "name", name, "uri", uri));
    db.exec(
        "UPDATE exception_task SET evidence_file_uri=#{p.uri} WHERE id=#{p.id}",
        Api.params("id", id, "uri", uri));
    return Api.ok(Map.of("fileId", fileId));
  }

  @PostMapping("/{id}/submit")
  Object submit(@PathVariable long id) {
    var c = handler(id);
    if (Api.text(c, "analysisText").isBlank() || Api.text(c, "resolutionText").isBlank())
      throw Api.fail(422, "先保存问题分析和处理结论");
    if (db.exec(
            "UPDATE exception_task SET"
                + " status='PENDING_REVIEW',submitted_by=#{p.actor},updated_at=SYSUTCDATETIME()"
                + " WHERE id=#{p.id} AND status='PROCESSING'",
            Api.params("id", id, "actor", access.actor().id()))
        == 0) throw Api.fail(409, "工单尚未进入处理中");
    return Api.ok(Map.of("message", "已提交独立复核"));
  }

  @PostMapping("/{id}/review")
  Object review(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var c = access.caseRow(id);
    access.caseWrite(c, "OWNER", "REVIEWER");
    db.exec(
        "EXEC usp_review_exception"
            + " @case_id=#{p.id},@reviewer=#{p.actor},@approve=#{p.approve},@resolution_code=#{p.code},@reason=#{p.reason},@evidence_result=#{p.result}",
        Api.params(
            "id",
            id,
            "actor",
            access.actor().id(),
            "approve",
            Boolean.TRUE.equals(b.get("approve")),
            "code",
            Api.required(b, "resolutionCode"),
            "reason",
            Api.required(b, "reason"),
            "result",
            Api.text(b, "evidenceResultId").isBlank() ? null : Api.number(b, "evidenceResultId")));
    return Api.ok(Map.of("message", "复核完成，历史对账分类保持原记录"));
  }
}
