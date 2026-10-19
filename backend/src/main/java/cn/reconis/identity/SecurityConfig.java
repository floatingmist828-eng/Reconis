package cn.reconis.identity;

import cn.reconis.common.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService accounts(Db db) {
    return name -> {
      var user =
          db.one("SELECT * FROM app_user WHERE username=#{p.name}", Api.params("name", name));
      if (user == null)
        throw new org.springframework.security.core.userdetails.UsernameNotFoundException(name);
      return org.springframework.security.core.userdetails.User.withUsername(name)
          .password(user.get("passwordHash").toString())
          .roles(user.get("platformRole").toString())
          .disabled(!"ACTIVE".equals(user.get("status")))
          .build();
    };
  }

  @Bean
  BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(11);
  }

  @Bean
  SecurityFilterChain security(HttpSecurity h, RequestAuditFilter audit) throws Exception {
    h.csrf(
            c ->
                c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/api/v1/callbacks/alipay"))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/v1/auth/**", "/api/v1/health", "/api/v1/callbacks/alipay")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> {
                          r.setStatus(401);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter().write("{\"message\":\"请先登录\"}");
                        })
                    .accessDeniedHandler(
                        (q, r, x) -> {
                          r.setStatus(403);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter().write("{\"message\":\"权限不足或安全令牌已过期，请刷新\"}");
                        }))
        .addFilterAfter(audit, AnonymousAuthenticationFilter.class);
    return h.build();
  }
}
