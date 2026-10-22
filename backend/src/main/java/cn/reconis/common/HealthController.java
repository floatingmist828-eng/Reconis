package cn.reconis.common;

import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  private final DataSource dataSource;
  private final String releaseId;
  private final String buildSourceSha256;

  public HealthController(DataSource dataSource) {
    this.dataSource = dataSource;
    var build = new Properties();
    try (var stream = HealthController.class.getResourceAsStream("/reconis-build.properties")) {
      if (stream != null) build.load(stream);
    } catch (java.io.IOException ignored) {
      // A source checkout can run without packaged release metadata.
    }
    releaseId = build.getProperty("releaseId", "development");
    buildSourceSha256 = build.getProperty("buildSourceSha256", "");
  }

  @GetMapping("/api/v1/health")
  ResponseEntity<?> health() {
    try (var connection = dataSource.getConnection(); var query = connection.createStatement()) {
      query.setQueryTimeout(2);
      try (var result = query.executeQuery("SELECT 1")) {
        if (result.next() && result.getInt(1) == 1) {
          return ResponseEntity.ok(Api.ok(status("UP")));
        }
      }
    } catch (SQLException e) {
      // Public readiness responses must not expose connection strings or database details.
    }
    return ResponseEntity.status(503)
        .body(Api.ok(status("DOWN")));
  }

  private Map<String, String> status(String state) {
    return Map.of("application", "Reconis", "status", state,
        "releaseId", releaseId, "buildSourceSha256", buildSourceSha256);
  }
}
