package cn.reconis.common;

import java.util.*;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Component;

@Component
public class Db {
  @Mapper
  public interface SqlMapper {
    @SelectProvider(type = Provider.class, method = "sql")
    List<Map<String, Object>> query(@Param("sql") String sql, @Param("p") Map<String, Object> p);

    @UpdateProvider(type = Provider.class, method = "sql")
    int execute(@Param("sql") String sql, @Param("p") Map<String, Object> p);
  }

  public static class Provider {
    public String sql(Map<String, Object> p) {
      return p.get("sql").toString();
    }
  }

  private final SqlMapper mapper;

  public Db(SqlMapper mapper) {
    this.mapper = mapper;
  }

  public List<Map<String, Object>> list(String sql, Map<String, Object> p) {
    return mapper.query(sql, p).stream().map(Db::camel).toList();
  }

  public Map<String, Object> one(String sql, Map<String, Object> p) {
    var rows = list(sql, p);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Map<String, Object> need(String sql, Map<String, Object> p) {
    var row = one(sql, p);
    if (row == null) throw Api.fail(404, "记录不存在或不可访问");
    return row;
  }

  public int exec(String sql, Map<String, Object> p) {
    return mapper.execute(sql, p);
  }

  public long insert(String sql, Map<String, Object> p) {
    return ((Number) need(sql, p).get("id")).longValue();
  }

  public static long id(Map<String, Object> row, String k) {
    return Long.parseLong(row.get(k).toString());
  }

  public Map<String, Object> page(
      String from, String where, String sort, Map<String, Object> p, int page, int size) {
    page = Math.max(1, page);
    size = Math.clamp(size, 1, 100);
    p = new HashMap<>(p);
    p.put("offset", (page - 1) * size);
    p.put("size", size);
    var total = need("SELECT COUNT_BIG(*) total " + from + " WHERE " + where, p).get("total");
    return Map.of(
        "items",
        list(
            "SELECT * "
                + from
                + " WHERE "
                + where
                + " ORDER BY "
                + sort
                + " OFFSET #{p.offset} ROWS FETCH NEXT #{p.size} ROWS ONLY",
            p),
        "total",
        total,
        "page",
        page,
        "pageSize",
        size);
  }

  private static Map<String, Object> camel(Map<String, Object> row) {
    Map<String, Object> m = new LinkedHashMap<>();
    row.forEach(
        (k, v) -> {
          StringBuilder b = new StringBuilder();
          boolean upper = false;
          for (char c : k.toCharArray()) {
            if (c == '_') upper = true;
            else {
              b.append(upper ? Character.toUpperCase(c) : c);
              upper = false;
            }
          }
          m.put(b.toString(), v);
        });
    return m;
  }
}
