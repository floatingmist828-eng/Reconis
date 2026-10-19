package cn.reconis.payment;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final Db db;
  private final Access access;
  private final PaymentService service;
  private final FileStore files;

  public PaymentController(Db db, Access access, PaymentService service, FileStore files) {
    this.db = db;
    this.access = access;
    this.service = service;
    this.files = files;
  }

  @PostMapping("/orders/{id}/payments")
  Object create(@PathVariable long id, @RequestBody Map<String, Object> b) {
    access.order(id, false);
    if (access.actor().role().equals("AUDITOR") || access.actor().role().equals("ADMIN"))
      throw Api.fail(403, "当前身份不办理付款");
    return Api.ok(service.create(id, Api.uuid(b)));
  }

  @PostMapping("/payments/{id}/sync")
  Object sync(@PathVariable long id) {
    access.payment(id, false);
    if (access.actor().role().equals("AUDITOR")) throw Api.fail(403, "审计身份只读");
    return Api.ok(service.syncPayment(id));
  }

  @PostMapping("/payments/{id}/demo")
  Object demo(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var p = access.payment(id, false);
    if (access.actor().role().equals("AUDITOR") || access.actor().role().equals("ADMIN"))
      throw Api.fail(403, "当前身份只读");
    String mode = Api.required(b, "mode");
    service.gateway.simulate(p.get("outTradeNo").toString(), Db.id(p, "amountCent"), mode);
    if (mode.equals("NOTIFY")) {
      db.exec(
          "INSERT"
              + " payment_notification(payment_id,notify_id,payload_hash,signature_valid,handling_result,request_id)"
              + " VALUES(#{p.id},'DEMO_NOTIFICATION',#{p.hash},1,N'DEMO 模拟通知',#{p.req})",
          Api.params(
              "id",
              id,
              "hash",
              FileStore.sha((id + "DEMO").getBytes()),
              "req",
              RequestContext.requestId()));
      return Api.ok(service.syncPayment(id));
    }
    return Api.ok(
        Map.of("message", mode.equals("TIMEOUT") ? "模拟渠道超时，保留待确认状态" : "模拟渠道已收款且通知丢失，等待定时或主动查询"));
  }

  @PostMapping("/payments/{id}/refunds")
  Object apply(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var p = access.payment(id, false);
    if (!"SUCCEEDED".equals(p.get("status"))) throw Api.fail(409, "只能对成功支付申请退款");
    if (!Objects.equals(p.get("consumerId"), access.actor().id()))
      access.merchant(Db.id(p, "merchantId"), "OWNER", "CASHIER", "FINANCE");
    long amount = Api.number(b, "amountCent");
    if (amount <= 0) throw Api.fail(422, "退款金额须大于0");
    var old =
        db.one(
            "SELECT * FROM refund_request WHERE client_request_id=#{p.req}",
            Api.params("req", Api.uuid(b)));
    if (old != null) {
      if (Db.id(old, "paymentId") != id
          || Db.id(old, "amountCent") != amount
          || !Objects.equals(old.get("reason"), Api.required(b, "reason")))
        throw Api.fail(409, "退款请求号内容冲突");
      return Api.ok(old);
    }
    long rid =
        db.insert(
            "INSERT"
                + " refund_request(payment_id,client_request_id,out_request_no,amount_cent,reason,applicant_id)"
                + " OUTPUT INSERTED.id"
                + " VALUES(#{p.id},#{p.req},#{p.no},#{p.amount},#{p.reason},#{p.actor})",
            Api.params(
                "id",
                id,
                "req",
                Api.uuid(b),
                "no",
                Api.no("REF"),
                "amount",
                amount,
                "reason",
                Api.required(b, "reason"),
                "actor",
                access.actor().id()));
    return Api.ok(Map.of("id", rid));
  }

  @GetMapping("/refunds")
  Object refunds(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "1") int page) {
    var p = Api.params("q", "%" + q + "%", "status", status);
    String scope = access.scope("x.merchant_id", p) + " OR x.consumer_id=#{p.actor}";
    return Api.ok(
        db.page(
            "FROM (SELECT"
                + " r.*,p.order_id,p.out_trade_no,o.merchant_id,o.merchant_name,o.store_name,o.order_no,o.consumer_id,u.display_name"
                + " applicant_name,dbo.fn_refundable_amount(p.id) refundable_cent FROM"
                + " refund_request r JOIN payment_attempt p ON p.id=r.payment_id JOIN v_order o ON"
                + " o.id=p.order_id JOIN app_user u ON u.id=r.applicant_id) x",
            "("
                + scope
                + ") AND (out_request_no LIKE #{p.q} OR reason LIKE #{p.q}) AND (#{p.status}='' OR"
                + " status=#{p.status})",
            "id DESC",
            p,
            page,
            20));
  }

  @PostMapping("/refunds/{id}/review")
  Object review(@PathVariable long id, @RequestBody Map<String, Object> b) {
    var r = service.refund(id);
    access.merchant(Db.id(r, "merchantId"), "OWNER", "FINANCE");
    boolean approve = Boolean.TRUE.equals(b.get("approve"));
    db.exec(
        "EXEC usp_approve_refund"
            + " @refund_id=#{p.id},@reviewer=#{p.actor},@approve=#{p.approve},@reason=#{p.reason}",
        Api.params(
            "id",
            id,
            "actor",
            access.actor().id(),
            "approve",
            approve,
            "reason",
            Api.required(b, "reason")));
    if (approve) service.executeRefund(id);
    return Api.ok(service.refund(id));
  }

  @PostMapping("/refunds/{id}/sync")
  Object syncRefund(@PathVariable long id) {
    var r = service.refund(id);
    access.merchant(Db.id(r, "merchantId"), "OWNER", "FINANCE");
    return Api.ok(service.syncRefund(id));
  }

  @GetMapping("/integration/status")
  Object status() {
    access.actor();
    return Api.ok(
        Map.of(
            "environment",
            service.gateway.environment,
            "configured",
            service.gateway.configured(),
            "channel",
            "支付宝",
            "automaticQueryIntervalSeconds",
            15,
            "recentQueries",
            db.list(
                "SELECT TOP 12 action,result,summary,occurred_at FROM audit_log WHERE"
                    + " event_category='CHANNEL_QUERY' AND (actor_user_id=#{p.actor} OR"
                    + " actor_user_id IS NULL) ORDER BY id DESC",
                Api.params("actor", access.actor().id()))));
  }

  @PostMapping(value = "/callbacks/alipay", produces = "text/plain")
  String callback(@RequestParam Map<String, String> b) {
    String payload = new String(files.jsonBytes(b), java.nio.charset.StandardCharsets.UTF_8);
    var p =
        db.one(
            "SELECT p.*,o.amount_cent FROM payment_attempt p JOIN v_order o ON o.id=p.order_id"
                + " WHERE p.out_trade_no=#{p.no}",
            Api.params("no", b.get("out_trade_no")));
    boolean valid = false;
    String outcome = "REJECTED";
    try {
      valid = service.gateway.verify(b);
      if (!valid
          || p == null
          || !service.gateway.appId.equals(b.get("app_id"))
          || !service.gateway.sellerId.equals(b.get("seller_id"))
          || PaymentGateway.cent(b.get("total_amount")) != Db.id(p, "amountCent"))
        throw Api.fail(422, "通知不可信");
      if (Set.of("TRADE_SUCCESS", "TRADE_FINISHED").contains(b.get("trade_status"))) {
        service.syncPayment(Db.id(p, "id"));
        var state =
            db.need(
                "SELECT status FROM payment_attempt WHERE id=#{p.id}",
                Api.params("id", p.get("id")));
        if (!"SUCCEEDED".equals(state.get("status"))) throw Api.fail(409, "查询尚未确认");
      }
      outcome = "ACCEPTED";
    } catch (Exception ignored) {
    }
    String uri =
        files.write("evidence", ".json", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    db.exec(
        "INSERT"
            + " payment_notification(payment_id,notify_id,payload_file_uri,payload_hash,signature_valid,handling_result,request_id)"
            + " VALUES(#{p.id},#{p.notify},#{p.uri},#{p.hash},#{p.valid},#{p.result},#{p.req})",
        Api.params(
            "id",
            p == null ? null : p.get("id"),
            "notify",
            b.get("notify_id"),
            "uri",
            uri,
            "hash",
            FileStore.sha(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "valid",
            valid,
            "result",
            outcome,
            "req",
            RequestContext.requestId()));
    return outcome.equals("ACCEPTED") ? "success" : "failure";
  }
}
