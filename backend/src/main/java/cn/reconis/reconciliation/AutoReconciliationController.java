package cn.reconis.reconciliation;

import cn.reconis.common.Api;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reconciliation/automatic")
public class AutoReconciliationController {
  private final AutoReconciliationService service;
  public AutoReconciliationController(AutoReconciliationService service) { this.service=service; }
  @GetMapping Object list(@RequestParam(defaultValue="1") int page) { return Api.ok(service.list(page)); }
  @PostMapping Object enqueue(@RequestBody Map<String,Object> body) {
    return Api.ok(service.enqueue(Api.required(body,"start"),Api.required(body,"end"),Boolean.TRUE.equals(body.get("daily"))));
  }
  @GetMapping("/{id}") Object detail(@PathVariable long id) { return Api.ok(service.detail(id)); }
  @PostMapping("/{id}/retry") Object retry(@PathVariable long id) { service.retry(id); return Api.ok(Map.of("id",id)); }
  @PostMapping("/{id}/schedule") Object daily(@PathVariable long id,@RequestBody Map<String,Object> body) {
    service.daily(id,Boolean.TRUE.equals(body.get("enabled"))); return Api.ok(Map.of("id",id));
  }
  @GetMapping("/{id}/original") ResponseEntity<byte[]> original(@PathVariable long id) throws Exception {
    String format=Api.text(service.detail(id),"acquisitionFormat");
    if(!java.util.Set.of("xml","csv","zip").contains(format)) format="bin";
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=channel-bill-"+id+"."+format)
        .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store")
        .body(service.original(id));
  }
}
