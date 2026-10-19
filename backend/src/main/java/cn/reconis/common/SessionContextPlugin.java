package cn.reconis.common;

import java.sql.*;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.springframework.stereotype.Component;

@Component
@Intercepts({
  @Signature(
      type = Executor.class,
      method = "update",
      args = {MappedStatement.class, Object.class}),
  @Signature(
      type = Executor.class,
      method = "query",
      args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
  @Signature(
      type = Executor.class,
      method = "query",
      args = {
        MappedStatement.class,
        Object.class,
        RowBounds.class,
        ResultHandler.class,
        CacheKey.class,
        BoundSql.class
      })
})
public class SessionContextPlugin implements Interceptor {
  public Object intercept(Invocation i) throws Throwable {
    Connection c = ((Executor) i.getTarget()).getTransaction().getConnection();
    var a = RequestContext.actor();
    try {
      set(c, "user_id", a == null ? null : a.id());
      set(c, "role", a == null ? "SYSTEM" : a.role());
      set(c, "merchant_id", RequestContext.merchant());
      set(c, "request_id", RequestContext.requestId());
      return i.proceed();
    } finally {
      set(c, "user_id", null);
      set(c, "role", null);
      set(c, "merchant_id", null);
      set(c, "request_id", null);
    }
  }

  private void set(Connection c, String key, Object value) throws SQLException {
    try (var s = c.prepareStatement("EXEC sys.sp_set_session_context @key=?, @value=?")) {
      s.setString(1, key);
      s.setObject(2, value);
      s.execute();
    }
  }
}
