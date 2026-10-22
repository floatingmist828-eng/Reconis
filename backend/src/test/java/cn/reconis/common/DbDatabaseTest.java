package cn.reconis.common;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exercises the production MyBatis mapper against SQL Server, including trigger result handling.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RECONIS_SQL_TEST_URL", matches = ".+")
class DbDatabaseTest {
  private Connection admin;
  private String database;
  private SqlSession session;
  private Db db;

  @BeforeAll
  void setupDatabase() throws Exception {
    String url = System.getenv("RECONIS_SQL_TEST_URL");
    var properties = new Properties();
    for (String key : List.of("user", "password")) {
      String value = System.getenv("RECONIS_SQL_TEST_" + key.toUpperCase(Locale.ROOT));
      if (value != null) properties.setProperty(key, value);
    }
    admin = DriverManager.getConnection(url, properties);
    database = "ReconisDbTest_" + UUID.randomUUID().toString().replace("-", "");
    try (var statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE [" + database + "]");
    }
    var source = new SQLServerDataSource();
    source.setURL(url);
    source.setDatabaseName(database);
    if (properties.containsKey("user")) source.setUser(properties.getProperty("user"));
    if (properties.containsKey("password")) source.setPassword(properties.getProperty("password"));
    var configuration =
        new Configuration(new Environment("sql-server-test", new JdbcTransactionFactory(), source));
    configuration.addMapper(Db.SqlMapper.class);
    session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
    session.getConnection().setCatalog(database);
    assertEquals(database, session.getConnection().getCatalog());
    db = new Db(session.getMapper(Db.SqlMapper.class));
    try (var statement = session.getConnection().createStatement()) {
      statement.execute(
          "CREATE TABLE insert_target(id BIGINT IDENTITY(41,1) PRIMARY KEY,value"
              + " NVARCHAR(100));CREATE TABLE insert_audit(id BIGINT IDENTITY(9001,1) PRIMARY"
              + " KEY,target_id BIGINT);CREATE TABLE copied_target(id BIGINT IDENTITY(101,1)"
              + " PRIMARY KEY,value NVARCHAR(100));");
      statement.execute(
          "CREATE TRIGGER track_insert ON insert_target AFTER INSERT AS BEGIN SET NOCOUNT ON;"
              + " INSERT insert_audit(target_id) SELECT id FROM inserted; END;");
    }
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

  @Test
  void triggeredInsertReturnsBusinessIdentityRatherThanAuditIdentity() {
    long id =
        db.insert(
            "INSERT insert_target(value) OUTPUT INSERTED.id VALUES(#{p.value})",
            Map.of("value", "触发器插入"));
    assertTrue(id >= 41 && id < 9001);
    var audit =
        db.need("SELECT id,target_id FROM insert_audit WHERE target_id=#{p.id}", Map.of("id", id));
    assertTrue(Db.id(audit, "id") >= 9001);
    assertEquals(id, Db.id(audit, "targetId"));
    assertEquals(
        "触发器插入",
        db.need("SELECT value FROM insert_target WHERE id=#{p.id}", Map.of("id", id)).get("value"));
  }

  @Test
  void insertFromSelectReturnsNewIdentityThroughTheSameMapper() {
    long original =
        db.insert(
            "INSERT insert_target(value) OUTPUT INSERTED.id VALUES(#{p.value})",
            Map.of("value", "来源记录"));
    long copied =
        db.insert(
            "INSERT copied_target(value) OUTPUT INSERTED.id SELECT value FROM insert_target"
                + " WHERE id=#{p.id}",
            Map.of("id", original));
    assertTrue(copied >= 101);
    assertEquals(
        "来源记录",
        db.need("SELECT value FROM copied_target WHERE id=#{p.id}", Map.of("id", copied))
            .get("value"));
  }

  @Test
  void identicalInsertParametersStillExecuteTwiceWithinOneSqlSession() {
    String insert = "INSERT insert_target(value) OUTPUT INSERTED.id VALUES(#{p.value})";
    var values = Map.<String, Object>of("value", "重复参数不是重复命令");
    long first = db.insert(insert, values), second = db.insert(insert, values);
    assertNotEquals(first, second);
    assertEquals(
        2L,
        Db.id(
            db.need("SELECT COUNT_BIG(*) count FROM insert_target WHERE value=#{p.value}", values),
            "count"));
  }

  @Test
  void searchMapperActuallyCancelsSlowSqlAndLeavesConnectionUsable() {
    long started = System.nanoTime();
    var error = assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,
        () -> db.search("WAITFOR DELAY '00:00:12'; SELECT 1 value", Map.of()));
    Throwable cause = error;
    while (cause.getCause() != null) cause = cause.getCause();
    assertTrue(cause instanceof SQLException, "a real JDBC timeout is required");
    long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed >= 7000 && elapsed < 11500, "8-second mapper timeout was not applied: " + elapsed);
    assertEquals(1, db.need("SELECT 1 value", Map.of()).get("value"));
  }
}
