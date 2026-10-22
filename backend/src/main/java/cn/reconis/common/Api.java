package cn.reconis.common;

import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

public final class Api {
  public static Map<String, Object> ok(Object data) {
    return Map.of("data", normalize(data), "requestId", RequestContext.requestId());
  }

  public static Object normalize(Object v) {
    if (v == null) return null;
    if (v instanceof Long || v instanceof java.math.BigInteger) return v.toString();
    if (v instanceof java.sql.Timestamp t)
      return t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (v instanceof byte[] b) return HexFormat.of().formatHex(b);
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> n = new LinkedHashMap<>();
      m.forEach((k, x) -> n.put(k.toString(), normalize(x)));
      return n;
    }
    if (v instanceof Collection<?> a) return a.stream().map(Api::normalize).toList();
    return v;
  }

  public static ResponseStatusException fail(int status, String message) {
    return new ResponseStatusException(HttpStatusCode.valueOf(status), message);
  }

  public static String text(Map<String, ?> p, String k) {
    Object v = p.get(k);
    return v == null ? "" : v.toString().strip();
  }

  public static String required(Map<String, ?> p, String k) {
    String v = text(p, k);
    if (v.isBlank()) throw fail(422, "缺少字段：" + k);
    return v;
  }

  public static long number(Map<String, ?> p, String k) {
    try {
      return Long.parseLong(required(p, k));
    } catch (NumberFormatException e) {
      throw fail(422, k + " 必须是整数");
    }
  }

  public static String uuid(Map<String, ?> p) {
    try {
      return UUID.fromString(required(p, "clientRequestId")).toString();
    } catch (IllegalArgumentException e) {
      throw fail(422, "clientRequestId 必须为 UUID");
    }
  }

  public static String no(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  public static LocalDateTime utc(String v) {
    try {
      return OffsetDateTime.parse(v).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    } catch (Exception e) {
      throw fail(422, "时间必须是带时区的 ISO8601 格式");
    }
  }

  public static Map<String, Object> params(Object... xs) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < xs.length; i += 2) m.put(xs[i].toString(), xs[i + 1]);
    return m;
  }

  @RestControllerAdvice
  public static class Errors {
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
      if (e instanceof org.springframework.web.ErrorResponse response) {
        return ResponseEntity.status(response.getStatusCode()).body(Map.of(
            "code", "INVALID_REQUEST", "message", "请求参数或访问方式不正确",
            "requestId", RequestContext.requestId()));
      }
      org.slf4j.LoggerFactory.getLogger(Errors.class)
          .error("请求处理失败，requestId={}", RequestContext.requestId(), e);
      return ResponseEntity.internalServerError().body(Map.of(
          "code", "INTERNAL_ERROR",
          "message", "请求处理失败，请稍后重试并提供请求编号",
          "requestId", RequestContext.requestId()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> known(ResponseStatusException e) {
      return ResponseEntity.status(e.getStatusCode())
          .body(
              Map.of(
                  "code",
                  "BUSINESS_ERROR",
                  "message",
                  Objects.toString(e.getReason(), "请求失败"),
                  "requestId",
                  RequestContext.requestId()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(DataAccessException e) {
      Throwable t = e.getMostSpecificCause();
      boolean conflict =
          e instanceof org.springframework.dao.DataIntegrityViolationException
              || e instanceof org.springframework.dao.ConcurrencyFailureException
              || t instanceof java.sql.SQLException sql && sql.getErrorCode() >= 51000;
      boolean unavailable =
          e instanceof org.springframework.dao.DataAccessResourceFailureException
              || e instanceof org.springframework.dao.TransientDataAccessResourceException;
      if (!conflict)
        org.slf4j.LoggerFactory.getLogger(Errors.class)
            .error("数据库操作失败，requestId={}", RequestContext.requestId(), e);
      String msg =
          t instanceof java.sql.SQLException s && s.getErrorCode() >= 51000
              ? s.getMessage()
              : conflict
                  ? "数据已变更或不符合业务约束，请刷新后重试"
                  : unavailable ? "数据库暂时不可用，请稍后重试" : "数据库操作失败，请联系管理员并提供请求编号";
      return ResponseEntity.status(conflict ? 409 : unavailable ? 503 : 500)
          .body(
              Map.of(
                  "code",
                  conflict
                      ? "DATA_CONFLICT"
                      : unavailable ? "DATABASE_UNAVAILABLE" : "DATABASE_ERROR",
                  "message",
                  msg,
                  "requestId",
                  RequestContext.requestId()));
    }

    @ExceptionHandler({
      IllegalArgumentException.class,
      org.springframework.web.multipart.MaxUploadSizeExceededException.class
    })
    ResponseEntity<?> invalid(Exception e) {
      return ResponseEntity.unprocessableEntity()
          .body(
              Map.of(
                  "code",
                  "INVALID_INPUT",
                  "message",
                  "输入格式不合法或文件超过20MB",
                  "requestId",
                  RequestContext.requestId()));
    }
  }
}
