package cn.reconis.casework;

import cn.reconis.common.Api;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{id}/dossier")
public class CaseEvidenceController {
  private final CaseEvidenceService service;

  public CaseEvidenceController(CaseEvidenceService service) { this.service = service; }

  @GetMapping
  Object inspect(@PathVariable long id) { return Api.ok(service.inspect(id)); }

  @GetMapping("/archive")
  ResponseEntity<byte[]> archive(@PathVariable long id) {
    var result = service.archive(id);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
        .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
        .header("X-Content-SHA256", result.sha256())
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(result.filename(), StandardCharsets.UTF_8).build().toString())
        .body(result.bytes());
  }
}
