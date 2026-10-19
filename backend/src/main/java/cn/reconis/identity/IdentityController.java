package cn.reconis.identity;

import cn.reconis.common.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
  private final Db db;
  private final Access access;
  private final BCryptPasswordEncoder encoder;

  public IdentityController(Db db, Access access, BCryptPasswordEncoder encoder) {
    this.db = db;
    this.access = access;
    this.encoder = encoder;
  }

  @GetMapping("/health")
  Object health() {
    return Api.ok(Map.of("application", "Reconis", "status", "UP"));
  }

  @GetMapping("/auth/csrf")
  Object csrf(CsrfToken t) {
    return Api.ok(Map.of("token", t.getToken(), "headerName", t.getHeaderName()));
  }

  @PostMapping("/auth/register")
  Object register(@RequestBody Map<String, Object> b) {
    String username = Api.required(b, "username"), password = Api.required(b, "password");
    if (!username.matches("[a-zA-Z0-9_]{3,32}") || password.length() < 10 || password.length() > 64)
      throw Api.fail(422, "用户名需3–32位字母数字下划线，密码需10–64位");
    db.exec(
        "INSERT app_user(username,password_hash,display_name)"
            + " VALUES(#{p.username},#{p.hash},#{p.name})",
        Api.params(
            "username",
            username,
            "hash",
            encoder.encode(password),
            "name",
            Api.required(b, "displayName")));
    return Api.ok(Map.of("status", "PENDING", "message", "注册成功，等待管理员审核"));
  }

  @PostMapping("/auth/login")
  Object login(@RequestBody Map<String, Object> b, HttpServletRequest q, HttpServletResponse r) {
    var u =
        db.one(
            "SELECT * FROM app_user WHERE username=#{p.name}",
            Api.params("name", Api.required(b, "username")));
    boolean valid =
        u != null && encoder.matches(Api.required(b, "password"), u.get("passwordHash").toString());
    db.exec(
        "INSERT audit_log(event_category,actor_user_id,action,result,summary,request_id)"
            + " VALUES('AUTH',#{p.user},'LOGIN',#{p.result},N'账号登录',#{p.req})",
        Api.params(
            "user",
            u == null ? null : u.get("id"),
            "result",
            valid ? "SUCCESS" : "FAILED",
            "req",
            RequestContext.requestId()));
    if (!valid) throw Api.fail(401, "用户名或密码不正确");
    if (!"ACTIVE".equals(u.get("status"))) throw Api.fail(403, "账号尚未审核通过或已停用");
    q.getSession(true);
    q.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            u.get("username"),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + u.get("platformRole")))));
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, q, r);
    return Api.ok(
        Map.of(
            "username",
            u.get("username"),
            "displayName",
            u.get("displayName"),
            "platformRole",
            u.get("platformRole")));
  }

  @PostMapping("/auth/logout")
  Object logout(HttpServletRequest q) {
    if (q.getSession(false) != null) q.getSession().invalidate();
    SecurityContextHolder.clearContext();
    return Api.ok(Map.of("message", "已退出"));
  }

  @GetMapping("/auth/me")
  Object me() {
    var a = access.actor();
    return Api.ok(
        Map.of(
            "id",
            a.id(),
            "username",
            a.username(),
            "displayName",
            a.name(),
            "platformRole",
            a.role(),
            "memberships",
            db.list(
                "SELECT mm.*,m.name merchant_name FROM merchant_member mm JOIN merchant m ON"
                    + " m.id=mm.merchant_id WHERE mm.user_id=#{p.id} AND mm.status='ACTIVE'",
                Api.params("id", a.id()))));
  }

  @GetMapping("/admin/users")
  Object users(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "1") int page) {
    access.role("ADMIN");
    return Api.ok(
        db.page(
            "FROM (SELECT id,username,display_name,platform_role,status,created_at FROM app_user)"
                + " u",
            "(username LIKE #{p.q} OR display_name LIKE #{p.q})",
            "id DESC",
            Api.params("q", "%" + q + "%"),
            page,
            20));
  }

  @PostMapping("/admin/users/{id}/review")
  Object review(@PathVariable long id, @RequestBody Map<String, Object> b) {
    access.role("ADMIN");
    if (id == access.actor().id()) throw Api.fail(409, "不能修改自己的审核或平台角色");
    String role = Api.required(b, "platformRole"), status = Api.required(b, "status");
    if (!Set.of("MEMBER", "ADMIN", "PLATFORM_FINANCE", "AUDITOR").contains(role)
        || !Set.of("ACTIVE", "REJECTED", "DISABLED").contains(status))
      throw Api.fail(422, "角色或状态无效");
    db.exec(
        "UPDATE app_user SET"
            + " platform_role=#{p.role},status=#{p.status},reviewed_by=#{p.actor},reviewed_at=SYSUTCDATETIME(),review_reason=#{p.reason},updated_at=SYSUTCDATETIME()"
            + " WHERE id=#{p.id}",
        Api.params(
            "id",
            id,
            "role",
            role,
            "status",
            status,
            "actor",
            access.actor().id(),
            "reason",
            Api.required(b, "reason")));
    return Api.ok(Map.of("message", "审核已保存"));
  }
}
