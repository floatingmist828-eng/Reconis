package cn.reconis.analytics;

import cn.reconis.common.Api;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics/risk")
public class RiskAnalyticsController {
  private final RiskAnalyticsService risk;

  public RiskAnalyticsController(RiskAnalyticsService risk) { this.risk = risk; }

  @GetMapping
  Object overview(@RequestParam Map<String, String> filters) { return Api.ok(risk.overview(filters)); }

  @GetMapping("/details")
  Object details(@RequestParam Map<String, String> filters) { return Api.ok(risk.details(filters)); }
}
