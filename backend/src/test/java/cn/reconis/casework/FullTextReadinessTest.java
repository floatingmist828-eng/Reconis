package cn.reconis.casework;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.server.ResponseStatusException;

class FullTextReadinessTest {
  DataSource source;
  FullTextReadiness readiness;
  @BeforeEach void setup() { source = mock(DataSource.class); readiness = new FullTextReadiness(source); }
  @AfterEach void close() { readiness.close(); }

  @Test void coldSearchReturnsUnavailableWithoutRunningUserQuery() {
    var operation = mock(java.util.function.Supplier.class);
    assertEquals(503, assertThrows(ResponseStatusException.class, () -> readiness.query(operation)).getStatusCode().value());
    verifyNoInteractions(operation, source);
  }

  @Test void successfulChineseProbeEnablesSearchUsingOnlySelect() throws Exception {
    var connection = mock(Connection.class);
    var statement = mock(Statement.class);
    var result = mock(ResultSet.class);
    doReturn(connection).when(source).getConnection();
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery(anyString())).thenReturn(result);
    when(result.next()).thenReturn(true);
    readiness.warmup();
    assertEquals(true, readiness.status().get("ready"));
    assertEquals("result", readiness.query(() -> "result"));
    verify(statement).setQueryTimeout(20);
    verify(statement).executeQuery(contains("LANGUAGE 2052"));
    verify(connection).close();
  }

  @Test void failedProbeDoesNotClaimReadyAndCanRecover() throws Exception {
    when(source.getConnection()).thenThrow(new SQLTimeoutException("private database details"));
    readiness.warmup();
    assertEquals(false, readiness.status().get("ready"));
    assertFalse(readiness.status().toString().contains("private database details"));
    successfulChineseProbeEnablesSearchUsingOnlySelect();
  }

  @Test void timeoutReturns503InsteadOfFalseEmptyResults() throws Exception {
    successfulChineseProbeEnablesSearchUsingOnlySelect();
    var error = assertThrows(ResponseStatusException.class, () -> readiness.query(() -> {
      throw new QueryTimeoutException("query expired");
    }));
    assertEquals(503, error.getStatusCode().value());
    assertTrue(error.getReason().contains("正在恢复"));
  }
}
