package cn.reconis.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.Db;
import cn.reconis.common.RequestContext;
import cn.reconis.identity.Access;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnalyticsServiceTest {
  private Db db;
  private Access access;
  private AnalyticsService service;
  private final Map<String, String> filters =
      Map.of("start", "2026-10-15", "end", "2026-10-21", "asOf", "2026-10-21T08:00:00Z");

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    access = mock(Access.class);
    when(access.actor())
        .thenReturn(new RequestContext.Actor(1L, "finance", "财务", "PLATFORM_FINANCE"));
    when(access.scope(anyString(), anyMap())).thenReturn("1=1");
    service = new AnalyticsService(db, access);
  }

  @Test
  void shanghaiDaysAndCutoffAreSharedByAggregatesAndDetails() {
    var filter = service.filter(filters);
    assertEquals(LocalDateTime.parse("2026-10-14T16:00:00"), filter.params().get("start"));
    assertEquals(LocalDateTime.parse("2026-10-21T16:00:00"), filter.params().get("end"));
    assertEquals(LocalDateTime.parse("2026-10-21T08:00:00"), filter.params().get("asOf"));
    assertTrue(filter.where().contains("f.recorded_at<=#{p.asOf}"));
  }

  @Test
  void invalidRangesAreRejectedBeforeDatabaseReads() {
    assertThrows(
        ResponseStatusException.class,
        () -> service.filter(Map.of("start", "2026-10-22", "end", "2026-10-21")));
    assertThrows(
        ResponseStatusException.class,
        () -> service.filter(Map.of("minCent", "200", "maxCent", "100")));
    assertThrows(ResponseStatusException.class, () -> service.filter(Map.of("minCent", "-1")));
    verifyNoInteractions(db);
  }

  @Test
  void selectedStoreMustBelongToSelectedMerchant() {
    when(db.need(anyString(), anyMap())).thenReturn(Map.of("id", 42L, "merchantId", 99L));
    var input = new HashMap<>(filters);
    input.put("merchantId", "12");
    input.put("storeId", "42");
    assertThrows(ResponseStatusException.class, () -> service.filter(input));
    verify(access).readMerchant(12);
    verify(access, never()).readMerchant(99);
  }

  @Test
  void onlineDetailsRetainRealTotalAndDelegateOnlyRequestedPage() {
    var input = new HashMap<>(filters);
    input.put("page", "501");
    input.put("pageSize", "20");
    when(db.page(anyString(), anyString(), anyString(), anyMap(), eq(501), eq(20)))
        .thenReturn(
            Map.of(
                "items", List.of(Map.of("id", 1L)), "total", 25001L, "page", 501, "pageSize", 20));
    var result = service.details(input);
    assertEquals(25001L, result.get("total"));
    assertEquals(501, result.get("page"));
    verify(db, never()).list(anyString(), anyMap());
  }

  @Test
  void oversizedExportIsRejectedBeforeLoadingRows() {
    when(db.need(anyString(), anyMap())).thenReturn(Map.of("total", 100001L));
    var failure = assertThrows(ResponseStatusException.class, () -> service.exportDetails(filters));
    assertEquals(422, failure.getStatusCode().value());
    assertTrue(failure.getReason().contains("100001"));
    verify(db, never()).list(anyString(), anyMap());
  }

  @Test
  void exportRaceCannotSilentlyReturnTruncatedFile() {
    when(db.need(anyString(), anyMap())).thenReturn(Map.of("total", 100000L));
    when(db.list(anyString(), anyMap())).thenReturn(Collections.nCopies(100001, Map.of("id", 1L)));
    assertThrows(ResponseStatusException.class, () -> service.exportDetails(filters));
  }
}
