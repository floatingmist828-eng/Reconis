package cn.reconis.order;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
  private final Db db;
  private final Access access;

  public OrderController(Db db, Access access) {
    this.db = db;
    this.access = access;
  }

  @GetMapping
  Object list(@RequestParam Map<String, String> filter) {
    var p = new HashMap<String, Object>(filter);
    p.put("q", "%" + filter.getOrDefault("q", "") + "%");
    p.put("status", filter.getOrDefault("status", ""));
    String where =
        access.ordersScope("o", p)
            + " AND (order_no LIKE #{p.q} OR subject LIKE #{p.q}) AND (#{p.status}='' OR"
            + " status=#{p.status})";
    if (!filter.getOrDefault("merchantId", "").isBlank())
      where += " AND merchant_id=#{p.merchantId}";
    if (!filter.getOrDefault("storeId", "").isBlank()) where += " AND store_id=#{p.storeId}";
    if (!filter.getOrDefault("minCent", "").isBlank()) where += " AND amount_cent>=#{p.minCent}";
    if (!filter.getOrDefault("maxCent", "").isBlank()) where += " AND amount_cent<=#{p.maxCent}";
    String sort =
        Map.of(
                "createdAt",
                "created_at DESC,id DESC",
                "amountAsc",
                "amount_cent,id",
                "amountDesc",
                "amount_cent DESC,id DESC")
            .getOrDefault(filter.getOrDefault("sort", "createdAt"), "created_at DESC,id DESC");
    return Api.ok(
        db.page(
            "FROM v_order o",
            where,
            sort,
            p,
            Integer.parseInt(filter.getOrDefault("page", "1")),
            20));
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable long id) {
    var row = access.order(id, false);
    var p = Api.params("id", id);
    row.put(
        "items", db.list("SELECT * FROM order_item WHERE order_id=#{p.id} ORDER BY line_no", p));
    row.put(
        "payments",
        db.list(
            "SELECT *,dbo.fn_refundable_amount(id) refundable_cent FROM payment_attempt WHERE"
                + " order_id=#{p.id} ORDER BY id DESC",
            p));
    row.put(
        "refunds",
        db.list(
            "SELECT r.* FROM refund_request r JOIN payment_attempt p ON p.id=r.payment_id WHERE"
                + " p.order_id=#{p.id} ORDER BY r.id DESC",
            p));
    row.put(
        "entries",
        db.list(
            "SELECT * FROM v_transaction_fact WHERE order_id=#{p.id} ORDER BY channel_occurred_at",
            p));
    row.put(
        "notifications",
        db.list(
            "SELECT n.id,n.signature_valid,n.handling_result,n.received_at,n.request_id FROM"
                + " payment_notification n JOIN payment_attempt p ON p.id=n.payment_id WHERE"
                + " p.order_id=#{p.id} ORDER BY n.id DESC",
            p));
    row.put(
        "results",
        db.list(
            "SELECT r.*,b.batch_no FROM reconciliation_result r JOIN reconciliation_batch b ON"
                + " b.id=r.batch_id LEFT JOIN transaction_entry e ON e.id=r.entry_id WHERE e.id"
                + " IN(SELECT id FROM v_transaction_fact WHERE order_id=#{p.id}) ORDER BY r.id"
                + " DESC",
            p));
    return Api.ok(row);
  }

  @PostMapping
  @Transactional
  public Object create(@RequestBody Map<String, Object> b) {
    String request = Api.uuid(b);
    var old =
        db.one(
            "SELECT * FROM business_order WHERE client_request_id=#{p.req}",
            Api.params("req", request));
    if (old != null) {
      access.order(Db.id(old, "id"), true);
      if (!Objects.equals(old.get("subject"), b.get("subject"))
          || Db.id(old, "storeId") != Api.number(b, "storeId")
          || !sameItems(Db.id(old, "id"), b)) throw Api.fail(409, "同一请求号的订单内容不一致");
      return Api.ok(old);
    }
    var s =
        db.need(
            "SELECT s.* FROM store s JOIN merchant m ON m.id=s.merchant_id WHERE s.id=#{p.id} AND"
                + " s.status='ACTIVE' AND m.status='ACTIVE'",
            Api.params("id", Api.number(b, "storeId")));
    access.merchant(Db.id(s, "merchantId"), "OWNER", "CASHIER");
    Object consumer = null;
    if (!Api.text(b, "consumerUsername").isBlank())
      consumer =
          db.need(
                  "SELECT id FROM app_user WHERE username=#{p.name} AND status='ACTIVE'",
                  Api.params("name", Api.text(b, "consumerUsername")))
              .get("id");
    long id =
        db.insert(
            "INSERT"
                + " business_order(order_no,store_id,consumer_id,created_by,client_request_id,subject,remark)"
                + " OUTPUT INSERTED.id"
                + " VALUES(#{p.no},#{p.store},#{p.consumer},#{p.actor},#{p.req},#{p.subject},#{p.remark})",
            Api.params(
                "no",
                Api.no("ORD"),
                "store",
                s.get("id"),
                "consumer",
                consumer,
                "actor",
                access.actor().id(),
                "req",
                request,
                "subject",
                Api.required(b, "subject"),
                "remark",
                Api.text(b, "remark")));
    items(id, b);
    return Api.ok(Map.of("id", id));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> itemList(Map<String, Object> b) {
    Object v = b.get("items");
    if (!(v instanceof List<?> l) || l.isEmpty() || l.size() > 100)
      throw Api.fail(422, "订单须包含1–100条明细");
    return (List<Map<String, Object>>) v;
  }

  private void items(long id, Map<String, Object> b) {
    int line = 0;
    long total = 0;
    for (var i : itemList(b)) {
      long quantity = Api.number(i, "quantity"), price = Api.number(i, "unitPriceCent");
      if (quantity < 1 || quantity > 10000 || price < 0 || price > 100000000)
        throw Api.fail(422, "数量或单价超出有效范围");
      total = Math.addExact(total, Math.multiplyExact(quantity, price));
      db.exec(
          "INSERT order_item(order_id,line_no,item_name,category_code,quantity,unit_price_cent)"
              + " VALUES(#{p.id},#{p.line},#{p.name},#{p.category},#{p.qty},#{p.price})",
          Api.params(
              "id",
              id,
              "line",
              ++line,
              "name",
              Api.required(i, "itemName"),
              "category",
              Api.text(i, "categoryCode").isBlank() ? "其他" : Api.text(i, "categoryCode"),
              "qty",
              quantity,
              "price",
              price));
    }
    if (total <= 0 || total > 100000000) throw Api.fail(422, "订单合计须大于0且不超过100万元");
  }

  private boolean sameItems(long id, Map<String, Object> b) {
    var rows =
        db.list(
            "SELECT * FROM order_item WHERE order_id=#{p.id} ORDER BY line_no",
            Api.params("id", id));
    var items = itemList(b);
    if (rows.size() != items.size()) return false;
    for (int n = 0; n < rows.size(); n++)
      if (!Objects.equals(rows.get(n).get("itemName"), items.get(n).get("itemName"))
          || Db.id(rows.get(n), "quantity") != Api.number(items.get(n), "quantity")
          || Db.id(rows.get(n), "unitPriceCent") != Api.number(items.get(n), "unitPriceCent"))
        return false;
    return true;
  }

  @PatchMapping("/{id}")
  @Transactional
  public Object update(@PathVariable long id, @RequestBody Map<String, Object> b) {
    access.order(id, true);
    int n =
        db.exec(
            "UPDATE business_order SET"
                + " subject=#{p.subject},remark=#{p.remark},updated_at=SYSUTCDATETIME() WHERE"
                + " id=#{p.id} AND status='DRAFT' AND version=CONVERT(BINARY(8),#{p.version},2)",
            Api.params(
                "id",
                id,
                "subject",
                Api.required(b, "subject"),
                "remark",
                Api.text(b, "remark"),
                "version",
                Api.required(b, "version")));
    if (n == 0) throw Api.fail(409, "仅可编辑未被修改的草稿");
    db.exec("DELETE order_item WHERE order_id=#{p.id}", Api.params("id", id));
    items(id, b);
    return Api.ok(Map.of("message", "草稿已保存"));
  }

  @DeleteMapping("/{id}")
  @Transactional
  public Object delete(@PathVariable long id) {
    var o = access.order(id, true);
    if (!"DRAFT".equals(o.get("status"))) throw Api.fail(409, "只可删除草稿");
    db.exec("DELETE order_item WHERE order_id=#{p.id}", Api.params("id", id));
    db.exec("DELETE business_order WHERE id=#{p.id} AND status='DRAFT'", Api.params("id", id));
    return Api.ok(Map.of("message", "草稿已删除"));
  }

  @PostMapping("/{id}/submit")
  Object submit(@PathVariable long id) {
    access.order(id, true);
    if (db.exec(
            "UPDATE business_order SET status='WAITING_PAYMENT',updated_at=SYSUTCDATETIME() WHERE"
                + " id=#{p.id} AND status='DRAFT'",
            Api.params("id", id))
        == 0) throw Api.fail(409, "订单不在草稿状态");
    return Api.ok(Map.of("message", "订单已提交收款"));
  }

  @PostMapping("/{id}/cancel")
  Object cancel(@PathVariable long id) {
    access.order(id, true);
    if (db.exec(
            "UPDATE business_order SET status='CANCELED',updated_at=SYSUTCDATETIME() WHERE"
                + " id=#{p.id} AND status IN ('DRAFT','WAITING_PAYMENT') AND NOT EXISTS(SELECT 1"
                + " FROM payment_attempt WHERE order_id=#{p.id} AND status NOT IN"
                + " ('CLOSED','FAILED'))",
            Api.params("id", id))
        == 0) throw Api.fail(409, "请先查询并确认待支付交易已关闭；成功收款请走退款");
    return Api.ok(Map.of("message", "订单已取消"));
  }
}
