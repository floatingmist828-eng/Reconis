package cn.reconis.reconciliation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReconciliationControllerTest {
  @Test
  void runsUnassignedBatchUsingPlatformPermissionWithoutConvertingNullMerchant() {
    Db db = mock(Db.class);
    Access access = mock(Access.class);
    when(access.batch(7))
        .thenReturn(
            Api.params(
                "id",
                7L,
                "merchantId",
                null,
                "scopeType",
                "PLATFORM_UNASSIGNED",
                "status",
                "READY"));
    var controller = new ReconciliationController(db, access, mock(ImportService.class));
    assertDoesNotThrow(() -> controller.run(7));
    verify(access).role("PLATFORM_FINANCE");
    verify(access, never()).merchant(anyLong(), any(String[].class));
    verify(db).exec(startsWith("EXEC usp_run_reconciliation"), anyMap());
  }

  @Test
  void doesNotCreateReplayBeforeOriginalBatchCompletes() {
    Db db = mock(Db.class);
    Access access = mock(Access.class);
    when(access.batch(7))
        .thenReturn(
            Api.params(
                "id",
                7L,
                "merchantId",
                null,
                "scopeType",
                "PLATFORM_UNASSIGNED",
                "status",
                "READY"));
    var controller = new ReconciliationController(db, access, mock(ImportService.class));
    assertEquals(
        409,
        assertThrows(ResponseStatusException.class, () -> controller.rerun(7))
            .getStatusCode()
            .value());
    verify(db, never()).insert(anyString(), anyMap());
  }
}
