package cn.reconis.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies integration audit visibility using the production mapper and SQL Server. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class PaymentIntegrationDatabaseTest {
  private Connection admin;
  private String database;
  private SqlSession session;
  private PaymentController controller;

  @BeforeAll
  void setupDatabase() throws Exception {
    String url = System.getenv("RECONIS_SQL_TEST_URL");
    var properties = new Properties();
    for (String key : List.of("user", "password")) {
      String value = System.getenv("RECONIS_SQL_TEST_" + key.toUpperCase(Locale.ROOT));
      if (value != null) properties.setProperty(key, value);
    }
    admin = DriverManager.getConnection(url, properties);
    database = "ReconisIntegrationTest_" + UUID.randomUUID().toString().replace("-", "");
    try (var statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE [" + database + "]");
    }
    var source = new SQLServerDataSource();
    source.setURL(url);
    source.setDatabaseName(database);
    if (properties.containsKey("user")) source.setUser(properties.getProperty("user"));
    if (properties.containsKey("password")) source.setPassword(properties.getProperty("password"));
    var config =
        new Configuration(
            new Environment("integration-test", new JdbcTransactionFactory(), source));
    config.addMapper(Db.SqlMapper.class);
    session = new SqlSessionFactoryBuilder().build(config).openSession(true);
    session.getConnection().setCatalog(database);
    assertEquals(database, session.getConnection().getCatalog());
    for (String batch :
        Files.readString(Path.of("..", "database", "001_schema.sql")).split("(?im)^GO\\s*$")) {
      if (!batch.isBlank()) execute(batch);
    }
    execute(
        """
        INSERT app_user(username,password_hash,display_name,status) VALUES
        ('owner','unused',N'负责人','ACTIVE'),('otherowner','unused',N'另一商户','ACTIVE'),
        ('consumer','unused',N'消费者','ACTIVE'),('disabledmember','unused',N'停用成员','ACTIVE'),
        ('former','unused',N'已移除操作员','ACTIVE'),('otherconsumer','unused',N'另一消费者','ACTIVE');
        INSERT merchant(merchant_code,name,category_code,status) VALUES
        ('M1',N'一食堂',N'餐饮','ACTIVE'),('M2',N'二食堂',N'餐饮','ACTIVE');
        INSERT merchant_member(merchant_id,user_id,role_code,status) VALUES
        (1,1,'OWNER','ACTIVE'),(2,2,'OWNER','ACTIVE'),(1,4,'CASHIER','DISABLED'),(1,5,'CASHIER','DISABLED');
        INSERT store(merchant_id,store_code,name) VALUES (1,'S1',N'窗口一'),(2,'S2',N'窗口二');
        INSERT business_order(order_no,store_id,consumer_id,created_by,client_request_id,subject) VALUES
        ('O1',1,3,1,'O1',N'第一笔'),('O2',2,6,2,'O2',N'第二笔'),('O3',2,3,2,'O3',N'第三笔');
        INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id) VALUES
        (1,'P1','P1','DEMO','DEMO_APP','DEMO_SELLER'),
        (2,'P2','P2','DEMO','DEMO_APP','DEMO_SELLER'),
        (3,'P3','P3','DEMO','DEMO_APP','DEMO_SELLER');
        INSERT refund_request(payment_id,client_request_id,out_request_no,amount_cent,reason,applicant_id) VALUES
        (1,'R1','R1',100,N'测试',3),(2,'R2','R2',100,N'测试',6),(3,'R3','R3',100,N'测试',3);
        INSERT audit_log(event_category,scope_merchant_id,object_type,object_id,action,result) VALUES
        ('CHANNEL_QUERY',1,'PAYMENT',1,'P1','SUCCEEDED'),('CHANNEL_QUERY',1,'REFUND',1,'R1','SUCCEEDED'),
        ('CHANNEL_QUERY',2,'PAYMENT',2,'P2','SUCCEEDED'),('CHANNEL_QUERY',2,'REFUND',2,'R2','SUCCEEDED'),
        ('CHANNEL_QUERY',2,'PAYMENT',3,'P3','SUCCEEDED'),('CHANNEL_QUERY',2,'REFUND',3,'R3','SUCCEEDED'),
        ('CHANNEL_QUERY',NULL,NULL,NULL,'UNASSIGNED','UNKNOWN');
        INSERT audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result)
        VALUES('CHANNEL_QUERY',5,1,'PAYMENT',1,'FORMER_ACTOR','SUCCEEDED');
        """);
    var db = new Db(session.getMapper(Db.SqlMapper.class));
    var files = mock(FileStore.class);
    var gateway = new PaymentGateway(files, "DEMO", "", "", "", "", "");
    var service = new PaymentService(db, files, gateway, mock(TransactionTemplate.class));
    controller = new PaymentController(db, new Access(db), service, files, 2500);
  }

  private void execute(String sql) throws Exception {
    try (var statement = session.getConnection().createStatement()) {
      boolean result = statement.execute(sql);
      while (result || statement.getUpdateCount() != -1)
        result = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }
  }

  @AfterEach
  void clearContext() {
    RequestContext.clear();
  }

  @AfterAll
  void removeDatabase() throws Exception {
    if (session != null) session.close();
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> status(long user, String role) {
    RequestContext.set(
        new RequestContext.Actor(user, "test", "测试", role), UUID.randomUUID().toString());
    return (Map<String, Object>) ((Map<?, ?>) controller.status()).get("data");
  }

  private Set<String> actions(long user, String role) {
    var records = (List<?>) status(user, role).get("recentQueries");
    var actions = new HashSet<String>();
    for (Object record : records) actions.add(((Map<?, ?>) record).get("action").toString());
    return actions;
  }

  @Test
  void merchantOnlySeesItsOwnManualAndAutomaticQueries() {
    assertEquals(Set.of("P1", "R1", "FORMER_ACTOR"), actions(1, "MEMBER"));
    assertEquals(Set.of("P2", "R2", "P3", "R3"), actions(2, "MEMBER"));
  }

  @Test
  void consumerSeesTheirOwnPaymentsAndRefundsAcrossMerchants() {
    assertEquals(Set.of("P1", "R1", "P3", "R3", "FORMER_ACTOR"), actions(3, "MEMBER"));
    assertEquals(Set.of("P2", "R2"), actions(6, "MEMBER"));
  }

  @Test
  void disabledMembershipDoesNotGrantAutomaticQueryHistory() {
    assertTrue(actions(4, "MEMBER").isEmpty());
  }

  @Test
  void formerOperatorCannotUseOldActorIdToBypassCurrentAuthorization() {
    assertTrue(actions(5, "MEMBER").isEmpty());
  }

  @Test
  void financeAndAuditorCanInspectAllChannelQueries() {
    assertEquals(8, actions(7, "PLATFORM_FINANCE").size());
    assertEquals(8, actions(8, "AUDITOR").size());
  }

  @Test
  void accountAdministratorDoesNotReceiveFinancialHistoryWithoutBusinessAccess() {
    assertTrue(actions(9, "ADMIN").isEmpty());
  }

  @Test
  void integrationStatusUsesConfiguredSchedulerDelay() {
    var status = status(1, "MEMBER");
    assertEquals(2.5, status.get("automaticQueryIntervalSeconds"));
    assertEquals("DEMO", status.get("configurationStatus"));
    assertEquals(false, status.get("callbackConfigured"));
  }
}
