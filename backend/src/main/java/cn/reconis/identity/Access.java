package cn.reconis.identity;

import cn.reconis.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Access {
  private final Db db;

  public Access(Db db) {
    this.db = db;
  }

  public RequestContext.Actor actor() {
    var a = RequestContext.actor();
    if (a == null) throw Api.fail(401, "请先登录");
    return a;
  }

  public boolean platform() {
    return Set.of("PLATFORM_FINANCE", "AUDITOR").contains(actor().role());
  }

  public void role(String... roles) {
    if (!Set.of(roles).contains(actor().role())) throw Api.fail(403, "当前角色无此操作权限");
  }

  public void merchant(long id, String... roles) {
    if (actor().role().equals("PLATFORM_FINANCE")) {
      RequestContext.merchant(id);
      return;
    }
    var p = Api.params("merchant", id, "user", actor().id());
    var m =
        db.one(
            "SELECT role_code FROM merchant_member WHERE merchant_id=#{p.merchant} AND"
                + " user_id=#{p.user} AND status='ACTIVE'",
            p);
    if (m == null || (roles.length > 0 && !Set.of(roles).contains(m.get("roleCode"))))
      throw Api.fail(403, "无权访问该商户或执行此操作");
    if (actor().role().equals("AUDITOR") || actor().role().equals("ADMIN"))
      throw Api.fail(403, "此平台身份不办理交易");
    RequestContext.merchant(id);
  }

  public String scope(String merchantColumn, Map<String, Object> p) {
    p.put("actor", actor().id());
    return platform()
        ? "1=1"
        : merchantColumn
            + " IN (SELECT merchant_id FROM merchant_member WHERE user_id=#{p.actor} AND"
            + " status='ACTIVE')";
  }

  public String ordersScope(String alias, Map<String, Object> p) {
    return "(" + scope(alias + ".merchant_id", p) + " OR " + alias + ".consumer_id=#{p.actor})";
  }

  public Map<String, Object> order(long id, boolean write) {
    var p = Api.params("id", id);
    var row = db.need("SELECT * FROM v_order o WHERE o.id=#{p.id} AND " + ordersScope("o", p), p);
    if (write) merchant(Db.id(row, "merchantId"), "OWNER", "CASHIER", "FINANCE");
    return row;
  }

  public Map<String, Object> payment(long id, boolean write) {
    var r =
        db.need(
            "SELECT p.*,o.merchant_id,o.consumer_id,o.amount_cent,o.subject,o.status order_status"
                + " FROM payment_attempt p JOIN v_order o ON o.id=p.order_id WHERE p.id=#{p.id}",
            Api.params("id", id));
    order(Db.id(r, "orderId"), write);
    return r;
  }

  public Map<String, Object> batch(long id) {
    var p = Api.params("id", id);
    return db.need(
        "SELECT * FROM reconciliation_batch b WHERE b.id=#{p.id} AND " + scope("b.merchant_id", p),
        p);
  }

  public Map<String, Object> caseRow(long id) {
    var p = Api.params("id", id);
    return db.need(
        "SELECT *,dbo.fn_case_overdue_days(due_at,closed_at,SYSUTCDATETIME()) overdue_days FROM"
            + " v_case c WHERE c.id=#{p.id} AND "
            + scope("c.merchant_id", p),
        p);
  }

  public void caseWrite(Map<String, Object> row, String... roles) {
    if (row.get("merchantId") == null) {
      role("PLATFORM_FINANCE");
      return;
    }
    merchant(Db.id(row, "merchantId"), roles);
  }
}
