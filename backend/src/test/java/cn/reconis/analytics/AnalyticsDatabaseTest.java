package cn.reconis.analytics;

import static org.junit.jupiter.api.Assertions.*;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in SQL Server tests: real views, effective-batch selection, grouping and pagination. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class AnalyticsDatabaseTest {
  private Connection admin, connection;
  private String database;
  private AnalyticsService service;
  private RiskAnalyticsService risk;
  private long user, merchant, storeA, storeB, effectiveBill, partialBill, declaredBill;
  private final Map<String, String> week =
      Map.of("start", "2026-10-15", "end", "2026-10-21", "asOf", "2026-10-21T08:00:00Z");

  @BeforeAll
  void setupDatabase() throws Exception {
    var properties = new Properties();
    for (String key : List.of("user", "password")) {
      String value = System.getenv("RECONIS_SQL_TEST_" + key.toUpperCase(Locale.ROOT));
      if (value != null) properties.setProperty(key, value);
    }
    String url = System.getenv("RECONIS_SQL_TEST_URL");
    admin = DriverManager.getConnection(url, properties);
    database = "ReconisAnalyticsTest_" + UUID.randomUUID().toString().replace("-", "");
    try (var statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE [" + database + "]");
    }
    connection = DriverManager.getConnection(url, properties);
    connection.setCatalog(database);
    for (String file :
        List.of(
            "001_schema.sql",
            "002_constraints_indexes.sql",
            "003_views_functions_procedures.sql")) {
      for (String batch :
          Files.readString(Path.of("..", "database", file)).split("(?im)^GO\\s*$")) {
        if (!batch.isBlank())
          try (var statement = connection.createStatement()) {
            boolean hasResult = statement.execute(batch);
            while (hasResult || statement.getUpdateCount() != -1)
              hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
          } catch (SQLException error) {
            throw new SQLException("执行数据库脚本 " + file + " 失败: " + error.getMessage(), error);
          }
      }
    }
    var db =
        new Db(
            new Db.SqlMapper() {
              @Override
              public List<Map<String, Object>> search(String sql, Map<String, Object> params) {
                return query(sql, params);
              }

              @Override
              public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
                var values = new ArrayList<Object>();
                var matcher = Pattern.compile("#\\{p\\.(\\w+)\\}").matcher(sql);
                var prepared = new StringBuilder();
                while (matcher.find()) {
                  values.add(params.get(matcher.group(1)));
                  matcher.appendReplacement(prepared, "?");
                }
                matcher.appendTail(prepared);
                try (var statement = connection.prepareStatement(prepared.toString())) {
                  for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
                  try (var result = statement.executeQuery()) {
                    var rows = new ArrayList<Map<String, Object>>();
                    while (result.next()) {
                      var row = new LinkedHashMap<String, Object>();
                      for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
                        row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
                      rows.add(row);
                    }
                    return rows;
                  }
                } catch (SQLException e) {
                  throw new IllegalStateException(sql, e);
                }
              }

              @Override
              public int execute(String sql, Map<String, Object> params) {
                throw new UnsupportedOperationException();
              }
            });
    service = new AnalyticsService(db, new Access(db));
    risk = new RiskAnalyticsService(db, service);
    user =
        insert(
            "INSERT app_user(username,password_hash,display_name,platform_role,status) OUTPUT"
                + " INSERTED.id VALUES(N'analytics',N'unused',N'财务','PLATFORM_FINANCE','ACTIVE')");
    merchant =
        insert(
            "INSERT merchant(merchant_code,name,category_code,status) OUTPUT INSERTED.id"
                + " VALUES('M1',N'测试食堂',N'餐饮','ACTIVE')");
    storeA =
        insert(
            "INSERT store(merchant_id,store_code,name) OUTPUT INSERTED.id VALUES("
                + merchant
                + ",'S1',N'同名窗口')");
    storeB =
        insert(
            "INSERT store(merchant_id,store_code,name) OUTPUT INSERTED.id VALUES("
                + merchant
                + ",'S2',N'同名窗口')");
    long a = event("A", storeA, 1000, "2026-10-15T04:00:00"),
        b = event("B", storeB, 2000, "2026-10-16T04:00:00"),
        c = event("C", storeA, 3000, "2026-10-16T05:00:00");
    long original =
        batch(
            "ORIGINAL",
            "XLSX",
            "2026-10-14T16:00:00",
            "2026-10-21T16:00:00",
            "2026-10-21T05:00:00",
            "PROVIDER_COMPLETE");
    result(original, a, "A", "AMOUNT_MISMATCH");
    long caseOrigin = result(original, b, "B", "LOCAL_ONLY");
    effectiveBill =
        batch(
            "LATEST_BILL",
            "XML",
            "2026-10-14T16:00:00",
            "2026-10-21T16:00:00",
            "2026-10-21T06:00:00",
            "PROVIDER_COMPLETE");
    result(effectiveBill, a, "A", "MATCHED");
    result(effectiveBill, b, "B", "LOCAL_ONLY");
    long overlapping =
        batch(
            "OVERLAP", "XLSX", "2026-10-15T16:00:00", "2026-10-16T16:00:00", "2026-10-21T06:30:00");
    result(overlapping, c, "C", "LOCAL_ONLY");
    long query =
        batch(
            "ONE_QUERY",
            "TRADE_QUERY",
            "2026-10-14T16:00:00",
            "2026-10-21T16:00:00",
            "2026-10-21T07:00:00");
    result(query, a, "A", "AMOUNT_MISMATCH");
    declaredBill =
        batch(
            "NEWER_DECLARED",
            "XML",
            "2026-10-14T16:00:00",
            "2026-10-21T16:00:00",
            "2026-10-21T07:15:00");
    result(declaredBill, a, "A", "AMOUNT_MISMATCH");
    partialBill =
        batch(
            "NEWER_PARTIAL",
            "XLSX",
            "2026-10-14T16:00:00",
            "2026-10-21T16:00:00",
            "2026-10-21T07:30:00",
            "UNKNOWN");
    result(partialBill, a, "A", "AMOUNT_MISMATCH");
    long laterCaseResult = result(partialBill, b, "B", "AMOUNT_MISMATCH");
    long task =
        insert(
            "INSERT"
                + " exception_task(case_no,case_key,origin_result_id,latest_result_id,status,title,description,due_at,closed_at,created_at,updated_at)"
                + " OUTPUT INSERTED.id VALUES('CASE-B','CASE-B',"
                + caseOrigin
                + ","
                + laterCaseResult
                + ",'CLOSED',N'对账差错',N'测试','2026-10-19T00:00:00','2026-10-21T09:00:00','2026-10-18T08:00:00','2026-10-21T09:00:00')");
    insert(
        "INSERT"
            + " audit_log(event_category,object_type,object_id,action,result,after_json,occurred_at)"
            + " OUTPUT INSERTED.id VALUES('DB_CHANGE','CASE',"
            + task
            + ",'CASE_WORKFLOW','SUCCESS',N'{\"status\":\"PROCESSING\",\"due_at\":\"2026-10-19T00:00:00\",\"closed_at\":null}','2026-10-21T07:00:00')");
    insert(
        "INSERT"
            + " audit_log(event_category,object_type,object_id,action,result,after_json,occurred_at)"
            + " OUTPUT INSERTED.id VALUES('DB_CHANGE','CASE',"
            + task
            + ",'CASE_WORKFLOW','SUCCESS',N'{\"status\":\"CLOSED\",\"due_at\":\"2026-10-19T00:00:00\",\"closed_at\":\"2026-10-21T09:00:00\"}','2026-10-21T09:00:00')");
  }

  @BeforeEach
  void actor() {
    RequestContext.set(
        new RequestContext.Actor(user, "analytics", "财务", "PLATFORM_FINANCE"), "analytics-test");
  }

  @AfterEach
  void clearActor() {
    RequestContext.clear();
  }

  @AfterAll
  void cleanup() throws Exception {
    if (connection != null) connection.close();
    if (admin != null) {
      try (var statement = admin.createStatement()) {
        if (database != null) {
          statement.execute(
              "ALTER DATABASE [" + database + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
          statement.execute("DROP DATABASE [" + database + "]");
        }
      } finally {
        admin.close();
      }
    }
  }

  private long insert(String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private long event(String key, long store, long amount, String at) throws SQLException {
    long order =
        insert(
            "INSERT business_order(order_no,store_id,created_by,client_request_id,subject,status)"
                + " OUTPUT INSERTED.id VALUES('O"
                + key
                + "',"
                + store
                + ","
                + user
                + ",'"
                + UUID.randomUUID()
                + "',N'午餐','PAID')");
    long payment =
        insert(
            "INSERT"
                + " payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status,is_primary)"
                + " OUTPUT INSERTED.id VALUES("
                + order
                + ",'"
                + UUID.randomUUID()
                + "','"
                + key
                + "','DEMO','DEMO_APP','DEMO_SELLER','SUCCEEDED',1)");
    return insert(
        "INSERT"
            + " transaction_entry(entry_no,event_key,entry_type,payment_id,amount_cent,channel_occurred_at,recorded_at)"
            + " OUTPUT INSERTED.id VALUES('E"
            + key
            + "','P:"
            + key
            + "','RECEIPT',"
            + payment
            + ","
            + amount
            + ",'"
            + at
            + "','2026-10-21T04:00:00')");
  }

  private long batch(String no, String type, String start, String end, String finished)
      throws SQLException {
    return batch(no, type, start, end, finished, "DECLARED_COMPLETE");
  }

  private long batch(
      String no, String type, String start, String end, String finished, String completeness)
      throws SQLException {
    return insert(
        "INSERT"
            + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,completeness,status,finished_at)"
            + " OUTPUT INSERTED.id VALUES('"
            + no
            + "','MERCHANT',"
            + merchant
            + ",'"
            + start
            + "','"
            + end
            + "','"
            + type
            + "','"
            + completeness
            + "','COMPLETED','"
            + finished
            + "')");
  }

  private long result(long batch, long entry, String key, String classification)
      throws SQLException {
    return insert(
        "INSERT"
            + " reconciliation_result(batch_id,result_key,source_event_key,entry_id,classification,reason_code)"
            + " OUTPUT INSERTED.id VALUES("
            + batch
            + ",'P:"
            + key
            + "','P:"
            + key
            + "',"
            + entry
            + ",'"
            + classification
            + "','TEST')");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(Object result, String field) {
    return (List<Map<String, Object>>) ((Map<?, ?>) result).get(field);
  }

  @Test
  void singleTradeQueryCannotReplaceBillAndOneDayCannotIncludeOtherDays() {
    var filters = new HashMap<>(week);
    filters.put("end", "2026-10-15");
    var result = service.reconciliation(filters);
    var distribution = list(result, "distribution");
    assertEquals(1, distribution.size());
    assertEquals("MATCHED", distribution.getFirst().get("classification"));
    assertEquals(1L, ((Number) distribution.getFirst().get("count")).longValue());
    assertEquals(effectiveBill, Db.id(list(result, "batches").getFirst(), "id"));
    filters.put("reconciliationSource", "TRADE_QUERY");
    assertEquals(
        "AMOUNT_MISMATCH",
        list(service.reconciliation(filters), "distribution").getFirst().get("classification"));
  }

  @Test
  void storeAndMoneyFiltersApplyToEveryReconciliationPanel() {
    var filters = new HashMap<>(week);
    filters.put("storeId", "" + storeA);
    filters.put("minCent", "2000");
    var result = service.reconciliation(filters);
    var associations = list(result, "associations");
    assertEquals(1, associations.size());
    assertEquals(storeA, Db.id(associations.getFirst(), "storeId"));
    assertEquals(3000L, Db.id(associations.getFirst(), "amountCent"));
    assertEquals(1L, Db.id(list(result, "distribution").getFirst(), "count"));
  }

  @Test
  void newerPartialOrDeclaredBillCannotReplaceProviderCompleteBaseline() {
    var filters = new HashMap<>(week);
    filters.put("end", "2026-10-15");
    var result = service.reconciliation(filters);
    var batches = list(result, "batches");
    assertEquals(1, batches.size());
    assertEquals(effectiveBill, Db.id(batches.getFirst(), "id"));
    assertEquals("PROVIDER_COMPLETE", batches.getFirst().get("completeness"));
    assertTrue(
        batches.stream()
            .noneMatch(b -> Db.id(b, "id") == partialBill || Db.id(b, "id") == declaredBill));
    assertEquals("MATCHED", list(result, "distribution").getFirst().get("classification"));
  }

  @Test
  void identicalStoreNamesRemainSeparateInAssociationsAndTransactionGroups() {
    assertEquals(2, list(service.reconciliation(week), "associations").size());
    var filters = new HashMap<>(week);
    filters.put("dimension", "store");
    assertEquals(2, list(service.transactions(filters), "groups").size());
    filters.put("pageSize", "1");
    filters.put("page", "2");
    var page = service.details(filters);
    assertEquals(3L, Db.id(page, "total"));
    assertEquals(1, ((List<?>) page.get("items")).size());
  }

  @SuppressWarnings("unchecked")
  @Test
  void caseStatisticsReconstructStatusBeforeLaterReview() {
    var before = (List<Map<String, Object>>) service.cases(week);
    assertEquals(1, before.size());
    assertEquals("PROCESSING", before.getFirst().get("status"));
    assertEquals("LOCAL_ONLY", before.getFirst().get("classification"));
    assertNull(before.getFirst().get("averageMinutes"));
    assertEquals(2L, ((Number) before.getFirst().get("overdueDays")).longValue());
    var later = new HashMap<>(week);
    later.put("asOf", "2026-10-21T09:30:00Z");
    var after = (List<Map<String, Object>>) service.cases(later);
    assertEquals("CLOSED", after.getFirst().get("status"));
    assertEquals(4380.0, ((Number) after.getFirst().get("averageMinutes")).doubleValue());
  }

  @SuppressWarnings("unchecked")
  @Test
  void riskSeparatesEffectiveEventsFromHistoricalPendingCases() {
    var result = risk.overview(week);
    var summary = (Map<String, Object>) result.get("summary");
    assertEquals(3, Db.id(summary, "eventCount"));
    assertEquals(2, Db.id(summary, "exceptionEventCount"));
    assertEquals("5000", summary.get("differenceCent"));
    assertEquals(1, Db.id(summary, "pendingEventCount"));
    assertEquals("2000", summary.get("pendingCent"));
    assertEquals(1, Db.id(summary, "overdueCaseCount"));
    assertNull(summary.get("medianMinutes"));
    assertEquals("DAYS_3_6", list(result, "aging").getFirst().get("ageBucket"));
    assertEquals(0, Db.id((Map<String,Object>) result.get("previous"), "eventCount"));
    var later = new HashMap<>(week);
    later.put("asOf", "2026-10-21T09:30:00Z");
    var after = (Map<String,Object>) risk.overview(later).get("summary");
    assertEquals("0", after.get("pendingCent"));
    assertEquals(1, Db.id(after, "closedCaseCount"));
    assertEquals(1, Db.id(after, "slaEligibleCount"));
    assertEquals(0, Db.id(after, "onTimeCount"));
    assertEquals(4380.0, ((Number) after.get("medianMinutes")).doubleValue());
    assertEquals(4380.0, ((Number) after.get("p95Minutes")).doubleValue());
    var queryOnly = new HashMap<>(week);
    queryOnly.put("reconciliationSource", "TRADE_QUERY");
    var querySummary = (Map<String,Object>) risk.overview(queryOnly).get("summary");
    assertEquals(1, Db.id(querySummary, "eventCount"));
    assertEquals(0, Db.id(querySummary, "caseCount"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void duplicateObservationsAndMultipleCasesNeverMultiplyRiskMoney() throws Exception {
    connection.setAutoCommit(false);
    try {
      long source = batch("RISK_DUPLICATE", "XML", "2026-10-14T16:00:00", "2026-10-21T16:00:00", "2026-10-21T07:45:00", "PROVIDER_COMPLETE");
      long entry;
      try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM transaction_entry WHERE event_key='P:B'")) { rows.next(); entry = rows.getLong(1); }
      long origin = result(source, entry, "B", "BILL_DUPLICATE");
      insert("INSERT reconciliation_result(batch_id,result_key,source_event_key,entry_id,classification,reason_code) OUTPUT INSERTED.id VALUES(" + source + ",'DUP:P:B','P:B'," + entry + ",'BILL_DUPLICATE','DUPLICATE_INPUT')");
      insertCase("DUP", origin, "OPEN", "2026-10-19T08:00:00", "2026-10-22T08:00:00", null);
      var summary = (Map<String,Object>) risk.overview(week).get("summary");
      // The separate overlapping C observation remains effective; the duplicate B rows are one event.
      assertEquals(2, Db.id(summary, "eventCount"));
      assertEquals(2, Db.id(summary, "exceptionEventCount"));
      assertEquals(1, Db.id(summary, "uncertainDifferenceCount"));
      assertEquals("3000", summary.get("differenceCent"));
      assertEquals(1, Db.id(summary, "pendingEventCount"));
      assertEquals(2, Db.id(summary, "openCaseCount"));
      assertEquals("2000", summary.get("pendingCent"));
      var filters = new HashMap<>(week); filters.put("kind", "pending");
      var details = risk.details(filters);
      assertEquals(1, Db.id(details, "total"));
      var row = ((List<Map<String,Object>>) details.get("items")).getFirst();
      assertEquals(2, Db.id(row, "caseCount"));
      assertEquals(2, row.get("caseIds").toString().split(",").length);
    } finally { connection.rollback(); connection.setAutoCommit(true); }
  }

  @SuppressWarnings("unchecked")
  @Test
  void riskAmountsRemainExactBeyondJavascriptAndBigintAggregateLimits() throws Exception {
    connection.setAutoCommit(false);
    try {
      long source = batch("RISK_LARGE", "XML", "2026-10-14T16:00:00", "2026-10-21T16:00:00", "2026-10-21T07:50:00", "PROVIDER_COMPLETE");
      long large = Long.MAX_VALUE - 1000;
      for (String key : List.of("LARGE_A", "LARGE_B")) {
        long origin = result(source, event(key, storeA, large, "2026-10-17T04:00:00"), key, "LOCAL_ONLY");
        insertCase(key, origin, "OPEN", "2026-10-20T08:00:00", "2026-10-22T08:00:00", null);
      }
      var filters = new HashMap<>(week); filters.put("storeId", Long.toString(storeA)); filters.put("minCent", Long.toString(large));
      var summary = (Map<String,Object>) risk.overview(filters).get("summary");
      String expected = new java.math.BigInteger(Long.toString(large)).multiply(java.math.BigInteger.TWO).toString();
      assertEquals(expected, summary.get("pendingCent"));
      assertEquals(expected, summary.get("differenceCent"));
      assertEquals(expected, ((Map<?,?>) ((Map<?,?>) Api.normalize(risk.overview(filters))).get("summary")).get("pendingCent"));
    } finally { connection.rollback(); connection.setAutoCommit(true); }
  }

  @SuppressWarnings("unchecked")
  @Test
  void riskPaginationFiltersAndCurrentMerchantAccessApplyToEverySource() throws Exception {
    var filters = new HashMap<>(week); filters.put("pageSize", "1"); filters.put("page", "2");
    var page = risk.details(filters);
    assertEquals(2, Db.id(page, "total"));
    assertEquals(1, ((List<?>) page.get("items")).size());
    filters.put("storeId", Long.toString(storeA)); filters.put("page", "1");
    assertEquals(1, Db.id(risk.details(filters), "total"));
    filters.put("day", "2026-10-15");
    assertEquals(0, Db.id(risk.details(filters), "total"));
    filters = new HashMap<>(week); filters.put("kind", "pending"); filters.put("ageBucket", "UNDER_1");
    assertEquals(0, Db.id(risk.details(filters), "total"));
    RequestContext.set(new RequestContext.Actor(user, "analytics", "成员", "MEMBER"), "risk-no-membership");
    var invisible = (Map<String,Object>) risk.overview(week).get("summary");
    assertEquals(0, Db.id(invisible, "eventCount"));
    assertEquals(0, Db.id(invisible, "caseCount"));
    assertEquals(0, Db.id(risk.details(week), "total"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void historicalCaseDetailsNeverLeakFutureClosureOrCurrentEditableFields() {
    var filters = new HashMap<>(week); filters.put("kind", "overdue");
    var row = ((List<Map<String,Object>>) risk.details(filters).get("items")).getFirst();
    assertEquals("PROCESSING", row.get("status"));
    assertNull(row.get("closedAt"));
    assertFalse(row.containsKey("afterJson"));
    assertFalse(row.containsKey("resolutionText"));
    assertFalse(row.containsKey("reviewedBy"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void percentileAndSlaUseValidClosedDurationsWithAnExplicitDenominator() throws Exception {
    connection.setAutoCommit(false);
    try {
      long source = batch("RISK_DURATION", "XML", "2026-10-14T16:00:00", "2026-10-21T16:00:00", "2026-10-21T07:50:00", "PROVIDER_COMPLETE");
      for (int hour = 1; hour <= 4; hour++) {
        String key = "DURATION_" + hour;
        long origin = result(source, event(key, storeA, 1000, "2026-10-17T04:00:00"), key, "LOCAL_ONLY");
        String due = hour == 3 ? null : hour == 4 ? "2026-10-20T05:00:00" : "2026-10-20T01:00:00";
        insertCase(key, origin, "CLOSED", "2026-10-20T00:00:00", due, "2026-10-20T0" + hour + ":00:00");
      }
      var filters = new HashMap<>(week); filters.put("storeId", Long.toString(storeA));
      var summary = (Map<String,Object>) risk.overview(filters).get("summary");
      assertEquals(4, Db.id(summary, "closedCaseCount"));
      assertEquals(3, Db.id(summary, "slaEligibleCount"));
      assertEquals(2, Db.id(summary, "onTimeCount"));
      assertEquals(1, Db.id(summary, "incompleteHistoryCount"));
      assertEquals(150.0, ((Number) summary.get("medianMinutes")).doubleValue(), 0.0001);
      assertEquals(231.0, ((Number) summary.get("p95Minutes")).doubleValue(), 0.0001);
      filters.put("kind", "slaEligible");
      assertEquals(3, Db.id(risk.details(filters), "total"));
      filters.put("kind", "onTime");
      assertEquals(2, Db.id(risk.details(filters), "total"));
    } finally { connection.rollback(); connection.setAutoCommit(true); }
  }

  @SuppressWarnings("unchecked")
  @Test
  void realMyBatisKeepsEmptyHistoricalPercentilesUnknownWithoutDroppingAggregateRow() throws Exception {
    var properties = new Properties();
    for (String key : List.of("user", "password")) {
      String value = System.getenv("RECONIS_SQL_TEST_" + key.toUpperCase(Locale.ROOT));
      if (value != null) properties.setProperty(key, value);
    }
    try (var mappedConnection = DriverManager.getConnection(System.getenv("RECONIS_SQL_TEST_URL"), properties)) {
      mappedConnection.setCatalog(database);
      var configuration = new org.apache.ibatis.session.Configuration();
      configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("risk-test",
          new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
          new org.apache.ibatis.datasource.unpooled.UnpooledDataSource()));
      configuration.addMapper(Db.SqlMapper.class);
      try (var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration).openSession(mappedConnection)) {
        var mapper = session.getMapper(Db.SqlMapper.class);
        // This is the production mapper behavior missed by the plain JDBC test adapter.
        assertNull(mapper.query("SELECT CAST(NULL AS FLOAT) median_minutes,CAST(NULL AS FLOAT) p95_minutes", Map.of()).getFirst());
        var mappedDb = new Db(mapper);
        var mappedRisk = new RiskAnalyticsService(mappedDb, new AnalyticsService(mappedDb, new Access(mappedDb)));
        var historical = new HashMap<>(week);
        historical.put("asOf", "2026-10-17T08:00:00Z");
        var empty = (Map<String,Object>) mappedRisk.overview(historical).get("summary");
        assertEquals(0, Db.id(empty, "caseCount"));
        assertEquals(0, Db.id(empty, "durationSampleCount"));
        assertTrue(empty.containsKey("medianMinutes"));
        assertTrue(empty.containsKey("p95Minutes"));
        assertNull(empty.get("medianMinutes"));
        assertNull(empty.get("p95Minutes"));
        var pending = (Map<String,Object>) mappedRisk.overview(week).get("summary");
        assertEquals(1, Db.id(pending, "openCaseCount"));
        assertEquals(0, Db.id(pending, "durationSampleCount"));
        assertNull(pending.get("medianMinutes"));
        historical.put("asOf", "2026-10-21T09:30:00Z");
        var closed = (Map<String,Object>) mappedRisk.overview(historical).get("summary");
        assertEquals(1, Db.id(closed, "durationSampleCount"));
        assertEquals(4380.0, ((Number) closed.get("medianMinutes")).doubleValue(), 0.0001);
      }
    }
  }

  private long insertCase(String key, long origin, String status, String created, String due, String closed) throws SQLException {
    return insert("INSERT exception_task(case_no,case_key,origin_result_id,latest_result_id,status,title,description,due_at,closed_at,created_at,updated_at) OUTPUT INSERTED.id VALUES('RISK-" + key + "','RISK-" + key + "'," + origin + "," + origin + ",'" + status + "',N'风险分析验证',N'独立测试数据'," + (due == null ? "NULL" : "'" + due + "'") + "," + (closed == null ? "NULL" : "'" + closed + "'") + ",'" + created + "','" + (closed == null ? created : closed) + "')");
  }
}
