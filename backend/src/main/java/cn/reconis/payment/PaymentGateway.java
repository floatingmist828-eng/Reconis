package cn.reconis.payment;

import cn.reconis.common.*;
import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
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

  public record Configuration(boolean configured, String status, String message) {}

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
    return configuration().configured();
  }

  public Configuration configuration() {
    if (environment.equals("DEMO")) return new Configuration(true, "DEMO", "使用本地模拟渠道");
    if (appId.isBlank() || sellerId.isBlank() || privateFile.isBlank() || publicFile.isBlank())
      return new Configuration(false, "MISSING_CONFIG", "缺少沙箱应用、收款方或密钥文件配置");
    try {
      var factory = KeyFactory.getInstance("RSA");
      var privateKey =
          (RSAPrivateKey)
              factory.generatePrivate(
                  new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key(privateFile))));
      var publicKey =
          (RSAPublicKey)
              factory.generatePublic(
                  new X509EncodedKeySpec(Base64.getDecoder().decode(key(publicFile))));
      if (privateKey.getModulus().bitLength() != 2048 || publicKey.getModulus().bitLength() != 2048)
        return new Configuration(false, "INVALID_KEY", "RSA2 密钥应为 2048 位，应用私钥须使用 Java PKCS#8 格式");
      if (privateKey.getModulus().equals(publicKey.getModulus()))
        return new Configuration(false, "KEY_ROLE_MISMATCH", "验签文件应为支付宝公钥，不能使用应用自己的公钥");
      return new Configuration(true, "LOCAL_CONFIG_VALID", "本地密钥格式检查通过；渠道连通性及账号匹配仍需实际请求验证");
    } catch (Exception ignored) {
      return new Configuration(false, "INVALID_KEY", "无法读取有效 RSA2 密钥；检查 Java PKCS#8 应用私钥和支付宝公钥文件");
    }
  }

  public boolean callbackConfigured() {
    return !environment.equals("DEMO") && CallbackEndpoint.usable(notifyUrl);
  }

  private String key(String file) throws Exception {
    var path = Path.of(file);
    if (!Files.isRegularFile(path) || Files.size(path) > 16384)
      throw new IllegalArgumentException("无效密钥文件");
    return Files.readString(path).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
  }

  private AlipayClient client() throws Exception {
    if (!configured()) throw Api.fail(409, "支付宝沙箱尚未配置应用和密钥");
    var config = new AlipayConfig();
    config.setServerUrl("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
    config.setAppId(appId);
    config.setPrivateKey(key(privateFile));
    config.setAlipayPublicKey(key(publicFile));
    config.setFormat("json");
    config.setCharset("UTF-8");
    config.setSignType("RSA2");
    config.setConnectTimeout(5000);
    config.setReadTimeout(15000);
    return new DefaultAlipayClient(config);
  }

  public record BillAddress(String state, String code, String url) {}

  /** The SDK verifies the signed response before this download URL can be used. */
  public BillAddress billAddress(LocalDate date) throws Exception {
    if (environment.equals("DEMO")) return new BillAddress("DEMO", "DEMO_SIMULATED", null);
    if (!configured()) return new BillAddress("UNCONFIGURED", "MISSING_CONFIG", null);
    var request = new AlipayDataDataserviceBillDownloadurlQueryRequest();
    request.setBizContent(json.writeValueAsString(Map.of("bill_type", "trade", "bill_date", date.toString(), "secure", "true")));
    var response = client().execute(request);
    if (response.isSuccess()) return new BillAddress("AVAILABLE", "10000", response.getBillDownloadUrl());
    String code = Objects.toString(response.getSubCode(), response.getCode());
    if (code.contains("BILL_NOT_EXIST")) return new BillAddress("RETRY_WAIT", code, null);
    if (code.contains("NOT_SUPPORT") || code.contains("NOT_SUPPORTED") || code.contains("INSUFFICIENT"))
      return new BillAddress("UNSUPPORTED", code, null);
    if ("20000".equals(response.getCode())) return new BillAddress("RETRY_WAIT", code, null);
    return new BillAddress("FAILED", code, null);
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
    var configuration = configuration();
    if (!configuration.configured())
      return new Result("FAILED", null, 0, null, configuration.status(),
          json.writeValueAsString(Map.of("localConfiguration", configuration.status())), null);
    var q = new AlipayTradePrecreateRequest();
    if (callbackConfigured()) q.setNotifyUrl(notifyUrl);
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
    // refund_fee in the synchronous response can describe a trade-wide total.
    // Only the per-request query is used to book this refund's immutable amount.
    return result(client().execute(q).getBody(), "alipay_trade_refund_response", true);
  }

  public Result queryRefund(Map<String, Object> r) throws Exception {
    if (environment.equals("DEMO")) {
      String key = r.get("outRequestNo").toString();
      if (!Files.exists(files.resolve(uri(key))))
        return new Result("NOT_FOUND", null, 0, null, "DEMO_NOT_FOUND", "模拟渠道尚无退款记录", null);
      return demo(key);
    }
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

  Result result(String raw, String wrapper, boolean refund) throws Exception {
    JsonNode n = json.readTree(raw).path(wrapper);
    String code = n.path("code").asText(), state = "UNKNOWN";
    String diagnostic = safeCode(n.path("sub_code").asText(), code);
    boolean precreate = wrapper.equals("alipay_trade_precreate_response");
    if (precreate) {
      String qr = n.path("qr_code").asText();
      if (code.equals("10000")) {
        if (!qr.isBlank() && qr.length() <= 2048)
          return new Result("PENDING", null, 0, null, "10000", raw, qr);
        return new Result("UNKNOWN", null, 0, null, "MISSING_QR_CODE", raw, null);
      }
      // The official precreate error table explicitly requires querying after SYSTEM_ERROR.
      // Existing-trade/conflicting-context and unrecognized errors also never prove non-payment.
      boolean rejected =
          Set.of("20001", "40001", "40002", "40006").contains(code)
              || (code.equals("40004")
                  && Set.of(
                          "ACQ.INVALID_PARAMETER", "ACQ.ACCESS_FORBIDDEN",
                          "ACQ.EXIST_FORBIDDEN_WORD", "ACQ.PARTNER_ERROR",
                          "ACQ.TOTAL_FEE_EXCEED", "ACQ.BEYOND_PAY_RESTRICTION")
                      .contains(diagnostic));
      return new Result(rejected ? "FAILED" : "UNKNOWN", null, 0, null, diagnostic, raw, null);
    }
    long amount = 0;
    LocalDateTime at = null;
    if (code.equals("10000")) {
      String status = n.path("trade_status").asText();
      if (refund) {
        if (wrapper.equals("alipay_trade_fastpay_refund_query_response")) {
          if (n.path("refund_status").asText().equals("REFUND_SUCCESS")) state = "SUCCEEDED";
          // A successful query without REFUND_SUCCESS permits retry of the same
          // out_request_no and amount; transport/service errors remain UNKNOWN.
          else state = "NOT_EXECUTED";
        }
      } else
        state =
            switch (status) {
              case "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCEEDED";
              case "TRADE_CLOSED" -> "CLOSED";
              case "WAIT_BUYER_PAY" -> "PENDING";
              default -> "UNKNOWN";
            };
      String a = n.path(refund ? "refund_amount" : "total_amount").asText();
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
    if (state.equals("SUCCEEDED")
        && (amount <= 0 || at == null || (!refund && n.path("trade_no").asText().isBlank())))
      state = "UNKNOWN";
    return new Result(
        state, n.path("trade_no").asText(), amount, at, diagnostic, raw, null);
  }

  static String safeCode(String subCode, String code) {
    String value = subCode == null || subCode.isBlank() ? code : subCode;
    // Never expose channel sub_msg, transport exception text, request parameters, or signatures.
    if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}")) return "CHANNEL_RESPONSE_ERROR";
    return value.toUpperCase(Locale.ROOT);
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
