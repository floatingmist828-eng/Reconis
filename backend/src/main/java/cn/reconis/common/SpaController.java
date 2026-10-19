package cn.reconis.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
  @GetMapping({
    "/",
    "/login",
    "/merchants",
    "/orders",
    "/orders/{id}",
    "/refunds",
    "/reconciliation",
    "/cases",
    "/cases/{id}",
    "/search",
    "/analytics",
    "/audit",
    "/users",
    "/integration"
  })
  String app() {
    return "forward:/index.html";
  }
}
