package cn.reconis.common;

import java.util.*;

public final class RequestContext {
  public record Actor(Long id, String username, String name, String role) {}

  private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();
  private static final ThreadLocal<String> REQUEST = new ThreadLocal<>();
  private static final ThreadLocal<Long> MERCHANT = new ThreadLocal<>();

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
  }

  public static void set(Actor actor, String request) {
    ACTOR.set(actor);
    REQUEST.set(request);
    MERCHANT.remove();
  }

  public static void clear() {
    ACTOR.remove();
    REQUEST.remove();
    MERCHANT.remove();
  }
}
