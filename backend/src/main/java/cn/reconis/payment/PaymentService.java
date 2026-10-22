package cn.reconis.payment;

import cn.reconis.common.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private final Db db;
  private final FileStore files;
  public final PaymentGateway gateway;
  private final TransactionTemplate tx;

  public PaymentService(Db db, FileStore files, PaymentGateway gateway, TransactionTemplate tx) {
    this.db = db;
    this.files = files;
    this.gateway = gateway;
    this.tx = tx;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> create(long order, String request) {
    var p =
        tx.execute(
            t -> {
              var o =
                  db.need(
                      "SELECT * FROM business_order WITH(UPDLOCK,HOLDLOCK) WHERE id=#{p.id}",
                      Api.params("id", order));
              var old =
                  db.one(
                      "SELECT * FROM payment_attempt WHERE client_request_id=#{p.req}",
                      Api.params("req", request));
              if (old != null) {
                if (Db.id(old, "orderId") != order) throw Api.fail(409, "请求号已用于其他订单");
                return old;
              }
              if (!"WAITING_PAYMENT".equals(o.get("status"))) throw Api.fail(409, "订单不在待付款状态");
              old =
                  db.one(
                      "SELECT TOP 1 * FROM payment_attempt WHERE order_id=#{p.id} AND status NOT IN"
                          + " ('CLOSED','FAILED') ORDER BY id DESC",
                      Api.params("id", order));
              if (old != null) return old;
              long id =
                  db.insert(
                      "INSERT"
                          + " payment_attempt(order_id,client_request_id,out_trade_no,channel_env,app_id,seller_id,next_query_at)"
                          + " OUTPUT INSERTED.id"
                          + " VALUES(#{p.order},#{p.req},#{p.no},#{p.env},#{p.app},#{p.seller},DATEADD(SECOND,15,SYSUTCDATETIME()))",
                      Api.params(
                          "order",
                          order,
                          "req",
                          request,
                          "no",
                          Api.no("PAY"),
                          "env",
                          gateway.environment,
                          "app",
                          gateway.appId,
                          "seller",
                          gateway.sellerId));
              return db.need(
                  "SELECT * FROM payment_attempt WHERE id=#{p.id}", Api.params("id", id));
            });
    p.putAll(
        db.need(
            "SELECT amount_cent,subject FROM v_order WHERE id=#{p.id}", Api.params("id", order)));
    long id = Db.id(p, "id");
    if (terminal(p)) return current(id);
    if ("PENDING".equals(p.get("status")) && hasQr(p)) return current(id);
    // A committed send counter survives a crash before or after network I/O. Its outcome
    // is uncertain even if the process died before sending; always query the original id.
    if (Db.id(p, "precreateCount") > 0 || !"CREATED".equals(p.get("status"))) {
      p = (Map<String, Object>) syncPayment(id);
      if (terminal(p) || ("PENDING".equals(p.get("status")) && hasQr(p))) return p;
      if (!("PENDING".equals(p.get("status"))
          || "ACQ.TRADE_NOT_EXIST".equals(p.get("lastResultCode")))) return p;
    }
    String token = UUID.randomUUID().toString();
    var claimed =
        db.one(
            "DECLARE @claimed TABLE(previous_status VARCHAR(16),precreate_count INT);"
                + " UPDATE payment_attempt SET dispatch_token=#{p.token},"
                + " dispatch_lease_until=DATEADD(SECOND,90,SYSUTCDATETIME()),"
                + " next_query_at=DATEADD(SECOND,90,SYSUTCDATETIME()),precreate_count=precreate_count+1,"
                + " last_result_code='REQUEST_IN_PROGRESS',last_result_message=N'正在获取支付结果，请稍后刷新',"
                + " next_action='QUERY',updated_at=SYSUTCDATETIME()"
                + " OUTPUT deleted.status,inserted.precreate_count INTO @claimed"
                + " WHERE id=#{p.id} AND status IN ('CREATED','PENDING','UNKNOWN') AND"
                + " (dispatch_lease_until IS NULL OR dispatch_lease_until<=SYSUTCDATETIME());"
                + " SELECT * FROM @claimed;",
            Api.params("id", id, "token", token));
    if (claimed == null) return current(id);
    try {
      var r = gateway.create(p);
      save(r);
      String status = r.status();
      boolean firstSend = Db.id(claimed, "precreateCount") == 1
          && "CREATED".equals(claimed.get("previousStatus"));
      if ("FAILED".equals(status) && !firstSend) status = "UNKNOWN";
      if (!Set.of("FAILED", "PENDING", "UNKNOWN").contains(status)) status = "UNKNOWN";
      if ("PENDING".equals(status) && (r.qrCode() == null || r.qrCode().isBlank())) status = "UNKNOWN";
      String code = PaymentGateway.safeCode(null, r.code());
      var feedback = feedback(status, code, r.qrCode() != null && !r.qrCode().isBlank());
      if (r.status().equals("FAILED") && !firstSend)
        feedback = new Feedback("渠道拒绝本次重试，但之前的请求仍需确认。请查询原支付单，勿重复下单。", "QUERY");
      db.exec(
          "UPDATE payment_attempt SET status=#{p.status},qr_code=#{p.qr},"
              + " last_result_code=#{p.code},last_result_message=#{p.message},next_action=#{p.action},"
              + " next_query_at=CASE WHEN #{p.status}='FAILED' THEN NULL ELSE DATEADD(SECOND,15,SYSUTCDATETIME()) END,"
              + " updated_at=SYSUTCDATETIME() WHERE id=#{p.id} AND dispatch_token=#{p.token}"
              + " AND status IN ('CREATED','PENDING','UNKNOWN')",
          Api.params("id", id, "token", token, "status", status,
              "qr", "PENDING".equals(status) ? r.qrCode() : null, "code", code,
              "message", feedback.message(), "action", feedback.action()));
      audit(id, "PAYMENT_PRECREATE", code, status);
    } catch (Exception e) {
      db.exec(
          "UPDATE payment_attempt SET status='UNKNOWN',qr_code=NULL,last_result_code='CHANNEL_UNAVAILABLE',"
              + " last_result_message=N'暂未收到可信的渠道结果，将使用原支付单号查询。请勿重复下单。',"
              + " next_action='QUERY',next_query_at=DATEADD(SECOND,15,SYSUTCDATETIME()),"
              + " updated_at=SYSUTCDATETIME() WHERE id=#{p.id} AND dispatch_token=#{p.token}"
              + " AND status IN ('CREATED','PENDING','UNKNOWN')",
          Api.params("id", id, "token", token));
      audit(id, "PAYMENT_PRECREATE", "CHANNEL_UNAVAILABLE", "UNKNOWN");
    } finally {
      db.exec("UPDATE payment_attempt SET dispatch_token=NULL,dispatch_lease_until=NULL"
          + " WHERE id=#{p.id} AND dispatch_token=#{p.token}", Api.params("id", id, "token", token));
    }
    return current(id);
  }

  private boolean terminal(Map<String, Object> payment) {
    return Set.of("SUCCEEDED", "CLOSED", "FAILED").contains(payment.get("status"));
  }

  private boolean hasQr(Map<String, Object> payment) {
    return !Objects.toString(payment.get("qrCode"), "").isBlank();
  }

  private Map<String, Object> current(long id) {
    return db.need("SELECT p.*,o.amount_cent,o.subject,"
        + " CAST(CASE WHEN p.dispatch_lease_until>SYSUTCDATETIME() THEN 1 ELSE 0 END AS BIT) precreate_in_flight"
        + " FROM payment_attempt p JOIN v_order o ON o.id=p.order_id WHERE p.id=#{p.id}", Api.params("id", id));
  }

  private record Feedback(String message, String action) {}

  private Feedback feedback(String state, String code, boolean qr) {
    if (state.equals("SUCCEEDED")) return new Feedback("付款成功，已确认入账", "NONE");
    if (state.equals("CLOSED")) return new Feedback("该支付单已关闭，可重新发起支付", "RETRY");
    if (state.equals("PENDING"))
      return qr ? new Feedback("二维码已生成，请扫码付款；付款后可查询结果", "PAY")
          : new Feedback("渠道确认正在等待付款，可重新获取原支付单二维码", "RETRY");
    if (state.equals("FAILED")) {
      String message = switch (code) {
        case "MISSING_CONFIG", "INVALID_KEY", "KEY_ROLE_MISMATCH" -> "支付接入配置不完整，请联系商户检查配置后重试";
        case "ACQ.ACCESS_FORBIDDEN", "20001", "40001", "40002", "40006", "ACQ.PARTNER_ERROR" -> "渠道拒绝收款请求，请联系商户检查应用配置和收款权限后重试";
        case "ACQ.TOTAL_FEE_EXCEED", "ACQ.BEYOND_PAY_RESTRICTION" -> "本次收款超过渠道限额，请联系商户核对金额和收款额度";
        case "ACQ.EXIST_FORBIDDEN_WORD", "ACQ.INVALID_PARAMETER" -> "渠道拒绝订单信息，请联系商户核对订单内容后重试";
        default -> "渠道明确拒绝本次收款请求，请联系商户检查后重试";
      };
      return new Feedback(message, "CONTACT_SUPPORT");
    }
    return switch (code) {
      case "ACQ.TRADE_NOT_EXIST" -> new Feedback("渠道暂未查到该支付单，可重试获取二维码；系统继续使用原支付单号", "RETRY");
      case "ACQ.TRADE_HAS_SUCCESS" -> new Feedback("渠道提示该支付单已付款，正在查询核实，请勿重复付款", "QUERY");
      case "ACQ.TRADE_HAS_CLOSE" -> new Feedback("渠道提示该支付单已关闭，需查询确认后再重新付款", "QUERY");
      case "ACQ.CONTEXT_INCONSISTENT" -> new Feedback("渠道记录与本次请求不一致，请查询原支付单并联系商户核查", "QUERY");
      case "MISSING_QR_CODE" -> new Feedback("渠道未返回有效二维码，正在查询原支付单，请勿重复下单", "QUERY");
      case "AMOUNT_MISMATCH" -> new Feedback("渠道金额与订单不一致，已建立核查工单，请联系商户处理", "CONTACT_SUPPORT");
      default -> new Feedback("渠道结果暂未确认，将使用原支付单号继续查询，请勿重复下单", "QUERY");
    };
  }

  private String save(PaymentGateway.Result r) {
    return files.write(
        "evidence", ".json", r.raw().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public Object syncPayment(long id) {
    var p = current(id);
    if (terminal(p) || Boolean.TRUE.equals(p.get("precreateInFlight"))) return p;
    try {
      var r = gateway.query(p);
      String evidence = save(r);
      if (r.status().equals("SUCCEEDED")) {
        if (r.amountCent() != Db.id(p, "amountCent")) {
          recordQueryDifference(p, r, evidence);
          updateQueryFeedback(p, "UNKNOWN", "AMOUNT_MISMATCH");
          audit(id, "PAYMENT_QUERY", "AMOUNT_MISMATCH", "UNKNOWN");
          return current(id);
        }
        db.list(
            "EXEC usp_confirm_payment"
                + " @payment_id=#{p.id},@channel_trade_no=#{p.trade},@amount_cent=#{p.amount},@occurred_at=#{p.at},@evidence=#{p.evidence}",
            Api.params(
                "id",
                id,
                "trade",
                r.tradeNo(),
                "amount",
                r.amountCent(),
                "at",
                r.occurredAt(),
                "evidence",
                evidence));
        var confirmed =
            db.need(
                "SELECT is_primary FROM payment_attempt WHERE id=#{p.id}", Api.params("id", id));
        if (!Boolean.TRUE.equals(confirmed.get("isPrimary"))) recordQueryDifference(p, r, evidence);
        db.exec(
            "UPDATE payment_attempt SET qr_code=NULL,last_result_code=#{p.code},"
                + " last_result_message=N'付款成功，已确认入账',next_action='NONE'"
                + " WHERE id=#{p.id} AND status='SUCCEEDED'",
            Api.params("id", id, "code", PaymentGateway.safeCode(null, r.code())));
      } else updateQueryFeedback(p, Set.of("PENDING", "CLOSED").contains(r.status()) ? r.status() : "UNKNOWN", r.code());
      audit(id, "PAYMENT_QUERY", r.code(), r.status());
    } catch (Exception e) {
      audit(id, "PAYMENT_QUERY", "ERROR", "UNKNOWN");
      updateQueryFeedback(p, "UNKNOWN", "CHANNEL_UNAVAILABLE");
    }
    return current(id);
  }

  private void updateQueryFeedback(Map<String, Object> payment, String state, String code) {
    code = PaymentGateway.safeCode(null, code);
    var feedback = feedback(state, code, hasQr(payment));
    db.exec("UPDATE payment_attempt SET status=#{p.status},last_result_code=#{p.code},"
        + " last_result_message=#{p.message},next_action=#{p.action},"
        + " qr_code=CASE WHEN #{p.status}='CLOSED' THEN NULL ELSE qr_code END,"
        + " query_count=query_count+1,next_query_at=CASE WHEN #{p.status}='CLOSED' THEN NULL ELSE"
        + " DATEADD(SECOND,CASE WHEN query_count<1 THEN 15 WHEN query_count<3 THEN 60 ELSE 300 END,SYSUTCDATETIME()) END,"
        + " updated_at=SYSUTCDATETIME() WHERE id=#{p.id} AND status IN ('CREATED','PENDING','UNKNOWN')"
        + " AND precreate_count=#{p.count} AND (dispatch_lease_until IS NULL OR dispatch_lease_until<=SYSUTCDATETIME())",
        Api.params("id", payment.get("id"), "count", payment.get("precreateCount"), "status", state,
            "code", code, "message", feedback.message(), "action", feedback.action()));
  }

  private void recordQueryDifference(
      Map<String, Object> payment, PaymentGateway.Result result, String evidence) {
    String event = "P:" + payment.get("outTradeNo");
    var merchant =
        db.need(
                "SELECT merchant_id FROM v_order WHERE id=#{p.id}",
                Api.params("id", payment.get("orderId")))
            .get("merchantId");
    if (db.one(
            "SELECT TOP 1 id FROM v_case WHERE merchant_id=#{p.merchant} AND"
                + " source_event_key=#{p.event} AND status<>'CLOSED'",
            Api.params("merchant", merchant, "event", event))
        != null) return;
    var start =
        result
            .occurredAt()
            .atOffset(java.time.ZoneOffset.UTC)
            .atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(java.time.ZoneOffset.UTC)
            .toLocalDateTime();
    long batch =
        tx.execute(
            t -> {
              long id =
                  db.insert(
                      "INSERT"
                          + " reconciliation_batch(batch_no,scope_type,merchant_id,period_start,period_end,source_type,source_file_uri,completeness,created_by)"
                          + " OUTPUT INSERTED.id"
                          + " VALUES(#{p.no},'MERCHANT',#{p.merchant},#{p.start},#{p.end},'TRADE_QUERY',#{p.evidence},'UNKNOWN',#{p.actor})",
                      Api.params(
                          "no",
                          Api.no("QRY"),
                          "merchant",
                          merchant,
                          "start",
                          start,
                          "end",
                          start.plusDays(1),
                          "evidence",
                          evidence,
                          "actor",
                          RequestContext.actor() == null ? null : RequestContext.actor().id()));
              db.exec(
                  "INSERT"
                      + " statement_line(import_batch_id,line_no,event_type,out_trade_no,channel_trade_no,amount_cent,channel_occurred_at,raw_line_file_uri)"
                      + " VALUES(#{p.batch},1,'RECEIPT',#{p.trade},#{p.channel},#{p.amount},#{p.at},#{p.evidence})",
                  Api.params(
                      "batch",
                      id,
                      "trade",
                      payment.get("outTradeNo"),
                      "channel",
                      result.tradeNo(),
                      "amount",
                      result.amountCent(),
                      "at",
                      result.occurredAt(),
                      "evidence",
                      evidence));
              return id;
            });
    db.exec("EXEC usp_run_reconciliation @batch_id=#{p.id}", Api.params("id", batch));
  }

  public Map<String, Object> refund(long id) {
    return db.need(
        "SELECT r.*,p.out_trade_no,p.channel_trade_no,o.merchant_id,o.consumer_id,p.order_id FROM"
            + " refund_request r JOIN payment_attempt p ON p.id=r.payment_id JOIN v_order o ON"
            + " o.id=p.order_id WHERE r.id=#{p.id}",
        Api.params("id", id));
  }

  public void executeRefund(long id) {
    recoverRefund(id);
  }

  private void recoverRefund(long id) {
    String token = UUID.randomUUID().toString();
    // The lease is committed before network I/O. A crashed worker can be replaced;
    // every retry keeps the immutable channel refund request number and amount.
    var claimed =
        db.one(
            "DECLARE @claimed TABLE(previous_status VARCHAR(24)); UPDATE refund_request SET"
                + " dispatch_token=#{p.token},"
                + " dispatch_lease_until=DATEADD(SECOND,90,SYSUTCDATETIME()),"
                + " next_query_at=DATEADD(SECOND,90,SYSUTCDATETIME()), status=CASE WHEN"
                + " status='APPROVED' THEN 'PROCESSING' ELSE status END,"
                + " updated_at=SYSUTCDATETIME() OUTPUT deleted.status INTO @claimed WHERE"
                + " id=#{p.id} AND status IN ('APPROVED','PROCESSING','UNKNOWN') AND"
                + " (status='APPROVED' OR next_query_at IS NULL OR next_query_at<=SYSUTCDATETIME())"
                + " AND (dispatch_lease_until IS NULL OR dispatch_lease_until<=SYSUTCDATETIME());"
                + " SELECT previous_status FROM @claimed;",
            Api.params("id", id, "token", token));
    if (claimed == null) return;
    try {
      var request = refund(id);
      PaymentGateway.Result result;
      if ("APPROVED".equals(claimed.get("previousStatus"))) {
        result = gateway.refund(request);
      } else {
        result = gateway.queryRefund(request);
        if (Set.of("NOT_FOUND", "NOT_EXECUTED").contains(result.status())) {
          save(result);
          audit(id, "REFUND_REDISPATCH", result.code(), "RETRY");
          result = gateway.refund(request);
        }
      }
      applyRefund(id, token, result);
    } catch (Exception e) {
      db.exec(
          "UPDATE refund_request SET"
              + " status='UNKNOWN',next_query_at=DATEADD(SECOND,15,SYSUTCDATETIME()) WHERE"
              + " id=#{p.id} AND dispatch_token=#{p.token} AND status IN ('PROCESSING','UNKNOWN')",
          Api.params("id", id, "token", token));
      audit(id, "REFUND_CALL", "UNCERTAIN", "UNKNOWN");
    } finally {
      db.exec(
          "UPDATE refund_request SET dispatch_token=NULL,dispatch_lease_until=NULL"
              + " WHERE id=#{p.id} AND dispatch_token=#{p.token}",
          Api.params("id", id, "token", token));
    }
  }

  private void applyRefund(long id, String token, PaymentGateway.Result r) {
    String evidence = save(r);
    if (r.status().equals("SUCCEEDED"))
      db.list(
          "EXEC usp_confirm_refund"
              + " @refund_id=#{p.id},@amount_cent=#{p.amount},@occurred_at=#{p.at},@evidence=#{p.evidence}",
          Api.params(
              "id", id, "amount", r.amountCent(), "at", r.occurredAt(), "evidence", evidence));
    else
      db.exec(
          "UPDATE refund_request SET"
              + " status='UNKNOWN',query_count=query_count+1,next_query_at=DATEADD(MINUTE,1,SYSUTCDATETIME())"
              + " WHERE id=#{p.id} AND dispatch_token=#{p.token} AND status IN"
              + " ('PROCESSING','UNKNOWN')",
          Api.params("id", id, "token", token));
    audit(id, "REFUND_QUERY", r.code(), r.status());
  }

  public Object syncRefund(long id) {
    recoverRefund(id);
    return refund(id);
  }

  public void audit(long id, String action, String code, String status) {
    boolean refundAction = action.startsWith("REFUND");
    String from =
        refundAction
            ? "refund_request r JOIN payment_attempt p ON p.id=r.payment_id"
            : "payment_attempt p";
    var scope =
        db.one(
            "SELECT o.merchant_id FROM "
                + from
                + " JOIN v_order o ON o.id=p.order_id WHERE "
                + (refundAction ? "r" : "p")
                + ".id=#{p.id}",
            Api.params("id", id));
    db.exec(
        "INSERT"
            + " audit_log(event_category,actor_user_id,scope_merchant_id,object_type,object_id,action,result,summary,request_id)"
            + " VALUES('CHANNEL_QUERY',#{p.actor},#{p.merchant},#{p.type},#{p.id},#{p.action},#{p.result},#{p.summary},#{p.req})",
        Api.params(
            "actor",
            RequestContext.actor() == null ? null : RequestContext.actor().id(),
            "merchant",
            scope == null ? null : scope.get("merchantId"),
            "type",
            refundAction ? "REFUND" : "PAYMENT",
            "id",
            id,
            "action",
            action,
            "result",
            status,
            "summary",
            gateway.environment + " / " + code,
            "req",
            RequestContext.requestId()));
  }

  @Scheduled(
      fixedDelayString = "${reconis.query-delay-ms}",
      initialDelayString = "${reconis.query-delay-ms}")
  public void recover() {
    if (!gateway.configured()) return;
    RequestContext.set(null, UUID.randomUUID().toString());
    try {
      for (var p :
          db.list(
              "SELECT TOP 20 id FROM payment_attempt WHERE status IN"
                  + " ('CREATED','PENDING','UNKNOWN') AND next_query_at<=SYSUTCDATETIME() ORDER BY"
                  + " next_query_at",
              Map.of())) syncPayment(Db.id(p, "id"));
      for (var r :
          db.list(
              "SELECT TOP 20 id,status FROM refund_request WHERE status='APPROVED' OR (status IN"
                  + " ('PROCESSING','UNKNOWN') AND next_query_at<=SYSUTCDATETIME())"
                  + " ORDER BY COALESCE(next_query_at,created_at),id",
              Map.of())) {
        if ("APPROVED".equals(r.get("status"))) executeRefund(Db.id(r, "id"));
        else syncRefund(Db.id(r, "id"));
      }
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("状态恢复暂未完成：{}", e.getClass().getSimpleName());
    } finally {
      RequestContext.clear();
    }
  }
}
