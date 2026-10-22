package cn.reconis.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class HealthControllerTest {
  @Test
  void reportsReadyOnlyAfterDatabaseRespondsAndClosesResources() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var query = mock(Statement.class);
    var result = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(query);
    when(query.executeQuery("SELECT 1")).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getInt(1)).thenReturn(1);
    assertEquals(200, new HealthController(source).health().getStatusCode().value());
    verify(query).setQueryTimeout(2);
    verify(result).close();
    verify(query).close();
    verify(connection).close();
  }

  @Test
  void databaseFailureReturnsUnavailableWithoutSensitiveDetails() throws Exception {
    var source = mock(DataSource.class);
    when(source.getConnection()).thenThrow(new SQLException("secret database credentials"));
    var response = new HealthController(source).health();
    assertEquals(503, response.getStatusCode().value());
    assertTrue(response.getBody().toString().contains("DOWN"));
    assertFalse(response.getBody().toString().contains("secret"));
  }
}
