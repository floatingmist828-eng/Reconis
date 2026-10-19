package cn.reconis.analytics;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
  private final Db db;
  private final Access access;

  public AnalyticsService(Db db, Access access) {
    this.db = db;
    this.access = access;
  }

  public record Filter(Map<String, Object> params, String where) {}

  public Filter filter(Map<String, String> input) {
    if (access.actor().role().equals("ADMIN")) throw Api.fail(403, "管理员通过审核工作台管理资料");
    Map<String, Object> p = new HashMap<>();
    String
        start =
            input.getOrDefault(
                "start", LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(29).toString()),
        end = input.getOrDefault("end", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
    p.put(
        "start",
        LocalDate.parse(start)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime());
    p.put(
        "end",
        LocalDate.parse(end)
            .plusDays(1)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime());
    String where =
        access.scope("f.merchant_id", p)
            + " AND f.channel_occurred_at>=#{p.start} AND f.channel_occurred_at<#{p.end}";
    for (String k : List.of("merchantId", "storeId", "minCent", "maxCent")) {
      if (!input.getOrDefault(k, "").isBlank()) {
        p.put(k, Long.parseLong(input.get(k)));
        where +=
            " AND "
                + Map.of(
                        "merchantId",
                        "f.merchant_id=",
                        "storeId",
                        "f.store_id=",
                        "minCent",
                        "f.amount_cent>=",
                        "maxCent",
                        "f.amount_cent<=")
                    .get(k)
                + "#{p."
                + k
                + "}";
      }
    }
    return new Filter(p, where);
  }

  public Map<String, Object> transactions(Map<String, String> input) {
    var f = filter(input);
    String dimension = input.getOrDefault("dimension", "merchant");
    String expr =
        switch (dimension) {
          case "store" -> "CONVERT(VARCHAR(30),f.store_id)";
          case "year" -> "CONVERT(VARCHAR(4),YEAR(f.business_date))";
          case "month" -> "CONVERT(VARCHAR(7),f.business_date,120)";
          case "day" -> "CONVERT(VARCHAR(10),f.business_date,120)";
          default -> "CONVERT(VARCHAR(30),f.merchant_id)";
        };
    String label =
        switch (dimension) {
          case "store" -> "f.store_name";
          case "year", "month", "day" -> expr;
          default -> "f.merchant_name";
        };
    String sums =
        "COUNT_BIG(*) event_count,SUM(CASE WHEN entry_type='RECEIPT' THEN amount_cent ELSE 0 END)"
            + " receipt_cent,SUM(CASE WHEN entry_type='REFUND' THEN amount_cent ELSE 0 END)"
            + " refund_cent,SUM(CASE WHEN entry_type='RECEIPT' THEN amount_cent ELSE -amount_cent"
            + " END) net_cent";
    var summary =
        db.need("SELECT " + sums + " FROM v_transaction_fact f WHERE " + f.where(), f.params());
    var groups =
        db.list(
            "SELECT "
                + expr
                + " group_key,"
                + label
                + " group_name,"
                + sums
                + " FROM v_transaction_fact f WHERE "
                + f.where()
                + " GROUP BY "
                + expr
                + (label.equals(expr) ? "" : "," + label)
                + " ORDER BY group_key",
            f.params());
    var trend =
        db.list(
            "SELECT CONVERT(VARCHAR(10),business_date,120) day,"
                + sums
                + " FROM v_transaction_fact f WHERE "
                + f.where()
                + " GROUP BY business_date ORDER BY business_date",
            f.params());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("summary", summary);
    result.put("groups", groups);
    result.put("trend", trend);
    result.put("dimension", dimension);
    result.put("filters", input);
    result.put("asOf", Instant.now().toString());
    result.put("definition", "按渠道实际发生日期；净收款=收款-退款，包含额外真实收款");
    return result;
  }

  public List<Map<String, Object>> details(Map<String, String> input) {
    var f = filter(input);
    return db.list(
        "SELECT TOP 10000 f.* FROM v_transaction_fact f WHERE "
            + f.where()
            + " ORDER BY channel_occurred_at DESC,id DESC",
        f.params());
  }

  public Object reconciliation(Map<String, String> input) {
    var f = filter(input);
    Map<String, Object> p = f.params();
    String where = access.scope("b.merchant_id", p);
    if (p.containsKey("merchantId")) where += " AND b.merchant_id=#{p.merchantId}";
    String latest =
        "WITH latest AS(SELECT b.*,ROW_NUMBER() OVER(PARTITION BY scope_type,merchant_id ORDER BY"
            + " finished_at DESC,id DESC) n FROM reconciliation_batch b WHERE status='COMPLETED'"
            + " AND "
            + where
            + " AND period_start<#{p.end} AND period_end>#{p.start}) ";
    String from =
        " FROM reconciliation_result r JOIN latest b ON b.id=r.batch_id AND b.n=1 LEFT JOIN"
            + " transaction_entry e ON e.id=r.entry_id LEFT JOIN statement_line s ON"
            + " s.id=r.statement_line_id ";
    return Map.of(
        "distribution",
        db.list(
            latest
                + "SELECT r.classification,COUNT_BIG(*)"
                + " count,SUM(COALESCE(e.amount_cent,s.amount_cent,0)) amount_cent"
                + from
                + "GROUP BY r.classification",
            p),
        "associations",
        db.list(
            latest
                + "SELECT b.merchant_id,m.name merchant_name,COALESCE(f.store_name,N'渠道待定位')"
                + " store_name,r.reason_code,COUNT_BIG(*)"
                + " count,SUM(COALESCE(e.amount_cent,s.amount_cent,0)) amount_cent"
                + from
                + " LEFT JOIN v_transaction_fact f ON f.id=e.id LEFT JOIN merchant m ON"
                + " m.id=b.merchant_id WHERE r.classification<>'MATCHED' GROUP BY"
                + " b.merchant_id,m.name,f.store_name,r.reason_code ORDER BY count DESC",
            p),
        "definition",
        "每个商户范围在所选期间内最近一次完成批次；待确认与已证实差错分别列示");
  }

  public Object cases(Map<String, String> input) {
    var f = filter(input);
    var p = f.params();
    String where =
        access.scope("c.merchant_id", p) + " AND created_at>=#{p.start} AND created_at<#{p.end}";
    if (p.containsKey("merchantId")) where += " AND c.merchant_id=#{p.merchantId}";
    return db.list(
        "SELECT status,classification,COUNT_BIG(*) count,AVG(CASE WHEN status='CLOSED' THEN"
            + " CONVERT(DECIMAL(18,2),DATEDIFF(MINUTE,created_at,closed_at)) END)"
            + " average_minutes,SUM(dbo.fn_case_overdue_days(due_at,closed_at,SYSUTCDATETIME()))"
            + " overdue_days FROM v_case c WHERE "
            + where
            + " GROUP BY status,classification",
        p);
  }

  public Object dashboard(Map<String, String> input) {
    if (access.actor().role().equals("ADMIN"))
      return Map.of(
          "admin",
          true,
          "pendingUsers",
          db.need("SELECT COUNT_BIG(*) count FROM app_user WHERE status='PENDING'", Map.of())
              .get("count"),
          "pendingMerchants",
          db.need(
                  "SELECT COUNT_BIG(*) count FROM merchant WHERE status='PENDING' OR proposed_name"
                      + " IS NOT NULL",
                  Map.of())
              .get("count"));
    var tx = transactions(input);
    var p = new HashMap<String, Object>();
    tx.put(
        "openCases",
        db.need(
                "SELECT COUNT_BIG(*) count FROM v_case c WHERE "
                    + access.scope("c.merchant_id", p)
                    + " AND status<>'CLOSED'",
                p)
            .get("count"));
    tx.put(
        "recentBatches",
        db.list(
            "SELECT TOP 5 b.*,m.name merchant_name FROM reconciliation_batch b LEFT JOIN merchant m"
                + " ON m.id=b.merchant_id WHERE "
                + access.scope("b.merchant_id", p)
                + " ORDER BY b.id DESC",
            p));
    tx.put(
        "recentCases",
        db.list(
            "SELECT TOP 5 * FROM v_case c WHERE "
                + access.scope("c.merchant_id", p)
                + " AND status<>'CLOSED' ORDER BY c.id DESC",
            p));
    return tx;
  }
}
