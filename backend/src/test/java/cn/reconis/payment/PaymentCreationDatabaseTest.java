package cn.reconis.payment;

import static cn.reconis.support.SqlSandbox.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.support.SqlSandbox;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises persisted feedback, real SQL transactions, recovery and concurrent precreate leases. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class PaymentCreationDatabaseTest {
  @TempDir static Path directory;
  SqlSandbox sql;
  Db db;
  FileStore files;
  TransactionTemplate transactions;
  PaymentGateway gateway;
  PaymentService service;
  long order;
  String request;

  @BeforeAll void setup() throws Exception {
    sql = new SqlSandbox();
    var source = new SQLServerDataSource();
    source.setURL(System.getenv("RECONIS_SQL_TEST_URL"));
    source.setDatabaseName(sql.name);
    source.setUser(System.getenv("RECONIS_SQL_TEST_USER"));
    source.setPassword(System.getenv("RECONIS_SQL_TEST_PASSWORD"));
    var configuration = new Configuration(new Environment("payment-feedback", new SpringManagedTransactionFactory(), source));
    configuration.addMapper(Db.SqlMapper.class);
    var template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    db = new Db(template.getMapper(Db.SqlMapper.class));
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    files = new FileStore(directory.resolve("demo").toString(), "DEMO", "jdbc:sqlserver://local;databaseName=ReconisDemo");
  }

  @BeforeEach void fixture() throws Exception {
    String key = UUID.randomUUID().toString();
    long user = insert(sql.db, "INSERT app_user(username,password_hash,display_name,status) VALUES(?,'unused',N'支付测试','ACTIVE')", key);
    long merchant = insert(sql.db, "INSERT merchant(merchant_code,name,category_code,status) VALUES(?,N'支付测试',N'餐饮','ACTIVE')", key.replace("-", ""));
    long store = insert(sql.db, "INSERT store(merchant_id,store_code,name) VALUES(?,'S',N'测试门店')", merchant);
    order = insert(sql.db, "INSERT business_order(order_no,store_id,created_by,client_request_id,subject,status) VALUES(?,?,?,?,N'测试订单','WAITING_PAYMENT')", key, store, user, key);
    exec(sql.db, "INSERT order_item(order_id,line_no,item_name,quantity,unit_price_cent) VALUES(?,1,N'商品',1,100)", order);
    request = UUID.randomUUID().toString();
    gateway = spy(new PaymentGateway(files, "DEMO", "", "", "", "", ""));
    service = new PaymentService(db, files, gateway, transactions);
  }

  @AfterEach void clear() { RequestContext.clear(); }
  @AfterAll void cleanup() throws Exception { if (sql != null) sql.close(); }

  PaymentGateway.Result result(String state, String code, String qr) {
    return new PaymentGateway.Result(state, "CHANNEL_" + order, 100,
        LocalDateTime.of(2026, 10, 22, 4, 0), code, "{\"sub_msg\":\"sensitive request data\"}", qr);
  }

  Map<String, Object> create() { return service.create(order, request); }
  Map<String, Object> stored(long id) { return db.need("SELECT * FROM payment_attempt WHERE id=#{p.id}", Api.params("id", id)); }
  long ledger(long id) throws Exception { return (long) scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE payment_id=?", id); }

  @Test void qrCodeAndFeedbackSurviveReloadAndRepeatedCreateUsesOneRequest() throws Exception {
    doReturn(result("PENDING", "10000", "https://qr.alipay.com/test")).when(gateway).create(anyMap());
    var created = create();
    assertEquals("PENDING", created.get("status"));
    assertEquals("PAY", created.get("nextAction"));
    var persisted = stored(Db.id(created, "id"));
    assertEquals(created.get("qrCode"), persisted.get("qrCode"));
    assertEquals(created.get("lastResultMessage"), persisted.get("lastResultMessage"));
    assertEquals(created.get("id"), service.create(order, UUID.randomUUID().toString()).get("id"));
    verify(gateway, times(1)).create(anyMap());
    assertEquals(0, ledger(Db.id(created, "id")));
  }

  @Test void firstExplicitRefusalIsDurableFailedAndRetryNeedsANewClientRequest() throws Exception {
    doReturn(result("FAILED", "ACQ.ACCESS_FORBIDDEN", null)).when(gateway).create(anyMap());
    var rejected = create();
    long id = Db.id(rejected, "id");
    assertEquals("FAILED", rejected.get("status"));
    assertEquals("CONTACT_SUPPORT", rejected.get("nextAction"));
    assertNull(rejected.get("qrCode"));
    assertNull(rejected.get("nextQueryAt"));
    assertFalse(rejected.get("lastResultMessage").toString().contains("sensitive"));
    assertEquals(id, Db.id(create(), "id"));
    assertEquals("FAILED", ((Map<?, ?>) service.syncPayment(id)).get("status"));
    verify(gateway, never()).query(anyMap());
    doReturn(result("PENDING", "10000", "https://qr.alipay.com/retry")).when(gateway).create(anyMap());
    assertNotEquals(id, Db.id(service.create(order, UUID.randomUUID().toString()), "id"));
    assertEquals(0, ledger(id));
  }

  @Test void timeoutDoesNotLeakExceptionOrCreateANewPaymentDuringUncertainQuery() throws Exception {
    doThrow(new java.io.IOException("private_key=secret; signed_url=sensitive")).when(gateway).create(anyMap());
    doReturn(result("UNKNOWN", "ACQ.SYSTEM_ERROR", null)).when(gateway).query(anyMap());
    var first = create();
    assertEquals("UNKNOWN", first.get("status"));
    assertEquals("QUERY", first.get("nextAction"));
    assertFalse(first.toString().contains("private_key"));
    var retried = service.create(order, UUID.randomUUID().toString());
    assertEquals(first.get("id"), retried.get("id"));
    verify(gateway, times(1)).create(anyMap());
    assertEquals(1, Db.id(stored(Db.id(first, "id")), "precreateCount"));
  }

  @Test void rejectionOnRetryAfterTimeoutCannotReleaseOriginalPaymentAsFailed() throws Exception {
    doThrow(new java.io.IOException("timeout")).when(gateway).create(anyMap());
    var first = create();
    String trade = first.get("outTradeNo").toString();
    doReturn(result("UNKNOWN", "ACQ.TRADE_NOT_EXIST", null)).when(gateway).query(anyMap());
    doReturn(result("FAILED", "ACQ.INVALID_PARAMETER", null)).when(gateway).create(anyMap());
    var retried = service.create(order, UUID.randomUUID().toString());
    assertEquals(first.get("id"), retried.get("id"));
    assertEquals(trade, retried.get("outTradeNo"));
    assertEquals("UNKNOWN", retried.get("status"));
    assertEquals("QUERY", retried.get("nextAction"));
    assertEquals(2, Db.id(retried, "precreateCount"));
    assertEquals(0, ledger(Db.id(first, "id")));
  }

  @Test void successfulRecoveryBooksOnceAndReturnsCurrentTerminalState() throws Exception {
    doThrow(new java.io.IOException("timeout")).when(gateway).create(anyMap());
    long id = Db.id(create(), "id");
    doReturn(result("SUCCEEDED", "10000", null)).when(gateway).query(anyMap());
    var recovered = (Map<?, ?>) service.syncPayment(id);
    assertEquals("SUCCEEDED", recovered.get("status"));
    assertEquals("NONE", recovered.get("nextAction"));
    assertEquals("SUCCEEDED", create().get("status"));
    service.syncPayment(id);
    assertEquals(1, ledger(id));
    verify(gateway, times(1)).query(anyMap());
  }

  @Test void simultaneousCreateCallsShareCommittedLeaseAndOneChannelRequest() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); return result("PENDING", "10000", "https://qr.alipay.com/one"); })
        .when(gateway).create(anyMap());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(this::create);
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var concurrent = pool.submit(() -> service.create(order, UUID.randomUUID().toString())).get(10, TimeUnit.SECONDS);
        assertEquals("CREATED", concurrent.get("status"));
        assertEquals(true, concurrent.get("precreateInFlight"));
        release.countDown();
        var completed = first.get(10, TimeUnit.SECONDS);
        assertEquals(concurrent.get("id"), completed.get("id"));
        assertEquals("PENDING", completed.get("status"));
        verify(gateway, times(1)).create(anyMap());
      } finally { release.countDown(); }
    }
  }

  @Test void latePrecreateResponseAndFailureCannotDowngradeConfirmedPayment() throws Exception {
    for (boolean networkFailure : List.of(false, true)) {
      if (networkFailure) fixture();
      doAnswer(call -> {
        Map<String, Object> payment = call.getArgument(0);
        try (var connection = sql.connect()) {
          exec(connection, "EXEC usp_confirm_payment @payment_id=?,@channel_trade_no=?,@amount_cent=100,@occurred_at='2026-10-22T04:00:00'",
              payment.get("id"), "CHANNEL_" + order);
        }
        if (networkFailure) throw new java.io.IOException("late timeout");
        return result("PENDING", "10000", "https://qr.alipay.com/stale");
      }).when(gateway).create(anyMap());
      var response = create();
      assertEquals("SUCCEEDED", response.get("status"));
      assertNull(response.get("qrCode"));
      assertEquals("NONE", response.get("nextAction"));
      assertEquals(1, ledger(Db.id(response, "id")));
    }
  }

  @Test void queryCannotDowngradeATerminalResultWrittenWhileChannelWasResponding() throws Exception {
    doReturn(result("PENDING", "10000", "https://qr.alipay.com/query")).when(gateway).create(anyMap());
    long id = Db.id(create(), "id");
    doAnswer(call -> {
      exec(sql.db, "UPDATE payment_attempt SET status='CLOSED',next_query_at=NULL,next_action='RETRY',qr_code=NULL WHERE id=?", id);
      return result("UNKNOWN", "ACQ.SYSTEM_ERROR", null);
    }).when(gateway).query(anyMap());
    assertEquals("CLOSED", ((Map<?, ?>) service.syncPayment(id)).get("status"));
    assertNull(stored(id).get("nextQueryAt"));
    assertEquals(0, ledger(id));
  }

  @Test void expiredLeaseAfterCrashQueriesOriginalRequestBeforeDispatch() throws Exception {
    long id = insert(sql.db, "INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status,precreate_count,dispatch_token,dispatch_lease_until) VALUES(?,?,?,'DEMO','DEMO_APP','DEMO_SELLER','CREATED',1,'old-worker',DATEADD(MINUTE,-1,SYSUTCDATETIME()))", order, request, "CRASH_" + order);
    doReturn(result("UNKNOWN", "ACQ.TRADE_NOT_EXIST", null)).when(gateway).query(anyMap());
    doReturn(result("PENDING", "10000", "https://qr.alipay.com/recovered")).when(gateway).create(anyMap());
    var recovered = create();
    assertEquals(id, Db.id(recovered, "id"));
    assertEquals("CRASH_" + order, recovered.get("outTradeNo"));
    assertEquals("PENDING", recovered.get("status"));
    var calls = inOrder(gateway);
    calls.verify(gateway).query(anyMap());
    calls.verify(gateway).create(anyMap());
  }

  @Test void incorrectConfirmedAmountCreatesCaseWithoutBookingReceipt() throws Exception {
    doReturn(result("PENDING", "10000", "https://qr.alipay.com/amount")).when(gateway).create(anyMap());
    long id = Db.id(create(), "id");
    doReturn(new PaymentGateway.Result("SUCCEEDED", "CHANNEL_" + order, 200,
        LocalDateTime.of(2026, 10, 22, 4, 0), "10000", "{}", null)).when(gateway).query(anyMap());
    var queried = (Map<?, ?>) service.syncPayment(id);
    assertEquals("UNKNOWN", queried.get("status"));
    assertEquals("AMOUNT_MISMATCH", queried.get("lastResultCode"));
    assertEquals("CONTACT_SUPPORT", queried.get("nextAction"));
    assertEquals(0, ledger(id));
    assertEquals(1L, scalar(sql.db, "SELECT COUNT_BIG(*) FROM v_case WHERE source_event_key=?", "P:" + queried.get("outTradeNo")));
  }
}
