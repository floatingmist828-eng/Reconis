package cn.reconis.payment;

import static cn.reconis.support.SqlSandbox.*;
import static org.junit.jupiter.api.Assertions.*;
import cn.reconis.support.SqlSandbox;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class FinancialConcurrencyDatabaseTest {
  SqlSandbox sql;
  long user, reviewer, store, order, payment;
  String trade;
  @BeforeAll void setup() throws Exception { sql = new SqlSandbox(); }
  @AfterAll void cleanup() throws Exception { if (sql != null) sql.close(); }
  @BeforeEach void fixture() throws Exception {
    String key = UUID.randomUUID().toString();
    user = insert(sql.db, "INSERT app_user(username,password_hash,display_name,status) VALUES(?,'test',N'申请人','ACTIVE')", key);
    reviewer = insert(sql.db, "INSERT app_user(username,password_hash,display_name,status) VALUES(?,'test',N'审批人','ACTIVE')", "R" + key);
    long merchant = insert(sql.db, "INSERT merchant(merchant_code,name,category_code,status) VALUES(?,N'并发测试',N'餐饮','ACTIVE')", key.replace("-", ""));
    store = insert(sql.db, "INSERT store(merchant_id,store_code,name) VALUES(?,'S',N'测试门店')", merchant);
    order = insert(sql.db, "INSERT business_order(order_no,store_id,created_by,client_request_id,subject,status) VALUES(?,?,?,?,N'测试订单','WAITING_PAYMENT')", key, store, user, key);
    exec(sql.db, "INSERT order_item(order_id,line_no,item_name,quantity,unit_price_cent) VALUES(?,1,N'测试商品',1,10000)", order);
    trade = "PAY" + key;
    payment = insert(sql.db, "INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,status) VALUES(?,?,?,'DEMO','DEMO_APP','DEMO_SELLER','PENDING')", order, key, trade);
  }
  void confirm(Connection c) throws Exception {
    exec(c, "EXEC usp_confirm_payment @payment_id=?,@channel_trade_no=?,@amount_cent=10000,@occurred_at='2026-10-21T08:00:00'", payment, trade);
  }
  long refund(long amount) throws Exception {
    String key = UUID.randomUUID().toString();
    return insert(sql.db, "INSERT refund_request(payment_id,client_request_id,out_request_no,amount_cent,reason,applicant_id) VALUES(?,?,?,?,N'测试退款',?)", payment, key, key, amount, user);
  }
  interface Action { int run(Connection c, int i) throws Exception; }
  List<Integer> concurrent(int count, Action action) throws Exception {
    var ready = new CountDownLatch(count);
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(count)) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < count; i++) { final int n = i;
        futures.add(pool.submit(() -> { try (var c = sql.connect()) {
          ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS));
          return action.run(c, n);
        }}));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
      List<Integer> results = new ArrayList<>();
      for (var result : futures) results.add(result.get(35, TimeUnit.SECONDS));
      return results;
    }
  }

  @Test void simultaneousCallbacksAndQueriesCreateOneReceipt() throws Exception {
    assertEquals(List.of(0,0,0,0,0,0,0,0), concurrent(8, (c, n) -> { confirm(c); return 0; }));
    assertEquals(1L, scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE payment_id=?", payment));
    assertEquals(10000L, scalar(sql.db, "SELECT SUM(amount_cent) FROM transaction_entry WHERE payment_id=?", payment));
    assertEquals("PAID", scalar(sql.db, "SELECT status FROM business_order WHERE id=?", order));
  }

  @Test void simultaneousApprovalsCannotReserveMoreThanPaid() throws Exception {
    confirm(sql.db);
    long[] ids = {refund(6000), refund(6000)};
    var results = concurrent(2, (c, n) -> {
      try { exec(c, "EXEC usp_approve_refund ?,?,1,N'独立审核'", ids[n], reviewer); return 0; }
      catch (SQLException e) { return e.getErrorCode(); }
    });
    assertEquals(1, results.stream().filter(x -> x == 0).count());
    assertEquals(1, results.stream().filter(x -> x == 51012).count());
    assertEquals(4000L, scalar(sql.db, "SELECT dbo.fn_refundable_amount(?)", payment));
  }

  @Test void duplicateRefundConfirmationIsIdempotentAcrossConnections() throws Exception {
    confirm(sql.db); long id = refund(3000);
    exec(sql.db, "EXEC usp_approve_refund ?,?,1,N'独立审核'", id, reviewer);
    concurrent(6, (c, n) -> { exec(c, "EXEC usp_confirm_refund ?,3000,'2026-10-21T09:00:00'", id); return 0; });
    assertEquals(1L, scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE refund_id=?", id));
    assertEquals(7000L, scalar(sql.db, "SELECT dbo.fn_refundable_amount(?)", payment));
  }

  @Test void failedLedgerWriteRollsBackPaymentAndOrder() throws Exception {
    // Fail after the payment state UPDATE; the procedure must roll back every earlier change.
    exec(sql.db, "CREATE TRIGGER dbo.trg_fault_injection ON dbo.transaction_entry AFTER INSERT AS THROW 51999,N'Injected ledger failure',1;");
    try { assertEquals(51999, assertThrows(SQLException.class, () -> confirm(sql.db)).getErrorCode()); }
    finally { exec(sql.db, "DROP TRIGGER dbo.trg_fault_injection"); }
    assertEquals("PENDING", scalar(sql.db, "SELECT status FROM payment_attempt WHERE id=?", payment));
    assertEquals("WAITING_PAYMENT", scalar(sql.db, "SELECT status FROM business_order WHERE id=?", order));
    assertEquals(0L, scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE payment_id=?", payment));
    confirm(sql.db);
    assertEquals(1L, scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE payment_id=?", payment));
  }

  @Test void processDeathRollsBackOpenTransactionAndAllowsRecovery() throws Exception {
    String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    var child = new ProcessBuilder(javaBinary, "-Xmx64m", "-XX:ActiveProcessorCount=2", "-cp",
        System.getProperty("java.class.path"), CrashClient.class.getName(), sql.name,
        Long.toString(payment), trade).redirectErrorStream(true).start();
    assertTrue(child.waitFor(30, TimeUnit.SECONDS));
    String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(23, child.exitValue(), output);
    assertEquals("PENDING", scalar(sql.db, "SELECT status FROM payment_attempt WHERE id=?", payment));
    assertEquals(0L, scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE payment_id=?", payment));
    confirm(sql.db);
    assertEquals(1L, scalar(sql.db, "SELECT COUNT_BIG(*) FROM transaction_entry WHERE payment_id=?", payment));
  }

  public static class CrashClient {
    public static void main(String[] args) throws Exception {
      var p = new Properties(); p.setProperty("user", System.getenv("RECONIS_SQL_TEST_USER")); p.setProperty("password", System.getenv("RECONIS_SQL_TEST_PASSWORD"));
      var c = DriverManager.getConnection(System.getenv("RECONIS_SQL_TEST_URL"), p);
      c.setCatalog(args[0]); c.setAutoCommit(false);
      exec(c, "EXEC usp_confirm_payment ?,?,10000,'2026-10-21T08:00:00'", Long.parseLong(args[1]), args[2]);
      Runtime.getRuntime().halt(23);
    }
  }
}
