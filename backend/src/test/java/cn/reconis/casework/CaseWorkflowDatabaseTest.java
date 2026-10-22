package cn.reconis.casework;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the real SQL Server schema and procedures in an automatically removed, isolated database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class CaseWorkflowDatabaseTest {
  private Connection admin, db;
  private String database;
  private long merchant, otherMerchant, handler, reviewer, origin, caseId;
  private String event;
  private final LocalDateTime clock = LocalDateTime.of(2026, 10, 21, 8, 0);

  @BeforeAll
  void createDatabase() throws Exception {
    Properties properties = new Properties();
    for (String key : List.of("user", "password")) {
      String value = System.getenv("RECONIS_SQL_TEST_" + key.toUpperCase(Locale.ROOT));
      if (value != null) properties.setProperty(key, value);
    }
    String url = System.getenv("RECONIS_SQL_TEST_URL");
    admin = DriverManager.getConnection(url, properties);
    database = "ReconisWorkflowTest_" + UUID.randomUUID().toString().replace("-", "");
    try (Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE [" + database + "]");
      statement.execute("ALTER DATABASE [" + database + "] SET ALLOW_SNAPSHOT_ISOLATION ON");
    }
    db = DriverManager.getConnection(url, properties);
    db.setCatalog(database);
    for (String file :
        List.of(
            "001_schema.sql",
            "002_constraints_indexes.sql",
            "003_views_functions_procedures.sql",
            "004_triggers_fulltext.sql")) apply(file);
    long preservedMerchant =
        insert(
            "INSERT merchant(merchant_code,name,category_code,status)"
                + " VALUES('MIGRATION_SENTINEL',N'升级保留商户',N'餐饮','ACTIVE')");
    long preservedBatch =
        insert(
            "INSERT reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,"
                + "source_type,file_sha256,status)"
                + " VALUES('MIGRATION_SENTINEL','MERCHANT',?,'2026-10-01','2026-10-02','XLSX',?,'READY')",
            preservedMerchant,
            "a".repeat(64));
    // Reconstruct the prior release, including its original unique import index and source types.
    execute("DROP INDEX UX_import_file ON reconciliation_batch");
    execute("ALTER TABLE refund_request DROP COLUMN dispatch_token,dispatch_lease_until");
    execute("ALTER TABLE reconciliation_batch DROP COLUMN ingest_options_sha256");
    execute("ALTER TABLE reconciliation_batch DROP CONSTRAINT CK_batch_source_type");
    execute(
        "ALTER TABLE reconciliation_batch ADD CONSTRAINT CK_legacy_batch_source_type"
            + " CHECK(source_type IN ('XLSX','XML','TRADE_QUERY','DEMO_SEED'))");
    execute(
        "CREATE UNIQUE INDEX UX_import_file ON reconciliation_batch"
            + " (scope_type,merchant_id,file_sha256) WHERE file_sha256 IS NOT NULL"
            + " AND statement_source_batch_id IS NULL");
    // Existing installations must accept the same view/procedure/trigger update a second time.
    apply("migrations/006_integrity_and_imports.sql");
    apply("migrations/006_integrity_and_imports.sql");
    apply("003_views_functions_procedures.sql");
    apply("004_triggers_fulltext.sql");
    assertEquals("升级保留商户", scalar("SELECT name FROM merchant WHERE id=?", preservedMerchant));
    assertEquals(
        "a".repeat(64),
        scalar("SELECT file_sha256 FROM reconciliation_batch WHERE id=?", preservedBatch));
    assertNotNull(scalar("SELECT COL_LENGTH('dbo.refund_request','dispatch_token')"));
    assertNotNull(scalar("SELECT COL_LENGTH('dbo.refund_request','dispatch_lease_until')"));
    assertNotNull(scalar("SELECT COL_LENGTH('dbo.reconciliation_batch','ingest_options_sha256')"));
  }

  private void apply(String file) throws Exception {
    String sql = Files.readString(Path.of("..", "database", file), StandardCharsets.UTF_8);
    for (String batch : sql.split("(?im)^GO\\s*$")) {
      if (!batch.isBlank())
        try (Statement statement = db.createStatement()) {
          boolean hasResult = statement.execute(batch);
          while (hasResult || statement.getUpdateCount() != -1)
            hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        } catch (SQLException error) {
          throw new SQLException("执行数据库脚本 " + file + " 失败: " + error.getMessage(), error);
        }
    }
  }

  @AfterAll
  void removeDatabase() throws Exception {
    if (db != null) db.close();
    if (admin != null) {
      try (Statement statement = admin.createStatement()) {
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

  @BeforeEach
  void fixture() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    handler =
        insert(
            "INSERT app_user(username,password_hash,display_name,platform_role,status)"
                + " VALUES(?,'test','处理人','PLATFORM_FINANCE','ACTIVE')",
            "handler" + suffix);
    reviewer =
        insert(
            "INSERT app_user(username,password_hash,display_name,platform_role,status)"
                + " VALUES(?,'test','复核人','PLATFORM_FINANCE','ACTIVE')",
            "reviewer" + suffix);
    merchant =
        insert(
            "INSERT merchant(merchant_code,name,category_code,status)"
                + " VALUES(?,'商户甲','餐饮','ACTIVE')",
            suffix);
    otherMerchant =
        insert(
            "INSERT merchant(merchant_code,name,category_code,status)"
                + " VALUES(?,'商户乙','餐饮','ACTIVE')",
            "M" + suffix.substring(1));
    event = "P:" + suffix;
    origin = result(merchant, "CHANNEL_ONLY", clock.minusHours(4));
    caseId =
        insert(
            "INSERT"
                + " exception_task(case_no,case_key,origin_result_id,latest_result_id,assignee_id,status,title,description,analysis_text,resolution_text,submitted_by)"
                + " VALUES(?,?,?,?,?,'PENDING_REVIEW',N'差错',N'核查',N'第一轮分析',N'第一轮结论',?)",
            suffix,
            suffix,
            origin,
            origin,
            handler,
            handler);
  }

  private long result(long scope, String classification, LocalDateTime time) throws Exception {
    long batch =
        insert(
            "INSERT"
                + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,status,observed_at,finished_at,created_at)"
                + " VALUES(?,'MERCHANT',?,?,?,'DEMO_SEED','COMPLETED',?,?,?)",
            UUID.randomUUID().toString(),
            scope,
            clock.minusDays(1),
            clock.plusDays(1),
            time,
            time.plusSeconds(1),
            time);
    return insert(
        "INSERT"
            + " reconciliation_result(batch_id,result_key,source_event_key,classification,reason_code,detected_at)"
            + " VALUES(?,?,?,?,?,?)",
        batch,
        event,
        event,
        classification,
        "TEST",
        time.plusNanos(100_000_000));
  }

  private long insert(String sql, Object... parameters) throws Exception {
    try (PreparedStatement statement =
        prepare(sql + "; SELECT CONVERT(BIGINT,SCOPE_IDENTITY())", parameters)) {
      boolean result = statement.execute();
      while (!result && statement.getUpdateCount() != -1) result = statement.getMoreResults();
      try (ResultSet rows = statement.getResultSet()) {
        assertTrue(rows.next());
        return rows.getLong(1);
      }
    }
  }

  private PreparedStatement prepare(String sql, Object... parameters) throws SQLException {
    PreparedStatement statement = db.prepareStatement(sql);
    for (int index = 0; index < parameters.length; index++)
      statement.setObject(index + 1, parameters[index]);
    return statement;
  }

  private void execute(String sql, Object... parameters) throws Exception {
    try (PreparedStatement statement = prepare(sql, parameters)) {
      boolean hasResult = statement.execute();
      while (hasResult || statement.getUpdateCount() != -1)
        hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }
  }

  private Object scalar(String sql, Object... parameters) throws Exception {
    try (PreparedStatement statement = prepare(sql, parameters);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getObject(1) : null;
    }
  }

  private void review(boolean approve, String code, Long evidence, byte[] version)
      throws Exception {
    execute(
        "EXEC usp_review_exception"
            + " @case_id=?,@reviewer=?,@approve=?,@resolution_code=?,@reason=N'复核意见',@evidence_result=?,@expected_version=?",
        caseId,
        reviewer,
        approve,
        code,
        evidence,
        version);
  }

  private byte[] version() throws Exception {
    return (byte[]) scalar("SELECT version FROM exception_task WHERE id=?", caseId);
  }

  @Test
  void rejectionCannotMoveCaseToAnotherMerchant() throws Exception {
    long foreign = result(otherMerchant, "MATCHED", clock.minusHours(1));
    SQLException error =
        assertThrows(SQLException.class, () -> review(false, "RECONCILED", foreign, version()));
    assertEquals(51035, error.getErrorCode());
    assertEquals(
        merchant,
        ((Number) scalar("SELECT merchant_id FROM v_case WHERE id=?", caseId)).longValue());
    assertEquals(
        origin,
        ((Number) scalar("SELECT latest_result_id FROM exception_task WHERE id=?", caseId))
            .longValue());
  }

  @Test
  void crossMerchantProofCannotCloseCaseOrBeWrittenDirectly() throws Exception {
    long foreign = result(otherMerchant, "MATCHED", clock.minusHours(1));
    assertEquals(
        51034,
        assertThrows(SQLException.class, () -> review(true, "RECONCILED", foreign, version()))
            .getErrorCode());
    assertEquals(
        51045,
        assertThrows(
                SQLException.class,
                () ->
                    execute(
                        "UPDATE exception_task SET latest_result_id=? WHERE id=?", foreign, caseId))
            .getErrorCode());
  }

  @Test
  void recurrenceInvalidatesOldEvidenceButAcceptsFreshObservation() throws Exception {
    long oldMatch = result(merchant, "MATCHED", clock.minusHours(3));
    long recurrence = result(merchant, "CHANNEL_ONLY", clock.minusHours(2));
    execute("UPDATE exception_task SET latest_result_id=? WHERE id=?", recurrence, caseId);
    assertNull(
        scalar(
            "SELECT result_id FROM v_case_matched_evidence WHERE case_id=? AND result_id=?",
            caseId,
            oldMatch));
    assertEquals(
        51034,
        assertThrows(SQLException.class, () -> review(true, "RECONCILED", oldMatch, version()))
            .getErrorCode());
    long fresh = result(merchant, "MATCHED", clock.minusHours(1));
    review(true, "RECONCILED", fresh, version());
    assertEquals("CLOSED", scalar("SELECT status FROM exception_task WHERE id=?", caseId));
    assertEquals(
        fresh,
        ((Number) scalar("SELECT latest_result_id FROM exception_task WHERE id=?", caseId))
            .longValue());
  }

  @Test
  void staleReviewCannotApproveChangedWork() throws Exception {
    byte[] seen = version();
    execute("UPDATE exception_task SET due_at=SYSUTCDATETIME() WHERE id=?", caseId);
    assertEquals(
        51036,
        assertThrows(SQLException.class, () -> review(true, "KNOWN_EXCEPTION", null, seen))
            .getErrorCode());
    assertEquals("PENDING_REVIEW", scalar("SELECT status FROM exception_task WHERE id=?", caseId));
  }

  @Test
  void replayingSupersededInputCannotSupplyNewClosureEvidence() throws Exception {
    long oldMatch = result(merchant, "MATCHED", clock.minusHours(3));
    long recurrence = result(merchant, "CHANNEL_ONLY", clock.minusHours(2));
    execute("UPDATE exception_task SET latest_result_id=? WHERE id=?", recurrence, caseId);
    long source =
        ((Number) scalar("SELECT batch_id FROM reconciliation_result WHERE id=?", oldMatch))
            .longValue();
    long replay =
        insert(
            "INSERT"
                + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,status,statement_source_batch_id,observed_at,finished_at)"
                + " VALUES(?,'MERCHANT',?,?,?,'DEMO_SEED','COMPLETED',?,?,?)",
            UUID.randomUUID().toString(),
            merchant,
            clock.minusDays(1),
            clock.plusDays(1),
            source,
            clock,
            clock.plusSeconds(1));
    long staleProof =
        insert(
            "INSERT"
                + " reconciliation_result(batch_id,result_key,source_event_key,classification,reason_code,detected_at)"
                + " VALUES(?,?,?,'MATCHED','IDENTICAL',?)",
            replay,
            event,
            event,
            clock.plusNanos(100_000_000));
    assertNull(
        scalar(
            "SELECT result_id FROM v_case_matched_evidence WHERE case_id=? AND result_id=?",
            caseId,
            staleProof));
    assertEquals(
        51034,
        assertThrows(SQLException.class, () -> review(true, "RECONCILED", staleProof, version()))
            .getErrorCode());
  }

  @Test
  void returnedWorkKeepsItsPreviousContentAndAuditCannotBeErased() throws Exception {
    review(false, "", null, version());
    execute(
        "UPDATE exception_task SET"
            + " analysis_text=N'第二轮分析',resolution_text=N'第二轮结论',evidence_file_uri=N'evidence/round2.pdf'"
            + " WHERE id=?",
        caseId);
    String before =
        (String)
            scalar(
                "SELECT TOP 1 before_json FROM audit_log WHERE object_type='CASE' AND object_id=?"
                    + " ORDER BY id DESC",
                caseId);
    assertTrue(before.contains("第一轮分析"));
    assertTrue(before.contains("复核意见"));
    assertEquals(
        51046,
        assertThrows(
                SQLException.class,
                () ->
                    execute(
                        "DELETE FROM audit_log WHERE object_type='CASE' AND object_id=?", caseId))
            .getErrorCode());
  }

  @Test
  void submittedAndClosedContentIsProtectedByTheDatabase() throws Exception {
    assertEquals(
        51048,
        assertThrows(
                SQLException.class,
                () ->
                    execute(
                        "UPDATE exception_task SET evidence_file_uri=N'evidence/replaced.pdf' WHERE"
                            + " id=?",
                        caseId))
            .getErrorCode());
    assertEquals(
        51048,
        assertThrows(
                SQLException.class,
                () -> execute("UPDATE exception_task SET analysis_text=N'替换分析' WHERE id=?", caseId))
            .getErrorCode());
    review(true, "KNOWN_EXCEPTION", null, version());
    assertEquals(
        51048,
        assertThrows(
                SQLException.class,
                () ->
                    execute("UPDATE exception_task SET resolution_text=N'替换结论' WHERE id=?", caseId))
            .getErrorCode());
  }

  @Test
  void caseOriginCannotBeReassigned() throws Exception {
    long foreign = result(otherMerchant, "CHANNEL_ONLY", clock.minusHours(1));
    assertEquals(
        51044,
        assertThrows(
                SQLException.class,
                () ->
                    execute(
                        "UPDATE exception_task SET origin_result_id=?,latest_result_id=? WHERE"
                            + " id=?",
                        foreign,
                        foreign,
                        caseId))
            .getErrorCode());
    assertEquals(
        merchant,
        ((Number) scalar("SELECT merchant_id FROM v_case WHERE id=?", caseId)).longValue());
  }

  private long ownershipFixture(long targetMerchant, int targetLine) throws Exception {
    String key = UUID.randomUUID().toString().replace("-", "");
    String hash = key + key, trade = "OWN" + key;
    long store =
        insert("INSERT store(merchant_id,store_code,name) VALUES(?,?,N'食堂')", merchant, key);
    long order =
        insert(
            "INSERT business_order(order_no,store_id,created_by,client_request_id,subject)"
                + " VALUES(?,?,?,?,N'午餐')",
            key,
            store,
            handler,
            UUID.randomUUID().toString());
    insert(
        "INSERT"
            + " payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id)"
            + " VALUES(?,?,?,'DEMO','demo','demo')",
        order,
        UUID.randomUUID().toString(),
        trade);
    long oldBatch =
        insert(
            "INSERT"
                + " reconciliation_batch(batch_no,scope_type,period_start,period_end,source_type,file_sha256,status,observed_at)"
                + " VALUES(?,'PLATFORM_UNASSIGNED',?,?,'XLSX',?,'COMPLETED',?)",
            UUID.randomUUID().toString(),
            clock.minusDays(1),
            clock.plusDays(1),
            hash,
            clock.minusHours(4));
    long oldLine =
        insert(
            "INSERT"
                + " statement_line(import_batch_id,line_no,event_type,out_trade_no,amount_cent,channel_occurred_at)"
                + " VALUES(?,1,'RECEIPT',?,1234,?)",
            oldBatch,
            trade,
            clock.minusDays(1));
    origin =
        insert(
            "INSERT"
                + " reconciliation_result(batch_id,result_key,source_event_key,statement_line_id,classification,reason_code,detected_at)"
                + " VALUES(?,?,?,?, 'CHANNEL_ONLY','UNASSIGNED_MERCHANT',?)",
            oldBatch,
            "P:" + trade,
            "P:" + trade,
            oldLine,
            clock.minusHours(4).plusSeconds(1));
    caseId =
        insert(
            "INSERT"
                + " exception_task(case_no,case_key,origin_result_id,latest_result_id,assignee_id,status,title,description,analysis_text,resolution_text,submitted_by)"
                + " VALUES(?,?,?,?,?,'PENDING_REVIEW',N'归属核查',N'待认领',N'已识别',N'已核对原支付',?)",
            key,
            key,
            origin,
            origin,
            handler,
            handler);
    long targetBatch =
        insert(
            "INSERT"
                + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,file_sha256,status,observed_at)"
                + " VALUES(?,'MERCHANT',?,?,?,'XLSX',?,'COMPLETED',?)",
            UUID.randomUUID().toString(),
            targetMerchant,
            clock.minusDays(1),
            clock.plusDays(1),
            hash,
            clock.minusHours(1));
    long line =
        insert(
            "INSERT"
                + " statement_line(import_batch_id,line_no,event_type,out_trade_no,amount_cent,channel_occurred_at)"
                + " VALUES(?,?,'RECEIPT',?,1234,?)",
            targetBatch,
            targetLine,
            trade,
            clock.minusDays(1));
    insert(
        "INSERT"
            + " reconciliation_result(batch_id,result_key,source_event_key,statement_line_id,classification,reason_code,detected_at)"
            + " VALUES(?,?,?,?, 'CHANNEL_ONLY','PAYMENT_CONFIRMATION',?)",
        targetBatch,
        "P:" + trade,
        "P:" + trade,
        line,
        clock.minusHours(1).plusSeconds(1));
    return targetBatch;
  }

  private void confirmOwnership(long targetBatch) throws Exception {
    execute(
        "EXEC usp_review_exception"
            + " @case_id=?,@reviewer=?,@approve=1,@resolution_code='OWNERSHIP_CONFIRMED',@reason=N'已按原支付确认商户',@expected_version=?,@attribution_batch=?",
        caseId,
        reviewer,
        version(),
        targetBatch);
  }

  @Test
  void ownershipReviewClosesPlatformCaseWithoutMovingItsScope() throws Exception {
    long target = ownershipFixture(merchant, 1);
    confirmOwnership(target);
    assertEquals("CLOSED", scalar("SELECT status FROM exception_task WHERE id=?", caseId));
    assertNull(scalar("SELECT merchant_id FROM v_case WHERE id=?", caseId));
    assertEquals(
        origin,
        ((Number) scalar("SELECT latest_result_id FROM exception_task WHERE id=?", caseId))
            .longValue());
    String targetId =
        (String)
            scalar(
                "SELECT JSON_VALUE(after_json,'$.target_batch_id') FROM audit_log WHERE"
                    + " object_type='CASE' AND object_id=? AND action='CASE_OWNERSHIP_CONFIRMED'",
                caseId);
    assertEquals(Long.toString(target), targetId);
  }

  @Test
  void sameFileDoesNotProveAnotherRowOrMerchantBelongsToThisCase() throws Exception {
    long wrongLine = ownershipFixture(merchant, 2);
    assertEquals(
        51038, assertThrows(SQLException.class, () -> confirmOwnership(wrongLine)).getErrorCode());
    long wrongMerchant = ownershipFixture(otherMerchant, 1);
    assertEquals(
        51038,
        assertThrows(SQLException.class, () -> confirmOwnership(wrongMerchant)).getErrorCode());
  }
}
