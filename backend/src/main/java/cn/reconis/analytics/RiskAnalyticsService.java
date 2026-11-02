package cn.reconis.analytics;

import cn.reconis.common.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** Risk metrics keep reconciliation observations and workflow events in separate fact sets. */
@Service
public class RiskAnalyticsService {
  private final Db db;
  private final AnalyticsService analytics;

  public RiskAnalyticsService(Db db, AnalyticsService analytics) {
    this.db = db;
    this.analytics = analytics;
  }

  private String eventsCte(Map<String, String> input, AnalyticsService.Filter filter) {
    return analytics.reconciliationCte(input, filter)
        + " , risk_events AS (SELECT scope_type,merchant_id,source_event_key,MAX(merchant_name) merchant_name,"
        + " MAX(store_id) store_id,MAX(store_name) store_name,MAX(order_id) order_id,MIN(id) result_id,"
        + " MIN(batch_id) batch_id,MIN(event_at) event_at,MAX(amount_cent) amount_cent,"
        + " MAX(local_cent) local_cent,MAX(channel_cent) channel_cent,COUNT_BIG(*) observation_count,"
        + " MAX(CASE WHEN classification<>'MATCHED' THEN 1 ELSE 0 END) is_exception,"
        + " STRING_AGG(CONVERT(VARCHAR(MAX),classification),',') classifications,"
        + " CASE WHEN COUNT(DISTINCT entry_id)<=1 AND COUNT(DISTINCT statement_line_id)<=1"
        + " AND MAX(CASE WHEN classification IN ('BILL_DUPLICATE','KEY_UNRESOLVED','PENDING_CONFIRMATION','EXTRA_SUCCESS') THEN 1 ELSE 0 END)=0"
        + " THEN ABS(CONVERT(DECIMAL(38,0),COALESCE(MAX(local_cent),0))-COALESCE(MAX(channel_cent),0)) END difference_cent"
        + " FROM selected GROUP BY scope_type,merchant_id,source_event_key) ";
  }

  private String casesCte(Map<String, String> input, AnalyticsService.Filter filter) {
    return analytics.caseSnapshotsCte(filter, input.getOrDefault("reconciliationSource", "BILL"))
        + " , risk_cases AS(SELECT *,CASE WHEN snapshot_status NOT IN ('CLOSED','HISTORY_UNAVAILABLE') THEN 1 ELSE 0 END is_open,"
        + " CASE WHEN snapshot_status NOT IN ('CLOSED','HISTORY_UNAVAILABLE') AND snapshot_due_at<#{p.asOf} THEN 1 ELSE 0 END is_overdue,"
        + " CASE WHEN snapshot_status='CLOSED' AND snapshot_closed_at>=created_at AND snapshot_closed_at<=#{p.asOf} THEN 1 ELSE 0 END valid_closed,"
        + " CASE WHEN snapshot_status='CLOSED' AND snapshot_closed_at>=created_at AND snapshot_closed_at<=#{p.asOf} AND snapshot_due_at IS NOT NULL THEN 1 ELSE 0 END sla_eligible,"
        + " CASE WHEN snapshot_status='CLOSED' AND snapshot_closed_at>=created_at AND snapshot_closed_at<=#{p.asOf} AND snapshot_closed_at<=snapshot_due_at THEN 1 ELSE 0 END is_on_time,"
        + " CASE WHEN snapshot_status='HISTORY_UNAVAILABLE' OR snapshot_due_at IS NULL OR (snapshot_status='CLOSED'"
        + " AND (snapshot_closed_at IS NULL OR snapshot_closed_at<created_at OR snapshot_closed_at>#{p.asOf})) THEN 1 ELSE 0 END incomplete_history"
        + " FROM snapshots), pending_events AS(SELECT merchant_id,source_event_key,MAX(merchant_name) merchant_name,MAX(store_id) store_id,"
        + " MAX(store_name) store_name,MAX(order_id) order_id,MIN(origin_batch_id) batch_id,MAX(amount_cent) amount_cent,MIN(event_at) event_at,"
        + " MIN(created_at) first_detected_at,COUNT_BIG(*) case_count,SUM(is_overdue) overdue_case_count,"
        + " STRING_AGG(CONVERT(VARCHAR(MAX),id),',') case_ids,"
        + " FLOOR(DATEDIFF_BIG(SECOND,MIN(created_at),#{p.asOf})/86400.0) age_days"
        + " FROM risk_cases WHERE is_open=1 GROUP BY merchant_id,source_event_key), aged_events AS(SELECT *,"
        + " CASE WHEN age_days<1 THEN 'UNDER_1' WHEN age_days<3 THEN 'DAYS_1_2' WHEN age_days<7 THEN 'DAYS_3_6' ELSE 'DAYS_7_PLUS' END age_bucket"
        + " FROM pending_events) ";
  }

  private Map<String, Object> eventSummary(String cte, Map<String, Object> p) {
    return db.need(cte + "SELECT COUNT_BIG(*) event_count,COALESCE(SUM(CONVERT(BIGINT,is_exception)),0) exception_event_count,"
        + " CONVERT(VARCHAR(40),COALESCE(SUM(CASE WHEN is_exception=1 THEN difference_cent ELSE 0 END),0)) difference_cent,"
        + " COALESCE(SUM(CASE WHEN is_exception=1 AND difference_cent IS NULL THEN CONVERT(BIGINT,1) ELSE 0 END),0) uncertain_difference_count FROM risk_events", p);
  }

  public Map<String, Object> overview(Map<String, String> input) {
    var filter = analytics.filter(input);
    var p = filter.params();
    String events = eventsCte(input, filter), cases = casesCte(input, filter);
    var summary = eventSummary(events, p);
    summary.putAll(db.need(cases + "SELECT COUNT_BIG(*) case_count,COALESCE(SUM(CONVERT(BIGINT,is_open)),0) open_case_count,"
        + " COALESCE(SUM(CONVERT(BIGINT,is_overdue)),0) overdue_case_count,COALESCE(SUM(CONVERT(BIGINT,valid_closed)),0) closed_case_count,"
        + " COALESCE(SUM(CONVERT(BIGINT,sla_eligible)),0) sla_eligible_count,COALESCE(SUM(CONVERT(BIGINT,is_on_time)),0) on_time_count,"
        + " COALESCE(SUM(CONVERT(BIGINT,incomplete_history)),0) incomplete_history_count,"
        + " (SELECT COUNT_BIG(*) FROM pending_events) pending_event_count,"
        + " (SELECT CONVERT(VARCHAR(40),COALESCE(SUM(CONVERT(DECIMAL(38,0),amount_cent)),0)) FROM pending_events) pending_cent"
        + " FROM risk_cases", p));
    // MyBatis represents an all-NULL result row as null. A real sample count preserves the
    // aggregate row while the two percentiles correctly remain unknown for an empty cohort.
    var durations = db.need(cases + "SELECT COUNT_BIG(*) duration_sample_count,MAX(median_minutes) median_minutes,MAX(p95_minutes) p95_minutes FROM (SELECT"
        + " PERCENTILE_CONT(0.5) WITHIN GROUP(ORDER BY DATEDIFF_BIG(SECOND,created_at,snapshot_closed_at)/60.0) OVER() median_minutes,"
        + " PERCENTILE_CONT(0.95) WITHIN GROUP(ORDER BY DATEDIFF_BIG(SECOND,created_at,snapshot_closed_at)/60.0) OVER() p95_minutes"
        + " FROM risk_cases WHERE valid_closed=1) durations", p);
    durations.putIfAbsent("medianMinutes", null);
    durations.putIfAbsent("p95Minutes", null);
    summary.putAll(durations);
    var applied = analytics.appliedFilters(input, filter);
    var previousInput = previousPeriod(applied);
    var previousFilter = analytics.filter(previousInput);
    Map<String, Object> previous = eventSummary(eventsCte(previousInput, previousFilter), previousFilter.params());
    previous.put("start", previousInput.get("start"));
    previous.put("end", previousInput.get("end"));
    var result = new LinkedHashMap<String, Object>();
    result.put("summary", summary);
    result.put("previous", previous);
    result.put("filters", applied);
    result.put("aging", db.list(cases + "SELECT age_bucket,COUNT_BIG(*) event_count,SUM(case_count) case_count,"
        + " CONVERT(VARCHAR(40),SUM(CONVERT(DECIMAL(38,0),amount_cent))) pending_cent FROM aged_events GROUP BY age_bucket ORDER BY MIN(age_days)", p));
    result.put("stores", db.list(events + "SELECT TOP 20 merchant_id,merchant_name,store_id,store_name,COUNT_BIG(*) event_count,"
        + " SUM(CONVERT(BIGINT,is_exception)) exception_event_count,COUNT(DISTINCT CASE WHEN is_exception=1 THEN CONVERT(DATE,DATEADD(HOUR,8,event_at)) END) exception_days,"
        + " CONVERT(VARCHAR(40),COALESCE(SUM(CASE WHEN is_exception=1 THEN difference_cent ELSE 0 END),0)) difference_cent,"
        + " SUM(CASE WHEN is_exception=1 AND difference_cent IS NULL THEN 1 ELSE 0 END) uncertain_difference_count"
        + " FROM risk_events GROUP BY merchant_id,merchant_name,store_id,store_name HAVING SUM(is_exception)>0"
        + " ORDER BY exception_event_count DESC,exception_days DESC,merchant_id,store_id", p));
    result.put("trend", db.list(events + "SELECT CONVERT(VARCHAR(10),DATEADD(HOUR,8,event_at),120) day,COUNT_BIG(*) event_count,"
        + " SUM(CONVERT(BIGINT,is_exception)) exception_event_count,CONVERT(VARCHAR(40),COALESCE(SUM(CASE WHEN is_exception=1 THEN difference_cent ELSE 0 END),0)) difference_cent"
        + " FROM risk_events GROUP BY CONVERT(VARCHAR(10),DATEADD(HOUR,8,event_at),120) ORDER BY day", p));
    result.put("definitions", List.of(
        "差错率=存在非匹配结果的资金事件数/已对账资金事件数。同商户同事件只计一次；未对账交易不进入分母。",
        "待核实金额：按未关闭工单关联的资金事件去重，同事件取最大起因金额。账龄从最早未关闭工单创建时刻起，按完整24小时计算。",
        "可计算差额为有效观察中单边或一对一记录的本地与渠道金额绝对差之和。重复、标识不明、待确认和额外收款列为差额待核实，不按零差额解释。",
        "逾期工单指截止时未关闭且已过期限的工单。按时关闭率只统计关闭时间有效且有期限的已关闭工单；中位数与P95只统计关闭时间有效的工单。历史不完整单独提示。",
        "所有面板按起因交易日、对账来源、当前权限及同一数据截止时间筛选；前期比较采用紧邻且等天数的交易期间，并使用相同截止时间及权限。"));
    return result;
  }

  static Map<String, String> previousPeriod(Map<String, String> applied) {
    var previous = new LinkedHashMap<>(applied);
    LocalDate start = LocalDate.parse(applied.get("start")), end = LocalDate.parse(applied.get("end"));
    long days = ChronoUnit.DAYS.between(start, end) + 1;
    previous.put("start", start.minusDays(days).toString());
    previous.put("end", start.minusDays(1).toString());
    return previous;
  }

  public Map<String, Object> details(Map<String, String> input) {
    var filter = analytics.filter(input);
    var p = filter.params();
    String kind = input.getOrDefault("kind", "exceptions"), cte, from, where = "1=1", sort;
    if (Set.of("events", "exceptions").contains(kind)) {
      cte = eventsCte(input, filter); from = "risk_events"; sort = "event_at DESC,merchant_id,source_event_key";
      if (kind.equals("exceptions")) where = "is_exception=1";
    } else if (kind.equals("pending")) {
      cte = casesCte(input, filter); from = "aged_events"; sort = "first_detected_at,merchant_id,source_event_key";
      String bucket = input.getOrDefault("ageBucket", "");
      if (!bucket.isBlank()) {
        if (!Set.of("UNDER_1", "DAYS_1_2", "DAYS_3_6", "DAYS_7_PLUS").contains(bucket)) throw Api.fail(422, "账龄分组不合法");
        p.put("ageBucket", bucket); where += " AND age_bucket=#{p.ageBucket}";
      }
    } else if (Set.of("overdue", "closed", "onTime", "slaEligible", "incomplete").contains(kind)) {
      cte = casesCte(input, filter); from = "risk_cases"; sort = "created_at DESC,id DESC";
      where = Map.of("overdue", "is_overdue=1", "closed", "valid_closed=1", "onTime", "is_on_time=1", "slaEligible", "sla_eligible=1", "incomplete", "incomplete_history=1").get(kind);
    } else throw Api.fail(422, "不支持的风险明细类型");
    if (input.containsKey("unlocated") && input.get("unlocated").equals("true")) where += " AND store_id IS NULL";
    if (input.getOrDefault("unassignedMerchant", "").equals("true")) where += " AND merchant_id IS NULL";
    String day = input.getOrDefault("day", "");
    if (!day.isBlank()) {
      try { p.put("riskDay", LocalDate.parse(day)); } catch (DateTimeException e) { throw Api.fail(422, "明细日期不合法"); }
      where += " AND CONVERT(DATE,DATEADD(HOUR,8,event_at))=#{p.riskDay}";
    }
    int page = positivePage(input, "page", 1, 10000000), size = positivePage(input, "pageSize", 20, 100);
    p.put("offset", (page - 1) * size); p.put("size", size);
    Object total = db.need(cte + "SELECT COUNT_BIG(*) total FROM " + from + " WHERE " + where, p).get("total");
    String columns = from.equals("risk_cases") ? "id,case_no,source_event_key,merchant_id,merchant_name,store_id,store_name,order_id,origin_batch_id batch_id,"
        + " origin_classification classification,amount_cent,event_at,created_at,snapshot_status status,snapshot_due_at due_at,snapshot_closed_at closed_at" : "*";
    var rows = db.list(cte + "SELECT " + columns + " FROM " + from + " WHERE " + where + " ORDER BY " + sort
        + " OFFSET #{p.offset} ROWS FETCH NEXT #{p.size} ROWS ONLY", p);
    // DECIMAL(38) money avoids aggregate overflow and remains exact over the JSON/JavaScript boundary.
    for (var row : rows) if (row.get("differenceCent") != null) row.put("differenceCent", row.get("differenceCent").toString());
    return Map.of("items", rows, "total", total, "page", page, "pageSize", size, "kind", kind, "filters", analytics.appliedFilters(input, filter));
  }

  private int positivePage(Map<String, String> input, String key, int fallback, int maximum) {
    try {
      int value = Integer.parseInt(input.getOrDefault(key, Integer.toString(fallback)));
      if (value >= 1 && value <= maximum) return value;
    } catch (NumberFormatException ignored) { }
    throw Api.fail(422, "页码或每页条数不合法");
  }
}
