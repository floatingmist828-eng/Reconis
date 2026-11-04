package cn.reconis.analytics;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {
  private final AnalyticsService analytics;
  private final Db db;
  private final Access access;

  public AnalyticsController(AnalyticsService analytics, Db db, Access access) {
    this.analytics = analytics;
    this.db = db;
    this.access = access;
  }

  @GetMapping("/dashboard")
  Object dashboard(@RequestParam Map<String, String> p) {
    return Api.ok(analytics.dashboard(p));
  }

  @GetMapping("/analytics/transactions")
  Object transactions(@RequestParam Map<String, String> p) {
    return Api.ok(analytics.transactions(p));
  }

  @GetMapping("/analytics/transactions/details")
  Object details(@RequestParam Map<String, String> p) {
    return Api.ok(analytics.details(p));
  }

  @GetMapping("/analytics/reconciliation")
  Object reconciliation(@RequestParam Map<String, String> p) {
    return Api.ok(analytics.reconciliation(p));
  }

  @GetMapping("/analytics/cases")
  Object cases(@RequestParam Map<String, String> p) {
    return Api.ok(analytics.cases(p));
  }

  private String auditWhere(Map<String, Object> p, Map<String, String> f) {
    access.role("ADMIN", "AUDITOR");
    String where = "1=1";
    for (String k : List.of("category", "actorId", "merchantId", "result", "requestId")) {
      if (!f.getOrDefault(k, "").isBlank()) {
        p.put(k, f.get(k));
        where +=
            " AND "
                + Map.of(
                        "category",
                        "event_category",
                        "actorId",
                        "actor_user_id",
                        "merchantId",
                        "scope_merchant_id",
                        "result",
                        "result",
                        "requestId",
                        "request_id")
                    .get(k)
                + "=#{p."
                + k
                + "}";
      }
    }
    if (!f.getOrDefault("q", "").isBlank()) {
      p.put("q", "%" + f.get("q").trim() + "%");
      where += " AND (action LIKE #{p.q} OR summary LIKE #{p.q})";
    }
    if (!f.getOrDefault("start", "").isBlank()) {
      p.put("start", Api.utc(f.get("start")));
      where += " AND occurred_at>=#{p.start}";
    }
    if (!f.getOrDefault("end", "").isBlank()) {
      p.put("end", Api.utc(f.get("end")));
      where += " AND occurred_at<#{p.end}";
    }
    if (p.containsKey("merchantId")) {
      long merchant = Api.number(f, "merchantId");
      db.need("SELECT id FROM merchant WHERE id=#{p.id}", Api.params("id", merchant));
      RequestContext.merchant(merchant);
    }
    if (p.containsKey("start")
        && p.containsKey("end")
        && !((java.time.LocalDateTime) p.get("end"))
            .isAfter((java.time.LocalDateTime) p.get("start"))) throw Api.fail(422, "结束时间必须晚于开始时间");
    return where;
  }

  @GetMapping("/audit/events")
  Object events(@RequestParam Map<String, String> f) {
    var p = new HashMap<String, Object>();
    String where = auditWhere(p, f);
    return Api.ok(
        db.page(
            "FROM (SELECT a.*,u.display_name actor_name,m.name merchant_name FROM audit_log a LEFT"
                + " JOIN app_user u ON u.id=a.actor_user_id LEFT JOIN merchant m ON"
                + " m.id=a.scope_merchant_id) x",
            where,
            "id DESC",
            p,
            Integer.parseInt(f.getOrDefault("page", "1")),
            30));
  }

  @GetMapping("/audit/statistics")
  Object stats(@RequestParam Map<String, String> f) {
    var p = new HashMap<String, Object>();
    var scope = new HashMap<>(f);
    String result = scope.remove("result");
    String allResults = auditWhere(p, scope);
    String where = allResults;
    if (result != null && !result.isBlank()) {
      p.put("result", result);
      where += " AND result=#{p.result}";
    }
    return Api.ok(
        Map.of(
            "categories",
            db.list(
                "SELECT event_category,COUNT_BIG(*) count FROM audit_log WHERE "
                    + where
                    + " GROUP BY event_category",
                p),
            "actors",
            db.list(
                "SELECT TOP 10 a.actor_user_id,CASE WHEN a.actor_user_id IS NULL THEN N'系统/未登录'"
                    + " ELSE CONCAT(u.display_name,N' [',a.actor_user_id,N']') END"
                    + " actor_name,COUNT_BIG(*) count FROM audit_log a LEFT JOIN app_user u ON"
                    + " u.id=a.actor_user_id WHERE "
                    + where
                    + " GROUP BY a.actor_user_id,u.display_name ORDER BY count"
                    + " DESC,a.actor_user_id",
                p),
            "results",
            db.list(
                "SELECT result,COUNT_BIG(*) count FROM audit_log WHERE "
                    + allResults
                    + " GROUP BY result",
                p)));
  }
}
