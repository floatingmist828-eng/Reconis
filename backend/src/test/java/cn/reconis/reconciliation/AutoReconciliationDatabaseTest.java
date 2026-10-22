package cn.reconis.reconciliation;

import static org.junit.jupiter.api.Assertions.*;
import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import cn.reconis.payment.PaymentGateway;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="RECONIS_SQL_TEST_URL",matches=".+")
class AutoReconciliationDatabaseTest {
  @TempDir static Path directory;
  Connection admin, connection;
  String database;
  Db db;
  FileStore files;
  AutoReconciliationService service;
  TransactionTemplate transactions;
  long finance;
  int sequence=0;

  @BeforeAll void setup() throws Exception {
    String url=System.getenv("RECONIS_SQL_TEST_URL"); var properties=new Properties();
    for(String key:List.of("user","password")) { String value=System.getenv("RECONIS_SQL_TEST_"+key.toUpperCase(Locale.ROOT)); if(value!=null) properties.setProperty(key,value); }
    admin=DriverManager.getConnection(url,properties); database="ReconisAutomaticTest_"+UUID.randomUUID().toString().replace("-","");
    try(var s=admin.createStatement()) { s.execute("CREATE DATABASE ["+database+"]"); s.execute("ALTER DATABASE ["+database+"] SET ALLOW_SNAPSHOT_ISOLATION ON"); }
    connection=DriverManager.getConnection(url,properties); connection.setCatalog(database);
    for(String file:List.of("001_schema.sql","002_constraints_indexes.sql","003_views_functions_procedures.sql","004_triggers_fulltext.sql","migrations/006_integrity_and_imports.sql","migrations/007_auto_reconciliation.sql")) apply(file);
    var source=new SQLServerDataSource(); source.setURL(url); source.setDatabaseName(database);
    if(properties.containsKey("user")) source.setUser(properties.getProperty("user"));
    if(properties.containsKey("password")) source.setPassword(properties.getProperty("password"));
    var config=new Configuration(new Environment("automatic",new SpringManagedTransactionFactory(),source)); config.addMapper(Db.SqlMapper.class);
    var template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)); db=new Db(template.getMapper(Db.SqlMapper.class));
    files=new FileStore(directory.resolve("demo").toString(),"DEMO","jdbc:sqlserver://local;databaseName=ReconisDemo");
    var access=new Access(db); var gateway=new PaymentGateway(files,"DEMO","","","","","");
    var manager=new DataSourceTransactionManager(source); var importer=new ImportService(db,files,access,gateway);
    var proxy=new ProxyFactory(importer); proxy.setProxyTargetClass(true);
    proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
    transactions=new TransactionTemplate(manager);
    service=new AutoReconciliationService(db,files,access,gateway,(ImportService)proxy.getProxy(),transactions);
    finance=db.insert("INSERT app_user(username,password_hash,display_name,platform_role,status) OUTPUT INSERTED.id VALUES('automatic_finance','unused',N'平台财务','PLATFORM_FINANCE','ACTIVE')",Api.params());
  }

  @BeforeEach void actor() { RequestContext.set(new RequestContext.Actor(finance,"automatic_finance","平台财务","PLATFORM_FINANCE"),UUID.randomUUID().toString()); }
  @AfterEach void clear() { RequestContext.clear(); }
  LocalDate nextDay() { return LocalDate.of(2025,1,1).plusDays(sequence++); }
  long queue(LocalDate date) { return Db.id(service.enqueue(date.toString(),date.toString(),false).getFirst(),"id"); }
  String state(long id) { return service.detail(id).get("acquisitionState").toString(); }

  @Test void migrationCanRepeatWithExistingTasksWithoutAddingAnEntity() throws Exception {
    long id=queue(nextDay());
    apply("migrations/006_integrity_and_imports.sql"); apply("migrations/007_auto_reconciliation.sql"); apply("migrations/007_auto_reconciliation.sql");
    assertEquals("QUEUED",state(id));
    assertEquals(15L,Db.id(db.need("SELECT COUNT_BIG(*) n FROM sys.tables WHERE is_ms_shipped=0",Api.params()),"n"));
  }

  @Test void duplicateClosedDayReusesTaskAndSuccessfulEmptyBillDoesNotDuplicateInputs() throws Exception {
    LocalDate day=nextDay(); long id=queue(day); assertEquals(id,queue(day));
    service.process(id); assertEquals("SUCCEEDED",state(id));
    String result=service.detail(id).get("acquisitionResult").toString();
    service.process(id); assertEquals(result,service.detail(id).get("acquisitionResult"));
    assertEquals(1L,Db.id(service.detail(id),"acquisitionAttempts"));
    assertThrows(ResponseStatusException.class,()->service.retry(id));
    assertEquals(0L,Db.id(db.need("SELECT COUNT_BIG(*) n FROM v_reconciliation_summary WHERE id=#{p.id}",Api.params("id",id)),"n"));
  }

  @Test void lostNotificationChannelRecordProducesRealDiscrepancyThroughFullPipeline() throws Exception {
    LocalDate day=nextDay(); String key="L_"+UUID.randomUUID().toString().replace("-","");
    long merchant=db.insert("INSERT merchant(merchant_code,name,category_code,status) OUTPUT INSERTED.id VALUES(#{p.key},N'自动对账商户',N'餐饮','ACTIVE')",Api.params("key",key.substring(0,30)));
    long store=db.insert("INSERT store(merchant_id,store_code,name) OUTPUT INSERTED.id VALUES(#{p.m},'S',N'窗口')",Api.params("m",merchant));
    long order=db.insert("INSERT business_order(order_no,store_id,created_by,client_request_id,subject) OUTPUT INSERTED.id VALUES(#{p.key},#{p.store},#{p.actor},#{p.key},N'通知丢失')",Api.params("key",key,"store",store,"actor",finance));
    db.exec("INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id) VALUES(#{p.order},#{p.key},#{p.key},'DEMO','DEMO_APP','DEMO_SELLER')",Api.params("order",order,"key",key));
    files.jsonWrite("channel/"+key+".json",Map.of("status","SUCCEEDED","amountCent","1200","tradeNo","DEMO_"+key,"occurredAt",day+"T01:00:00Z"));
    long id=queue(day); service.process(id); assertEquals("SUCCEEDED",state(id));
    assertEquals(1L,Db.id(db.need("SELECT COUNT_BIG(*) n FROM reconciliation_result r JOIN statement_line s ON s.id=r.statement_line_id WHERE s.out_trade_no=#{p.key} AND r.classification='CHANNEL_ONLY'",Api.params("key",key)),"n"));
    assertEquals(1L,Db.id(db.need("SELECT COUNT_BIG(*) n FROM exception_task t JOIN reconciliation_result r ON r.id=t.origin_result_id JOIN statement_line s ON s.id=r.statement_line_id WHERE s.out_trade_no=#{p.key}",Api.params("key",key)),"n"));
  }

  @Test void oneMerchantFailureKeepsOtherResultAndRetryDoesNotReconcileItTwice() throws Exception {
    LocalDate day=nextDay();
    long bad=seedChannelReceipt(day), good=seedChannelReceipt(day);
    try(var statement=connection.createStatement()) {
      statement.execute("CREATE OR ALTER TRIGGER dbo.trg_test_one_merchant_failure ON dbo.reconciliation_result AFTER INSERT AS BEGIN IF EXISTS(SELECT 1 FROM inserted i JOIN dbo.reconciliation_batch b ON b.id=i.batch_id WHERE b.merchant_id="+bad+") THROW 51079,N'TEST_CONTROLLED_TRANSIENT',1; END;");
    }
    long id=queue(day);
    try {
      service.process(id); assertEquals("RETRY_WAIT",state(id));
      assertEquals(1L,Db.id(db.need("SELECT COUNT_BIG(*) n FROM reconciliation_result r JOIN reconciliation_batch b ON b.id=r.batch_id WHERE b.merchant_id=#{p.id}",Api.params("id",good)),"n"));
    } finally { try(var statement=connection.createStatement()){statement.execute("DROP TRIGGER IF EXISTS dbo.trg_test_one_merchant_failure");} }
    service.retry(id); service.process(id); assertEquals("SUCCEEDED",state(id));
    for(long merchant:List.of(bad,good))
      assertEquals(1L,Db.id(db.need("SELECT COUNT_BIG(*) n FROM reconciliation_result r JOIN reconciliation_batch b ON b.id=r.batch_id WHERE b.merchant_id=#{p.id}",Api.params("id",merchant)),"n"));
  }

  long seedChannelReceipt(LocalDate day) {
    String key="A_"+UUID.randomUUID().toString().replace("-","");
    long merchant=db.insert("INSERT merchant(merchant_code,name,category_code,status) OUTPUT INSERTED.id VALUES(#{p.key},N'隔离验证商户',N'餐饮','ACTIVE')",Api.params("key",key.substring(0,30)));
    long store=db.insert("INSERT store(merchant_id,store_code,name) OUTPUT INSERTED.id VALUES(#{p.m},'S',N'窗口')",Api.params("m",merchant));
    long order=db.insert("INSERT business_order(order_no,store_id,created_by,client_request_id,subject) OUTPUT INSERTED.id VALUES(#{p.key},#{p.store},#{p.actor},#{p.key},N'独立商户账单')",Api.params("key",key,"store",store,"actor",finance));
    db.exec("INSERT payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id) VALUES(#{p.order},#{p.key},#{p.key},'DEMO','DEMO_APP','DEMO_SELLER')",Api.params("order",order,"key",key));
    files.jsonWrite("channel/"+key+".json",Map.of("status","SUCCEEDED","amountCent","1200","tradeNo","DEMO_"+key,"occurredAt",day+"T01:00:00Z"));
    return merchant;
  }

  @Test void expiredWorkerLeaseIsRecoveredAndFourthInterruptedAttemptStops() {
    long id=queue(nextDay());
    db.exec("UPDATE reconciliation_batch SET acquisition_state='FETCHING',acquisition_attempts=1,acquisition_token='old',acquisition_lease_until=DATEADD(MINUTE,-1,SYSUTCDATETIME()) WHERE id=#{p.id}",Api.params("id",id));
    service.process(id); assertEquals("SUCCEEDED",state(id)); assertEquals(2L,Db.id(service.detail(id),"acquisitionAttempts"));
    long exhausted=queue(nextDay());
    db.exec("UPDATE reconciliation_batch SET acquisition_state='FETCHING',acquisition_attempts=4,acquisition_token='old',acquisition_lease_until=DATEADD(MINUTE,-1,SYSUTCDATETIME()) WHERE id=#{p.id}",Api.params("id",exhausted));
    service.process(exhausted); assertEquals("FAILED",state(exhausted));
  }

  @Test void concurrentWorkersClaimExactlyOnce() throws Exception {
    long id=queue(nextDay()); var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var work=new ArrayList<Future<?>>();
      for(int i=0;i<2;i++) work.add(pool.submit(()->{try{start.await();service.process(id);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{RequestContext.clear();}}));
      start.countDown(); for(var future:work) future.get(60,TimeUnit.SECONDS);
    }
    assertEquals("SUCCEEDED",state(id)); assertEquals(1L,Db.id(service.detail(id),"acquisitionAttempts"));
  }

  @Test void ordinaryMemberCannotReadGlobalChannelBillsOrCreateTasks() {
    long id=queue(nextDay()); RequestContext.set(new RequestContext.Actor(finance,"member","成员","MEMBER"),"scope-test");
    assertThrows(ResponseStatusException.class,()->service.detail(id));
    assertThrows(ResponseStatusException.class,()->service.enqueue("2025-01-01","2025-01-01",false));
    assertThrows(ResponseStatusException.class,()->service.retry(id));
    assertThrows(ResponseStatusException.class,()->service.original(id));
  }

  @Test void missingSandboxConfigurationIsVisibleAndCannotCreateFalseDiscrepancies() throws Exception {
    var sandboxFiles=new FileStore(directory.resolve("sandbox").toString(),"ALIPAY_SANDBOX","jdbc:sqlserver://local;databaseName=ReconisSandbox");
    var sandbox=new PaymentGateway(sandboxFiles,"ALIPAY_SANDBOX","","","","","");
    var access=new Access(db);
    var other=new AutoReconciliationService(db,sandboxFiles,access,sandbox,new ImportService(db,sandboxFiles,access,sandbox),transactions);
    LocalDate day=nextDay();
    long id=Db.id(other.enqueue(day.toString(),day.toString(),false).getFirst(),"id");
    other.process(id);
    assertEquals("UNCONFIGURED",other.detail(id).get("acquisitionState"));
    assertNull(other.detail(id).get("sourceFileUri"));
    assertEquals(0L,Db.id(db.need("SELECT COUNT_BIG(*) n FROM reconciliation_result WHERE batch_id=#{p.id}",Api.params("id",id)),"n"));
    long next=queue(nextDay()); service.process(next); assertEquals("SUCCEEDED",state(next));
  }

  @Test void revokedCreatorCannotRunAnAlreadyQueuedJob() {
    long user=db.insert("INSERT app_user(username,password_hash,display_name,platform_role,status) OUTPUT INSERTED.id VALUES('temporary_finance','unused',N'临时财务','PLATFORM_FINANCE','ACTIVE')",Api.params());
    RequestContext.set(new RequestContext.Actor(user,"temporary_finance","临时财务","PLATFORM_FINANCE"),"revocation-test");
    long id=queue(nextDay());
    db.exec("UPDATE app_user SET status='DISABLED' WHERE id=#{p.id}",Api.params("id",user));
    service.process(id); actor();
    assertEquals("FAILED",state(id)); assertNull(service.detail(id).get("sourceFileUri"));
  }

  @Test void tamperedOriginalIsRejectedRatherThanDownloadedOrReimported() throws Exception {
    long id=queue(nextDay()); service.process(id); var item=service.detail(id);
    Files.writeString(files.resolve(item.get("sourceFileUri").toString()),"changed");
    assertThrows(ResponseStatusException.class,()->service.original(id));
    db.exec("UPDATE reconciliation_batch SET acquisition_state='FAILED' WHERE id=#{p.id}",Api.params("id",id));
    service.retry(id); service.process(id); assertEquals("FAILED",state(id));
    assertTrue(service.detail(id).get("acquisitionMessage").toString().contains("散列"));
  }

  void apply(String file) throws Exception { for(String batch:Files.readString(Path.of("..","database",file)).split("(?im)^GO\\s*$")) if(!batch.isBlank()) try(var s=connection.createStatement()){boolean result=s.execute(batch);while(result||s.getUpdateCount()!=-1)result=s.getMoreResults(Statement.CLOSE_CURRENT_RESULT);} }
  @AfterAll void cleanup() throws Exception {
    RequestContext.clear(); if(connection!=null)connection.close();
    if(admin!=null)try(var s=admin.createStatement()){if(database!=null){s.execute("ALTER DATABASE ["+database+"] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");s.execute("DROP DATABASE ["+database+"]");}}finally{admin.close();}
  }
}
