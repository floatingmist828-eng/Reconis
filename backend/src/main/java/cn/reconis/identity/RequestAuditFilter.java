package cn.reconis.identity;

import cn.reconis.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestAuditFilter extends OncePerRequestFilter {
  private final Db db;

  public RequestAuditFilter(Db db) {
    this.db = db;
  }

  @Bean
  FilterRegistrationBean<RequestAuditFilter> disableAutoRegistration() {
    var r = new FilterRegistrationBean<>(this);
    r.setEnabled(false);
    return r;
  }

  protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain chain)
      throws ServletException, IOException {
    if (!q.getRequestURI().startsWith("/api/")
        || q.getRequestURI().equals("/api/v1/health")
        || q.getRequestURI().equals("/api/v1/auth/csrf")) {
      chain.doFilter(q, r);
      return;
    }
    String req = UUID.randomUUID().toString();
    r.setHeader("X-Request-Id", req);
    RequestContext.Actor actor = null;
    try {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser")) {
        var u =
            db.one(
                "SELECT id,username,display_name,platform_role,status FROM app_user WHERE"
                    + " username=#{p.name}",
                Api.params("name", auth.getName()));
        if (u == null || !"ACTIVE".equals(u.get("status"))) {
          SecurityContextHolder.clearContext();
          q.getSession().invalidate();
          r.setStatus(401);
          return;
        }
        actor =
            new RequestContext.Actor(
                Db.id(u, "id"),
                u.get("username").toString(),
                u.get("displayName").toString(),
                u.get("platformRole").toString());
      }
      RequestContext.set(actor, req);
      chain.doFilter(q, r);
    } finally {
      try {
        db.exec(
            "INSERT"
                + " audit_log(event_category,actor_user_id,scope_merchant_id,action,result,summary,request_id)"
                + " VALUES('ACCESS',#{p.user},#{p.merchant},#{p.action},#{p.result},#{p.summary},#{p.req})",
            Api.params(
                "user",
                actor == null ? null : actor.id(),
                "merchant",
                RequestContext.auditMerchant(),
                "action",
                q.getMethod() + " " + q.getRequestURI(),
                "result",
                r.getStatus() < 400 ? "SUCCESS" : "FAILED",
                "summary",
                "HTTP " + r.getStatus(),
                "req",
                req));
      } catch (Exception ignored) {
        logger.warn("访问审计写入失败，requestId=" + req);
      }
      RequestContext.clear();
    }
  }
}
