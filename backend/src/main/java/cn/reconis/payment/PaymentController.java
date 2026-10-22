package cn.reconis.payment;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final Db db;
  private final Access access;
  private final PaymentService service;
  private final FileStore files;
  private final long queryDelayMs;

  public PaymentController(
      Db db,
      Access access,
      PaymentService service,
      FileStore files,
      @Value("${reconis.query-delay-ms}") long queryDelayMs) {
    this.db = db;
    this.access = access;
    this.service = service;
    this.files = files;
    this.queryDelayMs = queryDelayMs;
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
    var params = Api.params();
    String scope = access.scope("a.scope_merchant_id", params);
    var configuration = service.gateway.configuration();
    return Api.ok(
        Map.of(
            "environment",
            service.gateway.environment,
            "configured",
            configuration.configured(),
            "configurationStatus",
            configuration.status(),
            "configurationMessage",
            configuration.message(),
            "callbackConfigured",
            service.gateway.callbackConfigured(),
            "callbackMessage",
            service.gateway.environment.equals("DEMO")
                ? "模拟环境不接收支付宝沙箱通知"
                : (service.gateway.callbackConfigured()
                    ? "已设置通知地址；公网可达性仍需实际通知验证"
                    : "未设置可用的公网通知地址；仍可通过主动查询确认支付和退款"),
            "channel",
            "支付宝",
            "automaticQueryIntervalSeconds",
            queryDelayMs / 1000.0,
            "recentQueries",
            db.list(
                "SELECT TOP 12 a.action,a.result,a.summary,a.occurred_at FROM audit_log a WHERE"
                    + " a.event_category='CHANNEL_QUERY' AND ("
                    + scope
                    + " OR EXISTS (SELECT 1 FROM payment_attempt p JOIN business_order o ON"
                    + " o.id=p.order_id WHERE o.consumer_id=#{p.actor} AND"
                    + " ((a.object_type='PAYMENT' AND a.object_id=p.id) OR (a.object_type='REFUND'"
                    + " AND EXISTS (SELECT 1 FROM refund_request r WHERE r.id=a.object_id AND"
                    + " r.payment_id=p.id))))) ORDER BY a.id DESC",
                params)));
  }

  @PostMapping(value = "/callbacks/alipay", produces = "text/plain")
  String callback(@RequestParam MultiValueMap<String, String> parameters) {
    // Do not let form/query parameter collisions select an arbitrary signed value.
    if (parameters.size() > 100
        || parameters.entrySet().stream()
            .anyMatch(e -> e.getKey().length() > 128 || e.getValue().size() != 1))
      return "failure";
    Map<String, String> b = new TreeMap<>(parameters.toSingleValueMap());
    if (b.values().stream().anyMatch(Objects::isNull)
        || b.values().stream().mapToLong(String::length).sum() > 65536)
      return "failure";
    Map<String, Object> p = null;
    boolean valid = false;
    String outcome = "REJECTED";
    try {
      if (!"RSA2".equals(b.get("sign_type"))
          || !"trade_status_sync".equals(b.get("notify_type"))
          || !callbackField(b, "notify_id", 100)
          || !callbackField(b, "out_trade_no", 64)
          || !callbackField(b, "trade_no", 100)
          || !callbackField(b, "sign", 1024)
          || !Set.of("TRADE_SUCCESS", "TRADE_FINISHED", "WAIT_BUYER_PAY", "TRADE_CLOSED")
              .contains(Objects.toString(b.get("trade_status"), "")))
        throw Api.fail(422, "通知字段不合法");
      // The SDK may remove signature fields during verification. Preserve the original evidence.
      valid = service.gateway.verify(new HashMap<>(b));
      if (!valid) throw Api.fail(422, "通知签名无效");
      p =
          db.one(
              "SELECT p.*,o.amount_cent FROM payment_attempt p JOIN v_order o ON o.id=p.order_id"
                  + " WHERE p.out_trade_no=#{p.no}",
              Api.params("no", b.get("out_trade_no")));
      if (p == null
          || !service.gateway.appId.equals(b.get("app_id"))
          || !service.gateway.sellerId.equals(b.get("seller_id"))
          || !Objects.equals(p.get("appId"), b.get("app_id"))
          || !Objects.equals(p.get("sellerId"), b.get("seller_id"))
          || !Objects.equals(p.get("channelEnv"), service.gateway.environment)
          || !Objects.toString(b.get("total_amount"), "").matches("[0-9]{1,12}(\\.[0-9]{1,2})?")
          || PaymentGateway.cent(b.get("total_amount")) <= 0
          || PaymentGateway.cent(b.get("total_amount")) != Db.id(p, "amountCent"))
        throw Api.fail(422, "通知不可信");
      String trade = b.get("trade_no");
      if ("SUCCEEDED".equals(p.get("status"))) {
        // A committed receipt is enough to acknowledge a repeated, independently verified
        // notification. A temporary query outage must not turn an accepted payment into failure.
        if (!trade.equals(p.get("channelTradeNo"))) throw Api.fail(422, "通知交易号不一致");
        outcome = "ACCEPTED_DUPLICATE";
      } else if ("WAIT_BUYER_PAY".equals(b.get("trade_status"))) {
        // Informational notifications must never downgrade a confirmed or closed transaction.
        outcome = "ACCEPTED_INFORMATIONAL";
      } else {
        outcome = "RETRY_QUERY_UNCONFIRMED";
        service.syncPayment(Db.id(p, "id"));
        var state = db.need(
            "SELECT status,channel_trade_no FROM payment_attempt WHERE id=#{p.id}",
            Api.params("id", p.get("id")));
        boolean succeeded = "SUCCEEDED".equals(state.get("status"));
        if (succeeded && !trade.equals(state.get("channelTradeNo"))) {
          outcome = "REJECTED_TRADE_MISMATCH";
          throw Api.fail(422, "通知交易号与查单结果不一致");
        }
        if (!succeeded
            && !("TRADE_CLOSED".equals(b.get("trade_status"))
                && "CLOSED".equals(state.get("status"))))
          throw Api.fail(409, "查询尚未确认");
        outcome = "ACCEPTED";
      }
    } catch (Exception ignored) {
    }
    try {
      byte[] payload = files.jsonBytes(b);
      String uri = files.write("evidence", ".json", payload);
      db.exec(
          "INSERT"
              + " payment_notification(payment_id,notify_id,payload_file_uri,payload_hash,signature_valid,handling_result,request_id)"
              + " VALUES(#{p.id},#{p.notify},#{p.uri},#{p.hash},#{p.valid},#{p.result},#{p.req})",
          Api.params(
              "id", p == null ? null : p.get("id"),
              "notify", callbackField(b, "notify_id", 100) ? b.get("notify_id") : null,
              "uri", uri,
              "hash", FileStore.sha(payload),
              "valid", valid,
              "result", outcome,
              "req", RequestContext.requestId()));
    } catch (Exception e) {
      // Alipay retries unless the response body is exactly "success". In particular,
      // never acknowledge a notification whose durable audit evidence was not stored.
      org.slf4j.LoggerFactory.getLogger(getClass()).warn(
          "支付通知保存失败，requestId={}，type={}", RequestContext.requestId(), e.getClass().getSimpleName());
      return "failure";
    }
    return outcome.startsWith("ACCEPTED") ? "success" : "failure";
  }

  private static boolean callbackField(Map<String, String> b, String key, int maxLength) {
    String value = b.get(key);
    return value != null && !value.isBlank() && value.length() <= maxLength;
  }
}
