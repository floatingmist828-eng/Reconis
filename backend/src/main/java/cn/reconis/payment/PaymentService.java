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
    if (Set.of("SUCCEEDED", "CLOSED", "FAILED").contains(p.get("status"))) return p;
    try {
      var r = gateway.create(p);
      String evidence = save(r);
      db.exec(
          "UPDATE payment_attempt SET status=CASE WHEN status='CREATED' THEN 'PENDING' ELSE status"
              + " END WHERE id=#{p.id}",
          Api.params("id", p.get("id")));
      p.put("qrCode", r.qrCode());
      p.put("evidence", evidence);
    } catch (Exception e) {
      db.exec(
          "UPDATE payment_attempt SET status='UNKNOWN' WHERE id=#{p.id} AND status<>'SUCCEEDED'",
          Api.params("id", p.get("id")));
      p.put("message", "渠道结果待确认，将使用原请求号查询恢复");
    }
    return p;
  }

  private String save(PaymentGateway.Result r) {
    return files.write(
        "evidence", ".json", r.raw().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public Object syncPayment(long id) {
    var p =
        db.need(
            "SELECT p.*,o.amount_cent,o.subject FROM payment_attempt p JOIN v_order o ON"
                + " o.id=p.order_id WHERE p.id=#{p.id}",
            Api.params("id", id));
    try {
      var r = gateway.query(p);
      String evidence = save(r);
      if (r.status().equals("SUCCEEDED")) {
        if (r.amountCent() != Db.id(p, "amountCent")) {
          recordQueryDifference(p, r, evidence);
          throw Api.fail(409, "可信渠道结果金额与订单不一致，已建立核查工单");
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
      } else
        db.exec(
            "UPDATE payment_attempt SET"
                + " status=#{p.status},query_count=query_count+1,next_query_at=DATEADD(SECOND,CASE"
                + " WHEN query_count<1 THEN 15 WHEN query_count<3 THEN 60 ELSE 300"
                + " END,SYSUTCDATETIME()) WHERE id=#{p.id} AND status<>'SUCCEEDED'",
            Api.params("id", id, "status", r.status()));
      audit(id, "PAYMENT_QUERY", r.code(), r.status());
    } catch (Exception e) {
      audit(id, "PAYMENT_QUERY", "ERROR", "UNKNOWN");
      db.exec(
          "UPDATE payment_attempt SET"
              + " next_query_at=DATEADD(MINUTE,5,SYSUTCDATETIME()),query_count=query_count+1 WHERE"
              + " id=#{p.id} AND status<>'SUCCEEDED'",
          Api.params("id", id));
    }
    return db.need("SELECT * FROM payment_attempt WHERE id=#{p.id}", Api.params("id", id));
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
    if (db.exec(
            "UPDATE refund_request SET"
                + " status='PROCESSING',next_query_at=DATEADD(SECOND,30,SYSUTCDATETIME()) WHERE"
                + " id=#{p.id} AND status='APPROVED'",
            Api.params("id", id))
        == 0) return;
    try {
      applyRefund(id, gateway.refund(refund(id)));
    } catch (Exception e) {
      db.exec(
          "UPDATE refund_request SET"
              + " status='UNKNOWN',next_query_at=DATEADD(SECOND,15,SYSUTCDATETIME()) WHERE"
              + " id=#{p.id} AND status='PROCESSING'",
          Api.params("id", id));
      audit(id, "REFUND_CALL", "UNCERTAIN", "UNKNOWN");
    }
  }

  private void applyRefund(long id, PaymentGateway.Result r) {
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
              + " WHERE id=#{p.id} AND status IN ('PROCESSING','UNKNOWN')",
          Api.params("id", id));
    audit(id, "REFUND_QUERY", r.code(), r.status());
  }

  public Object syncRefund(long id) {
    try {
      applyRefund(id, gateway.queryRefund(refund(id)));
    } catch (Exception e) {
      audit(id, "REFUND_QUERY", "ERROR", "UNKNOWN");
    }
    return refund(id);
  }

  public void audit(long id, String action, String code, String status) {
    db.exec(
        "INSERT"
            + " audit_log(event_category,actor_user_id,object_type,object_id,action,result,summary,request_id)"
            + " VALUES('CHANNEL_QUERY',#{p.actor},'CHANNEL',#{p.id},#{p.action},#{p.result},#{p.summary},#{p.req})",
        Api.params(
            "actor",
            RequestContext.actor() == null ? null : RequestContext.actor().id(),
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
                  + " ('PROCESSING','UNKNOWN') AND next_query_at<=SYSUTCDATETIME()) ORDER BY id",
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
