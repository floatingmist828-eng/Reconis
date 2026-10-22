package cn.reconis.casework;

import static org.junit.jupiter.api.Assertions.*;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/** Exercises dossier SQL and tenant isolation against a real, disposable SQL Server database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class CaseEvidenceDatabaseTest {
  @TempDir static Path temp;
  Connection admin, connection;
  String database;
  FileStore files;
  CaseEvidenceService service;
  long owner, outsider, finance, merchant, otherMerchant, caseId, origin, matched, foreignResult;
  String originalFile, oldAttachment, currentAttachment;

  @BeforeAll void setup() throws Exception {
    var properties = new Properties();
    for (String key : List.of("user", "password")) {
      String value = System.getenv("RECONIS_SQL_TEST_" + key.toUpperCase(Locale.ROOT));
      if (value != null) properties.setProperty(key, value);
    }
    String url = System.getenv("RECONIS_SQL_TEST_URL");
    admin = DriverManager.getConnection(url, properties);
    database = "ReconisEvidenceTest_" + UUID.randomUUID().toString().replace("-", "");
    try (var s = admin.createStatement()) { s.execute("CREATE DATABASE [" + database + "]"); }
    connection = DriverManager.getConnection(url, properties);
    connection.setCatalog(database);
    for (String file : List.of("001_schema.sql", "002_constraints_indexes.sql", "003_views_functions_procedures.sql")) {
      for (String batch : Files.readString(Path.of("..", "database", file)).split("(?im)^GO\\s*$")) {
        if (!batch.isBlank()) try (var s = connection.createStatement()) {
          boolean result = s.execute(batch);
          while (result || s.getUpdateCount() != -1) result = s.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        }
      }
    }
    Db db = new Db(new Db.SqlMapper() {
      public List<Map<String, Object>> search(String sql, Map<String, Object> params) {
        return query(sql, params);
      }
      public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
        try (var s = prepare(sql, params); var result = s.executeQuery()) {
          var rows = new ArrayList<Map<String, Object>>();
          while (result.next()) {
            var row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
              row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
            rows.add(row);
          }
          return rows;
        } catch (SQLException e) { throw new IllegalStateException(sql, e); }
      }
      public int execute(String sql, Map<String, Object> params) {
        try (var s = prepare(sql, params)) { return s.executeUpdate(); }
        catch (SQLException e) { throw new IllegalStateException(sql, e); }
      }
    });
    files = new FileStore(temp.resolve("demo").toString(), "DEMO", "jdbc:sqlserver:ReconisDemo");
    service = new CaseEvidenceService(db, new Access(db), files);
    owner = user("owner", "MEMBER"); outsider = user("outsider", "MEMBER"); finance = user("finance", "PLATFORM_FINANCE");
    merchant = merchant("M1"); otherMerchant = merchant("M2");
    execute("INSERT merchant_member(merchant_id,user_id,role_code) VALUES(?,?,'OWNER')", merchant, owner);
    execute("INSERT merchant_member(merchant_id,user_id,role_code) VALUES(?,?,'OWNER')", otherMerchant, outsider);
    long store = insert("INSERT store(merchant_id,store_code,name) OUTPUT INSERTED.id VALUES(?,'S1',N'测试窗口')", merchant);
    long order = insert("INSERT business_order(order_no,store_id,created_by,client_request_id,subject,status) OUTPUT INSERTED.id VALUES('O1',?,?,'req1',N'测试订单','PAID')", store, owner);
    execute("INSERT order_item(order_id,line_no,item_name,quantity,unit_price_cent) VALUES(?,1,N'餐费',1,10000)", order);
    long payment = insert("INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,channel_trade_no,status,is_primary) OUTPUT INSERTED.id VALUES(?,'payreq','P1','DEMO','DEMO','DEMO','C1','SUCCEEDED',1)", order);
    String channel = files.write("evidence", ".json", "{\"amountCent\":10000}".getBytes(StandardCharsets.UTF_8));
    long entry = insert("INSERT transaction_entry(entry_no,event_key,entry_type,payment_id,amount_cent,channel_occurred_at,evidence_file_uri) OUTPUT INSERTED.id VALUES('E1','P:P1','RECEIPT',?,10000,'2026-10-21T01:00:00',?)", payment, channel);
    originalFile = files.write("imports", ".xml", "<statement><merchant>M1</merchant><merchant>M2</merchant></statement>".getBytes(StandardCharsets.UTF_8));
    long bill = batch("B1", merchant, originalFile, "2026-10-21T02:00:00");
    long line = insert("INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at,raw_line_file_uri) OUTPUT INSERTED.id VALUES(?,1,'RECEIPT','P1','C1',9800,'2026-10-21T01:00:00',?)", bill, originalFile);
    origin = result(bill, entry, line, "AMOUNT_MISMATCH");
    long later = batch("B2", merchant, originalFile, "2026-10-21T03:00:00");
    long corrected = insert("INSERT statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at,raw_line_file_uri) OUTPUT INSERTED.id VALUES(?,1,'RECEIPT','P1','C1',10000,'2026-10-21T01:00:00',?)", later, originalFile);
    matched = result(later, entry, corrected, "MATCHED");
    long otherBill = batch("FOREIGN_BILL", otherMerchant, originalFile, "2026-10-21T04:00:00");
    foreignResult = result(otherBill, null, null, "CHANNEL_ONLY");
    caseId = insert("INSERT exception_task(case_no,case_key,origin_result_id,latest_result_id,status,title,description,resolution_code,resolution_text,reviewed_by,closed_at) OUTPUT INSERTED.id VALUES('CASE1','CASEKEY1',?,?,'CLOSED',N'金额差错',N'原始差异200分','RECONCILED',N'复核匹配',?,SYSUTCDATETIME())", origin, matched, finance);
    oldAttachment = attachment("旧处理证据"); currentAttachment = attachment("复核所用证据");
    execute("UPDATE exception_task SET evidence_file_uri=? WHERE id=?", currentAttachment, caseId);
    execute("INSERT audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,before_json,after_json) VALUES('DB_CHANGE',?,?,'CASE',?,'CASE_WORKFLOW','SUCCESS',?,?)", owner, merchant, caseId,
        "{\"latest_result_id\":" + origin + ",\"evidence_file_uri\":\"" + oldAttachment + "\"}",
        "{\"latest_result_id\":" + matched + ",\"evidence_file_uri\":\"" + currentAttachment + "\"}");
    byte[] callback = "{\"out_trade_no\":\"P1\"}".getBytes(StandardCharsets.UTF_8);
    String callbackUri = files.write("evidence", ".json", callback);
    execute("INSERT payment_notification(payment_id,notify_id,payload_file_uri,payload_hash,signature_valid,handling_result,request_id) VALUES(?,'N1',?,?,1,'ACCEPTED','test')", payment, callbackUri, FileStore.sha(callback));
    execute("INSERT audit_log(event_category,scope_merchant_id,object_type,object_id,action,result,summary) VALUES('CHANNEL_QUERY',?,'PAYMENT',?,'PAYMENT_QUERY','UNKNOWN','DEMO / ERROR')", merchant, payment);
  }

  @AfterAll void cleanup() throws Exception {
    RequestContext.clear();
    if (connection != null) connection.close();
    if (admin != null) try (var s = admin.createStatement()) {
      if (database != null) { s.execute("ALTER DATABASE [" + database + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE"); s.execute("DROP DATABASE [" + database + "]"); }
    } finally { if (admin != null) admin.close(); }
  }

  @AfterEach void clearActor() { RequestContext.clear(); }

  @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Map<String, Object> d, String key) {
    return (List<Map<String, Object>>) d.get(key);
  }

  @Test void historyIncludesOriginalAndReviewedResultsButExcludesOtherTenantSameEvent() {
    actor(owner, "MEMBER");
    var dossier = service.inspect(caseId);
    var versions = rows(dossier, "reconciliationVersions");
    assertEquals(Set.of(origin, matched), new HashSet<>(versions.stream().map(r -> Db.id(r, "id")).toList()));
    assertTrue(versions.stream().allMatch(r -> Boolean.TRUE.equals(r.get("referencedInWorkflow"))));
    assertEquals(1, rows(dossier, "orders").size());
    assertEquals(1, rows(dossier, "ledger").size());
    assertEquals(2, rows(dossier, "statementLines").size());
    assertEquals(1, rows(dossier, "notifications").size());
    assertEquals("UNKNOWN", rows(dossier, "channelHistory").getFirst().get("result"));
    var evidence = rows(dossier, "files");
    assertTrue(evidence.stream().anyMatch(f -> oldAttachment.equals(f.get("sourceUri")) && "VERIFIED".equals(f.get("verification"))));
    assertTrue(evidence.stream().anyMatch(f -> currentAttachment.equals(f.get("sourceUri")) && "VERIFIED".equals(f.get("verification"))));
    assertTrue(evidence.stream().anyMatch(f -> originalFile.equals(f.get("sourceUri")) && "RESTRICTED".equals(f.get("verification"))));
  }

  @Test void otherMerchantCannotInspectOrDownloadEvenWithKnownCaseId() {
    actor(outsider, "MEMBER");
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.inspect(caseId)).getStatusCode().value());
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.archive(caseId)).getStatusCode().value());
  }

  @Test void platformCanVerifyOriginalAndDownloadArchiveWithAuditRecord() throws Exception {
    actor(finance, "PLATFORM_FINANCE");
    var dossier = service.inspect(caseId);
    assertTrue(rows(dossier, "files").stream().anyMatch(f -> originalFile.equals(f.get("sourceUri")) && "VERIFIED".equals(f.get("verification"))));
    var archive = service.archive(caseId);
    assertEquals(FileStore.sha(archive.bytes()), archive.sha256());
    try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM audit_log WHERE object_type='CASE_DOSSIER' AND object_id=" + caseId)) {
      assertTrue(r.next()); assertTrue(r.getInt(1) >= 1);
    }
  }

  @Test void revokedMembershipBlocksNewArchiveInsteadOfReusingEarlierAuthorization() throws Exception {
    actor(owner, "MEMBER");
    assertNotNull(service.inspect(caseId));
    execute("UPDATE merchant_member SET status='DISABLED' WHERE user_id=? AND merchant_id=?", owner, merchant);
    try { assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.archive(caseId)).getStatusCode().value()); }
    finally { execute("UPDATE merchant_member SET status='ACTIVE' WHERE user_id=? AND merchant_id=?", owner, merchant); }
  }

  private void actor(long id, String role) {
    RequestContext.set(new RequestContext.Actor(id, "test", "测试人员", role), "evidence-test");
  }

  private String attachment(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    String uri = files.write("evidence", ".txt", bytes);
    files.jsonWrite(uri + ".meta.json", Map.of("caseId", caseId, "uri", uri, "name", "proof.txt", "sha256", FileStore.sha(bytes)));
    return uri;
  }

  private long user(String name, String role) throws Exception {
    return insert("INSERT app_user(username,password_hash,display_name,platform_role,status) OUTPUT INSERTED.id VALUES(?,'unused',?,?, 'ACTIVE')", name, name, role);
  }
  private long merchant(String code) throws Exception {
    return insert("INSERT merchant(merchant_code,name,category_code,status) OUTPUT INSERTED.id VALUES(?,?,N'餐饮','ACTIVE')", code, code);
  }
  private long batch(String no, long merchantId, String uri, String observed) throws Exception {
    return insert("INSERT reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,source_file_uri,file_sha256,ingest_options_sha256,completeness,status,observed_at) OUTPUT INSERTED.id VALUES(?,'MERCHANT',?,'2026-10-20','2026-10-22','XML',?,?,?,'DECLARED_COMPLETE','COMPLETED',?)",
        no, merchantId, uri, FileStore.sha(Files.readAllBytes(files.resolve(uri))), FileStore.sha(no.getBytes(StandardCharsets.UTF_8)), observed);
  }
  private long result(long batch, Long entry, Long line, String classification) throws Exception {
    return insert("INSERT reconciliation_result(batch_id,result_key,source_event_key,entry_id,statement_line_id,classification,reason_code) OUTPUT INSERTED.id VALUES(?,'P:P1','P:P1',?,?,?,'AMOUNT_DIFFERENCE')", batch, entry, line, classification);
  }
  private long insert(String sql, Object... values) throws Exception {
    try (var s = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      try (var r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1); }
    }
  }
  private void execute(String sql, Object... values) throws Exception {
    try (var s = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      s.executeUpdate();
    }
  }
  private PreparedStatement prepare(String sql, Map<String, Object> params) throws SQLException {
    var values = new ArrayList<Object>();
    var matcher = Pattern.compile("#\\{p\\.(\\w+)\\}").matcher(sql);
    var prepared = new StringBuilder();
    while (matcher.find()) { values.add(params.get(matcher.group(1))); matcher.appendReplacement(prepared, "?"); }
    matcher.appendTail(prepared);
    var s = connection.prepareStatement(prepared.toString());
    for (int i = 0; i < values.size(); i++) s.setObject(i + 1, values.get(i));
    return s;
  }
}
