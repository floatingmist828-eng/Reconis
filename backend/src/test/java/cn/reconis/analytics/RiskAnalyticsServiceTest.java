package cn.reconis.analytics;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskAnalyticsServiceTest {
  @Test
  void previousPeriodIsAdjacentEqualLengthAndPreservesScopeAndCutoff() {
    var filters = Map.of("start", "2024-03-01", "end", "2024-03-03", "merchantId", "9007199254740993", "storeId", "42", "asOf", "2026-10-21T08:00:00Z", "minCent", "1000");
    var previous = RiskAnalyticsService.previousPeriod(filters);
    assertEquals("2024-02-27", previous.get("start"));
    assertEquals("2024-02-29", previous.get("end"));
    for (String key : java.util.List.of("merchantId", "storeId", "asOf", "minCent")) assertEquals(filters.get(key), previous.get(key));
    assertEquals("2024-03-01", filters.get("start"));
  }
}
