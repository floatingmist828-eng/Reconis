package cn.reconis.support;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** An owned, disposable SQL database; no development database is modified by these tests. */
public final class SqlSandbox implements AutoCloseable {
  public final String name = "ReconisResilienceTest_" + UUID.randomUUID().toString().replace("-", "");
  private final Properties properties = new Properties();
  private final Connection admin;
  public final Connection db;

  public SqlSandbox() throws Exception {
    properties.setProperty("user", System.getenv("RECONIS_SQL_TEST_USER"));
    properties.setProperty("password", System.getenv("RECONIS_SQL_TEST_PASSWORD"));
    admin = DriverManager.getConnection(System.getenv("RECONIS_SQL_TEST_URL"), properties);
    exec(admin, "CREATE DATABASE [" + name + "]");
    exec(admin, "ALTER DATABASE [" + name + "] SET ALLOW_SNAPSHOT_ISOLATION ON");
    db = connect();
    try {
      for (String file : List.of("001_schema.sql", "002_constraints_indexes.sql",
          "003_views_functions_procedures.sql", "004_triggers_fulltext.sql")) apply(Path.of("..", "database", file));
      try (var migrations = Files.list(Path.of("..", "database", "migrations"))) {
        for (Path file : migrations.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) apply(file);
      }
    } catch (Exception e) { close(); throw e; }
  }

  public Connection connect() throws SQLException {
    var connection = DriverManager.getConnection(System.getenv("RECONIS_SQL_TEST_URL"), properties);
    connection.setCatalog(name);
    return connection;
  }

  private void apply(Path file) throws Exception {
    for (String batch : Files.readString(file).split("(?im)^GO\\s*$")) if (!batch.isBlank()) exec(db, batch);
  }

  public static PreparedStatement prepare(Connection c, String sql, Object... parameters) throws SQLException {
    var statement = c.prepareStatement(sql);
    statement.setQueryTimeout(30);
    for (int n = 0; n < parameters.length; n++) statement.setObject(n + 1, parameters[n]);
    return statement;
  }

  public static void exec(Connection c, String sql, Object... values) throws SQLException {
    try (var query = prepare(c, sql, values)) {
      boolean rows = query.execute();
      while (rows || query.getUpdateCount() != -1) rows = query.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }
  }

  public static Object scalar(Connection c, String sql, Object... values) throws SQLException {
    try (var query = prepare(c, sql, values); var rows = query.executeQuery()) {
      return rows.next() ? rows.getObject(1) : null;
    }
  }

  public static long insert(Connection c, String sql, Object... values) throws SQLException {
    try (var query = prepare(c, "SET NOCOUNT ON; " + sql + "; SELECT CONVERT(BIGINT,SCOPE_IDENTITY())", values)) {
      boolean rows = query.execute();
      while (!rows && query.getUpdateCount() != -1) rows = query.getMoreResults();
      try (var result = query.getResultSet()) {
        if (result == null || !result.next()) throw new SQLException("Insert returned no identity");
        return result.getLong(1);
      }
    }
  }

  @Override public void close() throws SQLException {
    if (db != null) db.close();
    try {
      if (!name.matches("ReconisResilienceTest_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected test database");
      exec(admin, "ALTER DATABASE [" + name + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
      exec(admin, "DROP DATABASE [" + name + "]");
    } finally { admin.close(); }
  }
}
