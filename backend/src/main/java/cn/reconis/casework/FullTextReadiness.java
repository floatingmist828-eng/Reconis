package cn.reconis.casework;

import cn.reconis.common.Api;
import cn.reconis.common.RequestContext;
import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.*;
import org.springframework.stereotype.Component;

/** Load Chinese full-text resources off the request thread, without granting admin privileges. */
@Component
public class FullTextReadiness {
  private final DataSource source;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon().name("reconis-fulltext-warmup").factory());
  private final AtomicBoolean probing = new AtomicBoolean();
  private volatile boolean ready;
  private volatile String state = "WARMING", checkedAt = "";
  private volatile long durationMs;

  public FullTextReadiness(DataSource source) { this.source = source; }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    worker.scheduleWithFixedDelay(() -> { if (!ready) warmup(); }, 0, 30, TimeUnit.SECONDS);
  }

  void warmup() {
    if (!probing.compareAndSet(false, true)) return;
    long start = System.nanoTime();
    try (var connection = source.getConnection(); var query = connection.createStatement()) {
      // This SELECT exercises the same Chinese word breaker and thesaurus as user queries.
      // It must not run in a user's transaction or change server-wide SQL permissions.
      query.setQueryTimeout(20);
      try (var rows = query.executeQuery("SELECT COUNT_BIG(*) FROM FREETEXTTABLE("
          + "dbo.exception_task,(title,description,analysis_text,resolution_text),"
          + "N'支付 金额 退款',LANGUAGE 2052) ft")) {
        if (!rows.next()) throw new SQLException("Full-text probe returned no result");
      }
      ready = true;
      state = "READY";
    } catch (SQLException | RuntimeException e) {
      ready = false;
      state = "UNAVAILABLE";
      LoggerFactory.getLogger(getClass()).warn("全文检索预热未完成，errorType={}", e.getClass().getSimpleName());
    } finally {
      durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      checkedAt = Instant.now().toString();
      probing.set(false);
      LoggerFactory.getLogger(getClass()).info("全文检索预热 state={}, durationMs={}", state, durationMs);
    }
  }

  public Map<String, Object> status() {
    return Map.of("state", state, "ready", ready, "checkedAt", checkedAt,
        "durationMs", durationMs, "message", ready ? "全文检索已就绪" : "全文检索正在准备，请稍后重试");
  }

  public <T> T query(Supplier<T> operation) {
    if (!ready) throw Api.fail(503, "全文检索正在准备，请稍后重试");
    long start = System.nanoTime();
    try {
      return operation.get();
    } catch (DataAccessException e) {
      Throwable cause = e.getMostSpecificCause();
      if (!(e instanceof QueryTimeoutException) && !(cause instanceof SQLTimeoutException)
          && !(e instanceof DataAccessResourceFailureException)
          && !(e instanceof TransientDataAccessResourceException)) throw e;
      ready = false;
      state = "UNAVAILABLE";
      LoggerFactory.getLogger(getClass()).warn("全文检索暂不可用，requestId={}, errorType={}",
          RequestContext.requestId(), cause.getClass().getSimpleName());
      worker.execute(() -> { if (!ready) warmup(); });
      throw Api.fail(503, "全文检索暂时不可用，正在恢复，请稍后重试");
    } finally {
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      if (elapsed >= 1000) LoggerFactory.getLogger(getClass()).warn(
          "全文检索耗时，requestId={}, durationMs={}", RequestContext.requestId(), elapsed);
    }
  }

  @PreDestroy
  public void close() { worker.shutdownNow(); }
}
