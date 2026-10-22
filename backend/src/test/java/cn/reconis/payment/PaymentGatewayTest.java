package cn.reconis.payment;

import static org.junit.jupiter.api.Assertions.*;

import cn.reconis.common.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaymentGatewayTest {
  @TempDir Path temp;

  PaymentGateway gateway() throws Exception {
    var files =
        new FileStore(
            temp.resolve("demo").toString(),
            "DEMO",
            "jdbc:sqlserver://localhost;databaseName=ReconisDemo");
    return new PaymentGateway(files, "DEMO", "", "", "", "", "");
  }

  @Test
  void onlySuccessfulPrecreateWithQrCodeIsPayable() throws Exception {
    var accepted = gateway().result(
        "{\"alipay_trade_precreate_response\":{\"code\":\"10000\",\"qr_code\":\"https://qr.alipay.com/test\"}}",
        "alipay_trade_precreate_response", false);
    assertEquals("PENDING", accepted.status());
    assertNotNull(accepted.qrCode());
    var incomplete = gateway().result(
        "{\"alipay_trade_precreate_response\":{\"code\":\"10000\"}}",
        "alipay_trade_precreate_response", false);
    assertEquals("UNKNOWN", incomplete.status());
    assertEquals("MISSING_QR_CODE", incomplete.code());
  }

  @Test
  void explicitParameterOrPermissionRefusalIsDistinguishedFromUncertainOutcome() throws Exception {
    for (String code : java.util.List.of("ACQ.INVALID_PARAMETER", "ACQ.ACCESS_FORBIDDEN", "ACQ.TOTAL_FEE_EXCEED")) {
      var rejected = gateway().result(
          "{\"alipay_trade_precreate_response\":{\"code\":\"40004\",\"sub_code\":\"" + code + "\",\"sub_msg\":\"secret request parameters\"}}",
          "alipay_trade_precreate_response", false);
      assertEquals("FAILED", rejected.status());
      assertEquals(code, rejected.code());
      assertNull(rejected.qrCode());
    }
    for (String code : java.util.List.of("ACQ.SYSTEM_ERROR", "ACQ.TRADE_HAS_SUCCESS", "ACQ.TRADE_HAS_CLOSE", "ACQ.CONTEXT_INCONSISTENT", "UNRECOGNIZED_ERROR")) {
      var uncertain = gateway().result(
          "{\"alipay_trade_precreate_response\":{\"code\":\"40004\",\"sub_code\":\"" + code + "\"}}",
          "alipay_trade_precreate_response", false);
      assertEquals("UNKNOWN", uncertain.status(), code);
    }
  }

  @Test
  void unexpectedQueryStatusAndMissingTradeNumberAreNotSuccessfulPayments() throws Exception {
    var missing = gateway().result(
        "{\"alipay_trade_query_response\":{\"code\":\"10000\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"1.00\",\"send_pay_date\":\"2026-10-22 12:00:00\"}}",
        "alipay_trade_query_response", false);
    assertEquals("UNKNOWN", missing.status());
    var unexpected = gateway().result(
        "{\"alipay_trade_query_response\":{\"code\":\"10000\",\"trade_status\":\"FUTURE_STATUS\"}}",
        "alipay_trade_query_response", false);
    assertEquals("UNKNOWN", unexpected.status());
  }

  @Test
  void diagnosticCodesCannotContainRawChannelTextOrCredentials() {
    assertEquals("CHANNEL_RESPONSE_ERROR", PaymentGateway.safeCode("secret=123456 private_key=value", "40004"));
    assertEquals("ACQ.SYSTEM_ERROR", PaymentGateway.safeCode("acq.system_error", "40004"));
  }

  @Test
  void successfulQueryIsNotEquivalentToSuccessfulRefund() throws Exception {
    var r =
        gateway()
            .result(
                "{\"alipay_trade_fastpay_refund_query_response\":{\"code\":\"10000\",\"refund_amount\":\"0.00\"}}",
                "alipay_trade_fastpay_refund_query_response",
                true);
    assertEquals("NOT_EXECUTED", r.status());
  }

  @Test
  void unsuccessfulRefundQueryWithRequestedAmountCanBeRetried() throws Exception {
    var r =
        gateway()
            .result(
                "{\"alipay_trade_fastpay_refund_query_response\":{\"code\":\"10000\",\"refund_amount\":\"3.00\"}}",
                "alipay_trade_fastpay_refund_query_response",
                true);
    assertEquals("NOT_EXECUTED", r.status());
  }

  @Test
  void unavailableChannelIsUnknownNotMissing() throws Exception {
    var r =
        gateway()
            .result(
                "{\"alipay_trade_fastpay_refund_query_response\":{\"code\":\"20000\",\"sub_code\":\"SYSTEM_ERROR\"}}",
                "alipay_trade_fastpay_refund_query_response",
                true);
    assertEquals("UNKNOWN", r.status());
  }

  @Test
  void onlyPerRequestAmountAndConfirmedTimeAreBooked() throws Exception {
    var r =
        gateway()
            .result(
                """
                {"alipay_trade_fastpay_refund_query_response":{"code":"10000","refund_status":"REFUND_SUCCESS",
                 "refund_amount":"3.00","refund_fee":"8.00","gmt_refund_pay":"2026-10-21 12:00:00"}}
                """,
                "alipay_trade_fastpay_refund_query_response",
                true);
    assertEquals("SUCCEEDED", r.status());
    assertEquals(300, r.amountCent());
    assertEquals(4, r.occurredAt().getHour());
  }

  @Test
  void synchronousCumulativeRefundAmountIsNotBooked() throws Exception {
    var r =
        gateway()
            .result(
                """
                {"alipay_trade_refund_response":{"code":"10000","fund_change":"Y",
                 "refund_fee":"8.00","gmt_refund_pay":"2026-10-21 12:00:00"}}
                """,
                "alipay_trade_refund_response",
                true);
    assertEquals("UNKNOWN", r.status());
  }

  @Test
  void demoRetryPreservesOriginalRefundFact() throws Exception {
    var gateway = gateway();
    var request =
        Api.params("outRequestNo", "REF_1", "amountCent", 300L, "channelTradeNo", "TRADE_1");
    assertEquals("NOT_FOUND", gateway.queryRefund(request).status());
    var original = gateway.refund(request);
    var retry = gateway.refund(request);
    assertEquals(original.occurredAt(), retry.occurredAt());
    assertEquals(original.amountCent(), retry.amountCent());
    assertEquals("SUCCEEDED", gateway.queryRefund(request).status());
  }
}
