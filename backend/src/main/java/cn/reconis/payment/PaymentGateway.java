package cn.reconis.payment;

import cn.reconis.common.*;
import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentGateway {
  public record Result(
      String status,
      String tradeNo,
      long amountCent,
      LocalDateTime occurredAt,
      String code,
      String raw,
      String qrCode) {}

  private final FileStore files;
  private final ObjectMapper json = new ObjectMapper();
  public final String environment, appId, sellerId;
  private final String privateFile, publicFile, notifyUrl;

  public PaymentGateway(
      FileStore files,
      @Value("${reconis.environment}") String env,
      @Value("${reconis.alipay.app-id}") String app,
      @Value("${reconis.alipay.seller-id}") String seller,
      @Value("${reconis.alipay.private-key-file}") String priv,
      @Value("${reconis.alipay.public-key-file}") String pub,
      @Value("${reconis.alipay.notify-url}") String notify) {
    this.files = files;
    environment = env;
    appId = env.equals("DEMO") ? "DEMO_APP" : app;
    sellerId = env.equals("DEMO") ? "DEMO_SELLER" : seller;
    privateFile = priv;
    publicFile = pub;
    notifyUrl = notify;
    if (!Set.of("DEMO", "ALIPAY_SANDBOX").contains(env))
      throw new IllegalStateException("只支持 DEMO 或 ALIPAY_SANDBOX");
  }

  public boolean configured() {
    return environment.equals("DEMO")
        || (!appId.isBlank()
            && !sellerId.isBlank()
            && Files.isRegularFile(Path.of(privateFile))
            && Files.isRegularFile(Path.of(publicFile)));
  }

  private String key(String file) throws Exception {
    return Files.readString(Path.of(file)).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
  }

  private AlipayClient client() throws Exception {
    if (!configured()) throw Api.fail(409, "支付宝沙箱尚未配置应用和密钥");
    return new DefaultAlipayClient(
        "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
        appId,
        key(privateFile),
        "json",
        "UTF-8",
        key(publicFile),
        "RSA2");
  }

  public Result create(Map<String, Object> p) throws Exception {
    if (environment.equals("DEMO")) {
      String uri = uri(p.get("outTradeNo").toString());
      if (!Files.exists(files.resolve(uri)))
        files.jsonWrite(
            uri,
            Map.of(
                "status",
                "PENDING",
                "amountCent",
                p.get("amountCent").toString(),
                "tradeNo",
                "DEMO_" + p.get("outTradeNo")));
      return new Result(
          "PENDING",
          null,
          0,
          null,
          "DEMO_PRECREATE",
          "明确标注的模拟渠道",
          "reconis-demo:" + p.get("outTradeNo"));
    }
    var q = new AlipayTradePrecreateRequest();
    q.setNotifyUrl(notifyUrl);
    q.setBizContent(
        json.writeValueAsString(
            Map.of(
                "out_trade_no",
                p.get("outTradeNo"),
                "total_amount",
                yuan(p.get("amountCent")),
                "subject",
                p.get("subject"),
                "timeout_express",
                "15m")));
    return result(client().execute(q).getBody(), "alipay_trade_precreate_response", false);
  }

  public Result query(Map<String, Object> p) throws Exception {
    if (environment.equals("DEMO")) return demo(p.get("outTradeNo").toString());
    var q = new AlipayTradeQueryRequest();
    q.setBizContent(json.writeValueAsString(Map.of("out_trade_no", p.get("outTradeNo"))));
    return result(client().execute(q).getBody(), "alipay_trade_query_response", false);
  }

  public Result refund(Map<String, Object> r) throws Exception {
    if (environment.equals("DEMO")) {
      String k = r.get("outRequestNo").toString();
      if (!Files.exists(files.resolve(uri(k))))
        files.jsonWrite(
            uri(k),
            Map.of(
                "status",
                "SUCCEEDED",
                "amountCent",
                r.get("amountCent").toString(),
                "tradeNo",
                r.get("channelTradeNo"),
                "occurredAt",
                Instant.now().toString()));
      return demo(k);
    }
    var q = new AlipayTradeRefundRequest();
    q.setBizContent(
        json.writeValueAsString(
            Map.of(
                "out_trade_no",
                r.get("outTradeNo"),
                "out_request_no",
                r.get("outRequestNo"),
                "refund_amount",
                yuan(r.get("amountCent")),
                "refund_reason",
                r.get("reason"))));
    return result(client().execute(q).getBody(), "alipay_trade_refund_response", true);
  }

  public Result queryRefund(Map<String, Object> r) throws Exception {
    if (environment.equals("DEMO")) return demo(r.get("outRequestNo").toString());
    var q = new AlipayTradeFastpayRefundQueryRequest();
    q.setBizContent(
        json.writeValueAsString(
            Map.of(
                "out_trade_no",
                r.get("outTradeNo"),
                "out_request_no",
                r.get("outRequestNo"),
                "query_options",
                List.of("gmt_refund_pay"))));
    return result(
        client().execute(q).getBody(), "alipay_trade_fastpay_refund_query_response", true);
  }

  private Result result(String raw, String wrapper, boolean refund) throws Exception {
    JsonNode n = json.readTree(raw).path(wrapper);
    String code = n.path("code").asText(), state = "UNKNOWN";
    long amount = 0;
    LocalDateTime at = null;
    if (code.equals("10000")) {
      String status = n.path("trade_status").asText();
      if (refund) {
        if (n.path("refund_status").asText().equals("REFUND_SUCCESS")
            || !n.path("gmt_refund_pay").asText().isBlank()) state = "SUCCEEDED";
      } else
        state =
            switch (status) {
              case "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCEEDED";
              case "TRADE_CLOSED" -> "CLOSED";
              default -> "PENDING";
            };
      String a = n.path(refund ? "refund_amount" : "total_amount").asText();
      if (a.isBlank() && refund) a = n.path("refund_fee").asText();
      if (!a.isBlank()) amount = cent(a);
      String time = n.path(refund ? "gmt_refund_pay" : "send_pay_date").asText();
      if (!time.isBlank())
        at =
            LocalDateTime.parse(
                    time, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.of("Asia/Shanghai"))
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }
    if (state.equals("SUCCEEDED") && (amount <= 0 || at == null)) state = "UNKNOWN";
    return new Result(
        state, n.path("trade_no").asText(), amount, at, code, raw, n.path("qr_code").asText());
  }

  public void simulate(String key, long amount, String mode) {
    if (!environment.equals("DEMO")) throw Api.fail(403, "只允许演示环境模拟");
    if (!key.matches("[A-Za-z0-9_-]{1,64}")) throw Api.fail(422, "交易标识不合法");
    Map<String, Object> b =
        new HashMap<>(
            Map.of(
                "status",
                mode.equals("TIMEOUT") ? "UNKNOWN" : "SUCCEEDED",
                "tradeNo",
                "DEMO_" + key,
                "amountCent",
                Long.toString(amount),
                "occurredAt",
                Instant.now().toString()));
    files.jsonWrite(uri(key), b);
  }

  private Result demo(String key) {
    if (!Files.exists(files.resolve(uri(key))))
      return new Result("PENDING", null, 0, null, "DEMO_NOT_FOUND", "模拟渠道尚无完成记录", null);
    var b = files.jsonRead(uri(key));
    return new Result(
        b.get("status").toString(),
        Objects.toString(b.get("tradeNo"), ""),
        Long.parseLong(b.get("amountCent").toString()),
        b.get("occurredAt") == null ? null : Api.utc(b.get("occurredAt").toString()),
        "DEMO_QUERY",
        new String(files.jsonBytes(b), java.nio.charset.StandardCharsets.UTF_8),
        null);
  }

  private String uri(String key) {
    return "channel/" + key + ".json";
  }

  public boolean verify(Map<String, String> p) throws Exception {
    return !environment.equals("DEMO")
        && configured()
        && AlipaySignature.rsaCheckV1(p, key(publicFile), "UTF-8", "RSA2");
  }

  public static long cent(String yuan) {
    return new java.math.BigDecimal(yuan).movePointRight(2).longValueExact();
  }

  private String yuan(Object cent) {
    return new java.math.BigDecimal(cent.toString()).movePointLeft(2).toPlainString();
  }
}
