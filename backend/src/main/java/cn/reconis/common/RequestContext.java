package cn.reconis.common;

import java.util.*;

public final class RequestContext {
  public record Actor(Long id, String username, String name, String role) {}

  private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();
  private static final ThreadLocal<String> REQUEST = new ThreadLocal<>();
  private static final ThreadLocal<Long> MERCHANT = new ThreadLocal<>();
  private static final ThreadLocal<Set<Long>> AUDIT_SCOPES = ThreadLocal.withInitial(HashSet::new);

  public static Actor actor() {
    return ACTOR.get();
  }

  public static String requestId() {
    return Optional.ofNullable(REQUEST.get()).orElse("system");
  }

  public static Long merchant() {
    return MERCHANT.get();
  }

  public static void merchant(long id) {
    MERCHANT.set(id);
    AUDIT_SCOPES.get().add(id);
  }

  public static Long auditMerchant() {
    var scopes = AUDIT_SCOPES.get();
    return scopes.size() == 1 ? scopes.iterator().next() : null;
  }

  public static void set(Actor actor, String request) {
    ACTOR.set(actor);
    REQUEST.set(request);
    MERCHANT.remove();
    AUDIT_SCOPES.remove();
  }

  public static void clear() {
    ACTOR.remove();
    REQUEST.remove();
    MERCHANT.remove();
    AUDIT_SCOPES.remove();
  }
}
