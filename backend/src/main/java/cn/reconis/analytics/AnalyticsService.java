package cn.reconis.analytics;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
  public static final int EXPORT_LIMIT = 100000;
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
    LocalDate start, end;
    Instant asOf;
    try {
      LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
      start = LocalDate.parse(Objects.toString(input.get("start"), today.minusDays(29).toString()));
      end = LocalDate.parse(Objects.toString(input.get("end"), today.toString()));
      asOf =
          Objects.toString(input.get("asOf"), "").isBlank()
              ? Instant.now().truncatedTo(ChronoUnit.MILLIS)
              : Instant.parse(input.get("asOf"));
    } catch (java.time.DateTimeException e) {
      throw Api.fail(422, "请选择有效的起止日期和数据截止时间");
    }
    if (end.isBefore(start)) throw Api.fail(422, "结束日期不能早于开始日期");
    if (asOf.isAfter(Instant.now().plusSeconds(5))) throw Api.fail(422, "数据截止时间不能在未来");
    p.put(
        "start",
        start
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime());
    p.put(
        "end",
        end.plusDays(1)
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime());
    p.put("asOf", LocalDateTime.ofInstant(asOf, ZoneOffset.UTC));
    String where =
        access.scope("f.merchant_id", p)
            + " AND f.channel_occurred_at>=#{p.start} AND f.channel_occurred_at<#{p.end} AND"
            + " f.recorded_at<=#{p.asOf}";
    for (String k : List.of("merchantId", "storeId", "minCent", "maxCent")) {
      if (!Objects.toString(input.get(k), "").isBlank()) {
        long value = Api.number(input, k);
        if (value < 0 || (k.endsWith("Id") && value == 0))
          throw Api.fail(422, "筛选编号必须为正整数，金额不能为负数");
        p.put(k, value);
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
    if (p.containsKey("minCent")
        && p.containsKey("maxCent")
        && (Long) p.get("minCent") > (Long) p.get("maxCent")) throw Api.fail(422, "最小金额不能大于最大金额");
    if (p.containsKey("merchantId")) access.readMerchant((Long) p.get("merchantId"));
    if (p.containsKey("storeId")) {
      var store =
          db.need(
              "SELECT s.id,s.merchant_id FROM store s WHERE s.id=#{p.storeId} AND "
                  + access.scope("s.merchant_id", p),
              p);
      if (p.containsKey("merchantId") && Db.id(store, "merchantId") != (Long) p.get("merchantId"))
        throw Api.fail(422, "所选门店不属于该商户");
      access.readMerchant(Db.id(store, "merchantId"));
    }
    return new Filter(p, where);
  }

  Map<String, String> appliedFilters(Map<String, String> input, Filter f) {
    var result = new LinkedHashMap<>(input);
    result.put(
        "start",
        ((LocalDateTime) f.params().get("start"))
            .atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .toString());
    result.put(
        "end",
        ((LocalDateTime) f.params().get("end"))
            .atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .minusDays(1)
            .toString());
    result.put(
        "asOf", ((LocalDateTime) f.params().get("asOf")).toInstant(ZoneOffset.UTC).toString());
    return result;
  }

  public Map<String, Object> transactions(Map<String, String> input) {
    var f = filter(input);
    String dimension = input.getOrDefault("dimension", "merchant");
    if (!Set.of("merchant", "store", "year", "month", "day").contains(dimension))
      throw Api.fail(422, "不支持的统计维度");
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
          case "store" -> "CONCAT(f.merchant_name,N' / ',f.store_name,N' [',f.store_id,N']')";
          case "year", "month", "day" -> expr;
          default -> "CONCAT(f.merchant_name,N' [',f.merchant_code,N']')";
        };
    String sums =
        "COUNT_BIG(*) event_count,COALESCE(SUM(CASE WHEN entry_type='RECEIPT' THEN amount_cent ELSE"
            + " 0 END),0) receipt_cent,COALESCE(SUM(CASE WHEN entry_type='REFUND' THEN amount_cent"
            + " ELSE 0 END),0) refund_cent,COALESCE(SUM(CASE WHEN entry_type='RECEIPT' THEN"
            + " amount_cent ELSE -amount_cent END),0) net_cent";
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
    var applied = appliedFilters(input, f);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("summary", summary);
    result.put("groups", groups);
    result.put("trend", trend);
    result.put("dimension", dimension);
    result.put("filters", applied);
    result.put("asOf", applied.get("asOf"));
    result.put("scopeDescription", scopeDescription(f));
    result.put("definition", "按渠道实际发生日期统计截止时间前已入账的事实；净收款=收款-退款，包含额外真实收款");
    return result;
  }

  private String scopeDescription(Filter f) {
    String merchant = "全部可访问商户", store = "全部门店";
    if (f.params().containsKey("merchantId")) {
      var row =
          db.need("SELECT name,merchant_code FROM merchant WHERE id=#{p.merchantId}", f.params());
      merchant = row.get("name") + " [" + row.get("merchantCode") + "]";
    }
    if (f.params().containsKey("storeId")) {
      var row =
          db.need(
              "SELECT s.name,s.store_code,m.name merchant_name FROM store s JOIN merchant m ON"
                  + " m.id=s.merchant_id WHERE s.id=#{p.storeId}",
              f.params());
      store = row.get("merchantName") + " / " + row.get("name") + " [" + row.get("storeCode") + "]";
    }
    String min =
        f.params().containsKey("minCent")
            ? new java.math.BigDecimal(f.params().get("minCent").toString())
                .movePointLeft(2)
                .toPlainString()
            : "0";
    String max =
        f.params().containsKey("maxCent")
            ? new java.math.BigDecimal(f.params().get("maxCent").toString())
                .movePointLeft(2)
                .toPlainString()
            : "不限";
    return "商户：" + merchant + "；门店：" + store + "；单笔金额（元）：" + min + " 至 " + max;
  }

  public Map<String, Object> details(Map<String, String> input) {
    var f = filter(input);
    int page = Integer.parseInt(input.getOrDefault("page", "1")),
        size = Integer.parseInt(input.getOrDefault("pageSize", "20"));
    if (page < 1 || page > 10000000 || size < 1 || size > 100) throw Api.fail(422, "页码或每页条数不合法");
    var result =
        new LinkedHashMap<>(
            db.page(
                "FROM v_transaction_fact f",
                f.where(),
                "f.channel_occurred_at DESC,f.id DESC",
                f.params(),
                page,
                size));
    result.put("filters", appliedFilters(input, f));
    return result;
  }

  public List<Map<String, Object>> exportDetails(Map<String, String> input) {
    var f = filter(input);
    long total =
        Db.id(
            db.need(
                "SELECT COUNT_BIG(*) total FROM v_transaction_fact f WHERE " + f.where(),
                f.params()),
            "total");
    if (total > EXPORT_LIMIT)
      throw Api.fail(
          422, "当前范围共有 " + total + " 条流水，超过单次导出上限 " + EXPORT_LIMIT + " 条，请按日期或门店分段导出；未生成截断文件");
    // A bounded query also protects memory if a concurrent transaction becomes visible after COUNT.
    var rows =
        db.list(
            "SELECT TOP "
                + (EXPORT_LIMIT + 1)
                + " f.* FROM v_transaction_fact f WHERE "
                + f.where()
                + " ORDER BY f.channel_occurred_at DESC,f.id DESC",
            f.params());
    if (rows.size() > EXPORT_LIMIT) throw Api.fail(422, "流水数量已超过导出上限，请缩小范围后重试");
    return rows;
  }

  /**
   * Shared source selection for every reconciliation panel. Bill periods and single-event queries
   * stay separate.
   */
  String reconciliationCte(Map<String, String> input, Filter f) {
    var p = f.params();
    String source = input.getOrDefault("reconciliationSource", "BILL");
    if (!Set.of("BILL", "TRADE_QUERY").contains(source)) throw Api.fail(422, "对账统计来源不合法");
    String where = access.scope("b.merchant_id", p);
    if (p.containsKey("merchantId")) where += " AND b.merchant_id=#{p.merchantId}";
    String sourceWhere =
        source.equals("TRADE_QUERY")
            ? "b.source_type='TRADE_QUERY'"
            : "b.source_type<>'TRADE_QUERY'";
    String batchRank =
        source.equals("TRADE_QUERY")
            ? "1"
            : "ROW_NUMBER() OVER(PARTITION BY"
                + " b.scope_type,b.merchant_id,b.period_start,b.period_end ORDER BY CASE"
                + " b.completeness WHEN 'PROVIDER_COMPLETE' THEN 0 WHEN 'DECLARED_COMPLETE' THEN"
                + " 1 ELSE 2 END,b.finished_at DESC,b.id DESC)";
    return "WITH versions AS(SELECT b.*,"
        + batchRank
        + " period_version FROM reconciliation_batch b WHERE b.status='COMPLETED' AND"
        + " b.finished_at<=#{p.asOf} AND "
        + sourceWhere
        + " AND "
        + where
        + " AND b.period_start<#{p.end} AND b.period_end>#{p.start}), observations AS(SELECT"
        + " r.*,b.merchant_id,b.scope_type,b.finished_at,DENSE_RANK() OVER(PARTITION BY"
        + " b.scope_type,b.merchant_id,r.source_event_key ORDER BY b.finished_at DESC,b.id DESC)"
        + " event_version FROM reconciliation_result r JOIN versions b ON b.id=r.batch_id AND"
        + " b.period_version=1), effective AS(SELECT r.*,COALESCE(f.store_id,o.store_id)"
        + " store_id,COALESCE(f.store_name,st.name,N'渠道待定位') store_name,m.name"
        + " merchant_name,COALESCE(f.order_id,o.id) order_id,f.amount_cent local_cent,s.amount_cent channel_cent,COALESCE(f.amount_cent,s.amount_cent,0)"
        + " amount_cent,COALESCE(f.channel_occurred_at,s.channel_occurred_at) event_at FROM"
        + " observations r LEFT JOIN v_transaction_fact f ON f.id=r.entry_id AND f.recorded_at<=#{p.asOf} LEFT JOIN"
        + " statement_line s ON s.id=r.statement_line_id LEFT JOIN refund_request rr ON"
        + " rr.out_request_no=s.out_request_no AND s.event_type='REFUND' AND rr.created_at<=#{p.asOf} LEFT JOIN payment_attempt"
        + " pay ON (pay.id=rr.payment_id OR (s.event_type='RECEIPT' AND"
        + " pay.out_trade_no=s.out_trade_no)) AND pay.created_at<=#{p.asOf} LEFT JOIN v_order o ON o.id=pay.order_id AND"
        + " o.merchant_id=r.merchant_id LEFT JOIN store st ON st.id=o.store_id LEFT JOIN merchant m"
        + " ON m.id=r.merchant_id WHERE r.event_version=1), selected AS(SELECT * FROM effective"
        + " WHERE event_at>=#{p.start} AND event_at<#{p.end}"
        + optionalResultFilters(p)
        + ") ";
  }

  private String optionalResultFilters(Map<String, Object> p) {
    String where = "";
    if (p.containsKey("storeId")) where += " AND store_id=#{p.storeId}";
    if (p.containsKey("minCent")) where += " AND amount_cent>=#{p.minCent}";
    if (p.containsKey("maxCent")) where += " AND amount_cent<=#{p.maxCent}";
    return where;
  }

  public Object reconciliation(Map<String, String> input) {
    var f = filter(input);
    String cte = reconciliationCte(input, f);
    var p = f.params();
    return Map.of(
        "distribution",
            db.list(
                cte
                    + "SELECT classification,COUNT_BIG(*) count,SUM(amount_cent) amount_cent FROM"
                    + " selected GROUP BY classification",
                p),
        "associations",
            db.list(
                cte
                    + "SELECT merchant_id,merchant_name,store_id,CONCAT(store_name,CASE WHEN"
                    + " store_id IS NULL THEN N'' ELSE CONCAT(N' [',store_id,N']') END)"
                    + " store_name,reason_code,COUNT_BIG(*) count,SUM(amount_cent) amount_cent FROM"
                    + " selected WHERE classification<>'MATCHED' GROUP BY"
                    + " merchant_id,merchant_name,store_id,store_name,reason_code ORDER BY count"
                    + " DESC,merchant_id,store_id,reason_code",
                p),
        "batches",
            db.list(
                cte
                    + "SELECT"
                    + " b.id,b.batch_no,b.source_type,b.completeness,b.period_start,b.period_end,b.finished_at,m.name"
                    + " merchant_name,COUNT_BIG(*) result_count FROM selected r JOIN"
                    + " reconciliation_batch b ON b.id=r.batch_id LEFT JOIN merchant m ON"
                    + " m.id=b.merchant_id GROUP BY"
                    + " b.id,b.batch_no,b.source_type,b.completeness,b.period_start,b.period_end,b.finished_at,m.name"
                    + " ORDER BY b.finished_at DESC,b.id DESC",
                p),
        "definition",
            "按交易实际日期、门店及单笔金额筛选；同账期有效账单优先渠道确认完整，其次声明完整，再次完整性未知；同等级取最新完成版本，重叠账期按事件取最新观察并保留该观察中的重复行；单笔核查另行统计；无法定位门店的渠道记录仅列入全部门店范围");
  }

  String caseSnapshotsCte(Filter f, String source) {
    if (!Set.of("BILL", "TRADE_QUERY").contains(source)) throw Api.fail(422, "对账统计来源不合法");
    var p = f.params();
    String where =
        access.scope("c.merchant_id", p)
            + " AND c.created_at<=#{p.asOf} AND"
            + " COALESCE(f.channel_occurred_at,s.channel_occurred_at)>=#{p.start} AND"
            + " COALESCE(f.channel_occurred_at,s.channel_occurred_at)<#{p.end} AND EXISTS(SELECT 1 FROM reconciliation_batch ob"
            + " WHERE ob.id=origin.batch_id AND "
            + (source.equals("TRADE_QUERY") ? "ob.source_type='TRADE_QUERY'" : "ob.source_type<>'TRADE_QUERY'")
            + ")";
    if (p.containsKey("merchantId")) where += " AND c.merchant_id=#{p.merchantId}";
    if (p.containsKey("storeId")) where += " AND COALESCE(f.store_id,o.store_id)=#{p.storeId}";
    if (p.containsKey("minCent"))
      where += " AND COALESCE(f.amount_cent,s.amount_cent,0)>=#{p.minCent}";
    if (p.containsKey("maxCent"))
      where += " AND COALESCE(f.amount_cent,s.amount_cent,0)<=#{p.maxCent}";
    return
        "WITH case_source AS(SELECT c.*,origin.classification origin_classification,origin.batch_id origin_batch_id,"
            + " COALESCE(f.store_id,o.store_id) store_id,COALESCE(f.store_name,st.name,N'渠道待定位') store_name,"
            + " COALESCE(f.order_id,o.id) order_id,COALESCE(f.amount_cent,s.amount_cent,0) amount_cent,"
            + " COALESCE(f.channel_occurred_at,s.channel_occurred_at) event_at,h.after_json"
            + " FROM v_case c JOIN reconciliation_result origin ON origin.id=c.origin_result_id"
            + " LEFT JOIN v_transaction_fact f ON f.id=origin.entry_id AND f.recorded_at<=#{p.asOf} LEFT JOIN statement_line s"
            + " ON s.id=origin.statement_line_id LEFT JOIN refund_request rr ON"
            + " rr.out_request_no=s.out_request_no AND s.event_type='REFUND' AND rr.created_at<=#{p.asOf} LEFT JOIN"
            + " payment_attempt pay ON (pay.id=rr.payment_id OR (s.event_type='RECEIPT' AND"
            + " pay.out_trade_no=s.out_trade_no)) AND pay.created_at<=#{p.asOf} LEFT JOIN v_order o ON o.id=pay.order_id AND"
            + " o.merchant_id=c.merchant_id LEFT JOIN store st ON st.id=o.store_id OUTER APPLY(SELECT TOP 1 a.after_json FROM audit_log a"
            + " WHERE a.object_type='CASE' AND a.object_id=c.id AND a.event_category='DB_CHANGE'"
            + " AND a.occurred_at<=#{p.asOf} AND ISJSON(a.after_json)=1 ORDER BY a.occurred_at"
            + " DESC,a.id DESC) h WHERE "
            + where
            + "), snapshots AS(SELECT *,COALESCE(JSON_VALUE(after_json,'$.status'),CASE WHEN"
            + " updated_at<=#{p.asOf} THEN status ELSE 'HISTORY_UNAVAILABLE' END)"
            + " snapshot_status,CASE WHEN EXISTS(SELECT 1 FROM OPENJSON(COALESCE(after_json,N'{}'))"
            + " j WHERE j.[key]='closed_at') THEN"
            + " TRY_CONVERT(DATETIME2(3),JSON_VALUE(after_json,'$.closed_at'),126) WHEN"
            + " updated_at<=#{p.asOf} THEN closed_at END snapshot_closed_at,CASE WHEN EXISTS(SELECT"
            + " 1 FROM OPENJSON(COALESCE(after_json,N'{}')) j WHERE j.[key]='due_at') THEN"
            + " TRY_CONVERT(DATETIME2(3),JSON_VALUE(after_json,'$.due_at'),126) WHEN"
            + " updated_at<=#{p.asOf} THEN due_at END snapshot_due_at FROM case_source) ";
  }

  public Object cases(Map<String, String> input) {
    var f = filter(input);
    var p = f.params();
    String cte = caseSnapshotsCte(f, input.getOrDefault("reconciliationSource", "BILL"));
    return db.list(
        cte
            + "SELECT snapshot_status status,origin_classification classification,COUNT_BIG(*)"
            + " count,AVG(CASE WHEN snapshot_status='CLOSED' AND snapshot_closed_at<=#{p.asOf} THEN"
            + " CONVERT(DECIMAL(18,2),DATEDIFF(MINUTE,created_at,snapshot_closed_at)) END)"
            + " average_minutes,SUM(CASE WHEN snapshot_due_at IS NOT NULL THEN"
            + " dbo.fn_case_overdue_days(snapshot_due_at,CASE WHEN snapshot_status='CLOSED' THEN"
            + " snapshot_closed_at END,#{p.asOf}) END) overdue_days,SUM(CASE WHEN"
            + " snapshot_status='HISTORY_UNAVAILABLE' OR snapshot_due_at IS NULL OR"
            + " (snapshot_status='CLOSED' AND snapshot_closed_at IS NULL) THEN 1 ELSE 0 END)"
            + " incomplete_history_count FROM snapshots GROUP BY"
            + " snapshot_status,origin_classification",
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
    var actor = access.actor();
    if (actor.role().equals("MEMBER")
        && db.one(
                "SELECT TOP 1 id FROM merchant_member WHERE user_id=#{p.actor} AND status='ACTIVE'",
                Api.params("actor", actor.id()))
            == null) {
      var p = Api.params("actor", actor.id());
      return Map.of(
          "consumer", true,
          "summary",
              db.need(
                  "SELECT COUNT_BIG(*) order_count,COALESCE(SUM(CASE WHEN status='WAITING_PAYMENT'"
                      + " THEN 1 ELSE 0 END),0) waiting_count,COALESCE(SUM(CASE WHEN status='PAID'"
                      + " THEN 1 ELSE 0 END),0) paid_count FROM business_order WHERE"
                      + " consumer_id=#{p.actor}",
                  p),
          "pendingRefunds",
              db.need(
                      "SELECT COUNT_BIG(*) count FROM refund_request r JOIN payment_attempt p ON"
                          + " p.id=r.payment_id JOIN business_order o ON o.id=p.order_id WHERE"
                          + " o.consumer_id=#{p.actor} AND r.status IN"
                          + " ('PENDING_REVIEW','APPROVED','PROCESSING','UNKNOWN')",
                      p)
                  .get("count"),
          "recentOrders",
              db.list(
                  "SELECT TOP 8"
                      + " id,order_no,subject,amount_cent,status,merchant_name,store_name,created_at"
                      + " FROM v_order WHERE consumer_id=#{p.actor} ORDER BY created_at DESC,id"
                      + " DESC",
                  p));
    }
    var tx = transactions(input);
    var p = new HashMap<String, Object>();
    String merchantScope = access.scope("o.merchant_id", p);
    String caseScope = access.scope("c.merchant_id", p);
    if (!Objects.toString(input.get("merchantId"), "").isBlank()) {
      p.put("merchantId", Api.number(input, "merchantId"));
      merchantScope += " AND o.merchant_id=#{p.merchantId}";
      caseScope += " AND c.merchant_id=#{p.merchantId}";
    }
    tx.put(
        "pendingPayments",
        db.need(
                "SELECT COUNT_BIG(*) count FROM payment_attempt p JOIN v_order o ON o.id=p.order_id"
                    + " WHERE "
                    + merchantScope
                    + " AND p.status IN ('CREATED','PENDING','UNKNOWN')",
                p)
            .get("count"));
    tx.put(
        "pendingRefunds",
        db.need(
                "SELECT COUNT_BIG(*) count FROM refund_request r JOIN payment_attempt p ON"
                    + " p.id=r.payment_id JOIN v_order o ON o.id=p.order_id WHERE "
                    + merchantScope
                    + " AND r.status='PENDING_REVIEW'",
                p)
            .get("count"));
    tx.put(
        "pendingReviews",
        db.need(
                "SELECT COUNT_BIG(*) count FROM v_case c WHERE "
                    + caseScope
                    + " AND status='PENDING_REVIEW'",
                p)
            .get("count"));
    tx.put(
        "overdueCases",
        db.need(
                "SELECT COUNT_BIG(*) count FROM v_case c WHERE "
                    + caseScope
                    + " AND status<>'CLOSED' AND due_at<SYSUTCDATETIME()",
                p)
            .get("count"));
    tx.put(
        "openCases",
        db.need(
                "SELECT COUNT_BIG(*) count FROM v_case c WHERE "
                    + caseScope
                    + " AND status<>'CLOSED'",
                p)
            .get("count"));
    tx.put(
        "recentBatches",
        db.list(
            "SELECT TOP 5 b.*,m.name merchant_name FROM reconciliation_batch b LEFT JOIN merchant m"
                + " ON m.id=b.merchant_id WHERE "
                + access.scope("b.merchant_id", p)
                + (p.containsKey("merchantId") ? " AND b.merchant_id=#{p.merchantId}" : "")
                + " AND b.source_type<>'AUTO_JOB' ORDER BY b.id DESC",
            p));
    tx.put(
        "recentCases",
        db.list(
            "SELECT TOP 5 * FROM v_case c WHERE "
                + caseScope
                + " AND status<>'CLOSED' ORDER BY CASE WHEN due_at<SYSUTCDATETIME() THEN 0 ELSE 1"
                + " END, due_at, c.id DESC",
            p));
    return tx;
  }
}
