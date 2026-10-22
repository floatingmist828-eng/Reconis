package cn.reconis.payment;

import static cn.reconis.support.SqlSandbox.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import cn.reconis.support.SqlSandbox;
import com.alipay.api.internal.util.AlipaySignature;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;

/** Local RSA2 transport contract and real SQL booking; never contacts or impersonates Alipay. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class PaymentCallbackDatabaseTest {
  @TempDir static Path temp;
  SqlSandbox sql;
  SqlSession session;
  Db db;
  FileStore files;
  PaymentGateway gateway;
  MockMvc http;
  String applicationPrivate, channelPublic, channelPrivate;
  String trade, channelTrade;
  long payment, order;
  static final String APP = "2026000000000000", SELLER = "2088000000000000";

  @BeforeAll
  void setup() throws Exception {
    sql = new SqlSandbox();
    var source = new SQLServerDataSource();
    source.setURL(System.getenv("RECONIS_SQL_TEST_URL"));
    source.setDatabaseName(sql.name);
    source.setUser(System.getenv("RECONIS_SQL_TEST_USER"));
    source.setPassword(System.getenv("RECONIS_SQL_TEST_PASSWORD"));
    var config = new Configuration(new Environment("callback-test", new JdbcTransactionFactory(), source));
    config.addMapper(Db.SqlMapper.class);
    session = new SqlSessionFactoryBuilder().build(config).openSession(true);
    db = new Db(session.getMapper(Db.SqlMapper.class));
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var application = generator.generateKeyPair();
    var channel = generator.generateKeyPair();
    applicationPrivate = temp.resolve("test-application.pem").toString();
    channelPublic = temp.resolve("test-channel.pem").toString();
    Files.writeString(Path.of(applicationPrivate), Base64.getEncoder().encodeToString(application.getPrivate().getEncoded()));
    Files.writeString(Path.of(channelPublic), Base64.getEncoder().encodeToString(channel.getPublic().getEncoded()));
    channelPrivate = Base64.getEncoder().encodeToString(channel.getPrivate().getEncoded());
  }

  @AfterAll
  void cleanup() throws Exception {
    if (session != null) session.close();
    if (sql != null) sql.close();
  }

  @AfterEach
  void clearRequestContext() {
    RequestContext.clear();
  }

  @BeforeEach
  void fixture() throws Exception {
    String key = UUID.randomUUID().toString();
    long user = insert(sql.db, "INSERT app_user(username,password_hash,display_name,status) VALUES(?,'test',N'回调测试','ACTIVE')", key);
    long merchant = insert(sql.db, "INSERT merchant(merchant_code,name,category_code,status) VALUES(?,N'回调测试',N'餐饮','ACTIVE')", key.replace("-", ""));
    long store = insert(sql.db, "INSERT store(merchant_id,store_code,name) VALUES(?,'S',N'回调门店')", merchant);
    order = insert(sql.db, "INSERT business_order(order_no,store_id,created_by,client_request_id,subject,status) VALUES(?,?,?,?,N'签名回调测试','WAITING_PAYMENT')", key, store, user, key);
    exec(sql.db, "INSERT order_item(order_id,line_no,item_name,quantity,unit_price_cent) VALUES(?,1,N'测试商品',1,100)", order);
    trade = "PAY" + key.replace("-", "");
    channelTrade = "CHANNEL" + key.replace("-", "");
    payment = insert(sql.db, "INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status) VALUES(?,?,?,'ALIPAY_SANDBOX',?,?,'PENDING')", order, key, trade, APP, SELLER);
    files = new FileStore(temp.resolve("sandbox").toString(), "ALIPAY_SANDBOX", "jdbc:sqlserver://localhost;databaseName=ReconisSandbox");
    gateway = spy(new PaymentGateway(files, "ALIPAY_SANDBOX", APP, SELLER, applicationPrivate, channelPublic, ""));
    doReturn(result("SUCCEEDED")).when(gateway).query(anyMap());
    var service = new PaymentService(db, files, gateway, mock(TransactionTemplate.class));
    http = MockMvcBuilders.standaloneSetup(new PaymentController(db, new Access(db), service, files, 2500)).build();
    RequestContext.set(null, UUID.randomUUID().toString());
  }

  PaymentGateway.Result result(String status) {
    return new PaymentGateway.Result(status, channelTrade, 100, LocalDateTime.of(2026, 10, 22, 4, 0), "10000", "{\"local_test_query\":true}", null);
  }

  Map<String, String> notification() {
    return new TreeMap<>(Map.of(
        "notify_id", "TEST_" + UUID.randomUUID(), "notify_type", "trade_status_sync",
        "out_trade_no", trade, "trade_no", channelTrade, "app_id", APP,
        "seller_id", SELLER, "total_amount", "1.00", "trade_status", "TRADE_SUCCESS"));
  }

  LinkedMultiValueMap<String, String> signed(Map<String, String> body) throws Exception {
    var parameters = new LinkedMultiValueMap<String, String>();
    body.forEach(parameters::add);
    parameters.add("sign", AlipaySignature.rsaSign(AlipaySignature.getSignContent(body), channelPrivate, "UTF-8", "RSA2"));
    parameters.add("sign_type", "RSA2");
    return parameters;
  }

  void postNotification(Map<String, String> body, String expected) throws Exception {
    postParameters(signed(body), expected);
  }

  void postParameters(LinkedMultiValueMap<String, String> body, String expected) throws Exception {
    http.perform(post("/api/v1/callbacks/alipay").contentType(MediaType.APPLICATION_FORM_URLENCODED).params(body))
        .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string(expected));
  }

  long receipts() throws Exception {
    return (long) scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE payment_id=?", payment);
  }

  String latestOutcome() throws Exception {
    return scalar(sql.db, "SELECT TOP 1 handling_result FROM payment_notification WHERE request_id=? ORDER BY id DESC", RequestContext.requestId()).toString();
  }

  @Test
  void signedNotificationAndRetryBookExactlyOnceAndPreserveOriginalSignedEvidence() throws Exception {
    var body = notification();
    postNotification(body, "success");
    assertEquals(1L, receipts());
    assertEquals("PAID", scalar(sql.db, "SELECT status FROM business_order WHERE id=?", order));
    // The receipt survives an outage; the duplicate still has to pass signature and identity checks.
    doThrow(new java.io.IOException("local injected outage")).when(gateway).query(anyMap());
    postNotification(body, "success");
    verify(gateway, times(1)).query(anyMap());
    assertEquals(1L, receipts());
    assertEquals("ACCEPTED_DUPLICATE", latestOutcome());
    String uri = scalar(sql.db, "SELECT TOP 1 payload_file_uri FROM payment_notification WHERE payment_id=? ORDER BY id DESC", payment).toString();
    var evidence = files.jsonRead(uri);
    assertEquals("RSA2", evidence.get("sign_type"));
    assertNotNull(evidence.get("sign"));
    assertEquals(FileStore.sha(Files.readAllBytes(files.resolve(uri))), scalar(sql.db, "SELECT payload_hash FROM payment_notification WHERE payload_file_uri=?", uri));
    var tampered = signed(body);
    tampered.set("total_amount", "9.99");
    postParameters(tampered, "failure");
    assertEquals(1L, receipts());
  }

  @Test
  void queryFailureReturnsFailureAndTheSameSignedNotificationCanRecover() throws Exception {
    var body = notification();
    doThrow(new java.io.IOException("local injected outage")).when(gateway).query(anyMap());
    postNotification(body, "failure");
    assertEquals(0L, receipts());
    assertEquals("RETRY_QUERY_UNCONFIRMED", latestOutcome());
    doReturn(result("SUCCEEDED")).when(gateway).query(anyMap());
    postNotification(body, "success");
    assertEquals(1L, receipts());
  }

  @Test
  void signedWrongIdentityAmountOrOrderCannotTriggerChannelQueriesOrBooking() throws Exception {
    for (var replacement : Map.of("app_id", "OTHER_APP", "seller_id", "OTHER_SELLER", "total_amount", "2.00", "out_trade_no", "UNKNOWN_ORDER").entrySet()) {
      var body = notification();
      body.put(replacement.getKey(), replacement.getValue());
      postNotification(body, "failure");
    }
    verify(gateway, never()).query(anyMap());
    assertEquals(0L, receipts());
  }

  @Test
  void notificationMustMatchThePaymentBoundIdentityAndEnvironment() throws Exception {
    for (String column : List.of("app_id", "seller_id", "channel_env")) {
      String original = column.equals("app_id") ? APP : column.equals("seller_id") ? SELLER : "ALIPAY_SANDBOX";
      exec(sql.db, "UPDATE payment_attempt SET " + column + "=? WHERE id=?", column.equals("channel_env") ? "DEMO" : "OLD_VALUE", payment);
      try { postNotification(notification(), "failure"); }
      finally { exec(sql.db, "UPDATE payment_attempt SET " + column + "=? WHERE id=?", original, payment); }
    }
    verify(gateway, never()).query(anyMap());
    assertEquals(0L, receipts());
  }

  @Test
  void duplicateAndUnknownFormParametersDoNotBypassTheSignatureContract() throws Exception {
    var duplicate = signed(notification());
    duplicate.add("total_amount", "2.00");
    postParameters(duplicate, "failure");
    for (var replacement : Map.of("trade_status", "UNKNOWN_STATUS", "notify_type", "other_notice", "total_amount", "1.001", "notify_id", "x".repeat(101), "trade_no", "").entrySet()) {
      var body = notification();
      body.put(replacement.getKey(), replacement.getValue());
      postNotification(body, "failure");
    }
    var missing = notification();
    missing.remove("notify_id");
    postNotification(missing, "failure");
    verify(gateway, never()).query(anyMap());
    assertEquals(0L, receipts());
  }

  @Test
  void differentTradeNumberCannotBeAcknowledgedAgainstAnExistingReceipt() throws Exception {
    postNotification(notification(), "success");
    var conflicting = notification();
    conflicting.put("trade_no", "DIFFERENT_CHANNEL_TRADE");
    postNotification(conflicting, "failure");
    assertEquals(1L, receipts());
    verify(gateway, times(1)).query(anyMap());
  }

  @Test
  void queryTradeNumberMustMatchBeforeAcknowledgingANewNotification() throws Exception {
    var body = notification();
    body.put("trade_no", "DIFFERENT_CHANNEL_TRADE");
    postNotification(body, "failure");
    // Only the independent trusted query can book money; the conflicting notification is rejected.
    assertEquals(1L, receipts());
    assertEquals(channelTrade, scalar(sql.db, "SELECT channel_trade_no FROM payment_attempt WHERE id=?", payment));
    assertEquals("REJECTED_TRADE_MISMATCH", latestOutcome());
  }

  @Test
  void waitingAndClosedNotificationsCannotCreateReceipts() throws Exception {
    var body = notification();
    body.put("trade_status", "WAIT_BUYER_PAY");
    postNotification(body, "success");
    verify(gateway, never()).query(anyMap());
    assertEquals(0L, receipts());
    body.put("trade_status", "TRADE_CLOSED");
    doReturn(result("CLOSED")).when(gateway).query(anyMap());
    postNotification(body, "success");
    assertEquals("CLOSED", scalar(sql.db, "SELECT status FROM payment_attempt WHERE id=?", payment));
    assertEquals(0L, receipts());
  }

  @Test
  void closedNotificationAfterPaymentDoesNotReverseTheReceipt() throws Exception {
    var body = notification();
    postNotification(body, "success");
    body.put("trade_status", "TRADE_CLOSED");
    postNotification(body, "success");
    assertEquals("SUCCEEDED", scalar(sql.db, "SELECT status FROM payment_attempt WHERE id=?", payment));
    assertEquals(1L, receipts());
  }

  @Test
  void notificationPersistenceFailureDoesNotAcknowledgeAndRetryDoesNotDuplicateMoney() throws Exception {
    exec(sql.db, "CREATE TRIGGER dbo.trg_callback_test_failure ON dbo.payment_notification AFTER INSERT AS THROW 51998,N'Local callback test failure',1;");
    var body = notification();
    try { postNotification(body, "failure"); }
    finally { exec(sql.db, "DROP TRIGGER dbo.trg_callback_test_failure"); }
    assertEquals(1L, receipts());
    postNotification(body, "success");
    assertEquals(1L, receipts());
    assertEquals("ACCEPTED_DUPLICATE", latestOutcome());
  }
}
