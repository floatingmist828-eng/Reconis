package cn.reconis.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentRecoveryTest {
  Db db;
  FileStore files;
  PaymentGateway gateway;
  PaymentService service;
  final Map<String, Object> request =
      Api.params(
          "id",
          7L,
          "status",
          "PROCESSING",
          "outTradeNo",
          "PAY_original",
          "outRequestNo",
          "REF_original",
          "amountCent",
          300L);

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    files = mock(FileStore.class);
    gateway = mock(PaymentGateway.class);
    service = new PaymentService(db, files, gateway, mock(TransactionTemplate.class));
    when(db.need(anyString(), anyMap())).thenReturn(request);
    when(files.write(anyString(), anyString(), any())).thenReturn("evidence/query.json");
  }

  void claim(String previous) {
    when(db.one(contains("DECLARE @claimed"), anyMap()))
        .thenReturn(Map.of("previousStatus", previous));
  }

  PaymentGateway.Result result(String state) {
    return new PaymentGateway.Result(
        state, "CHANNEL_1", 300, LocalDateTime.of(2026, 10, 21, 4, 0), "10000", "{}", null);
  }

  @Test
  void crashBeforeChannelCallRedispatchesOriginalRequestAfterConfirmedAbsence() throws Exception {
    claim("PROCESSING");
    when(gateway.queryRefund(request)).thenReturn(result("NOT_FOUND"));
    when(gateway.refund(request)).thenReturn(result("SUCCEEDED"));
    service.syncRefund(7);
    var order = inOrder(gateway);
    order.verify(gateway).queryRefund(request);
    order
        .verify(gateway)
        .refund(
            argThat(
                p ->
                    p.get("outRequestNo").equals("REF_original")
                        && p.get("amountCent").equals(300L)));
    verify(db)
        .list(startsWith("EXEC usp_confirm_refund"), argThat(p -> p.get("amount").equals(300L)));
  }

  @Test
  void alreadySucceededChannelRequestIsConfirmedWithoutResending() throws Exception {
    claim("UNKNOWN");
    when(gateway.queryRefund(request)).thenReturn(result("SUCCEEDED"));
    service.syncRefund(7);
    verify(gateway, never()).refund(anyMap());
    verify(db).list(startsWith("EXEC usp_confirm_refund"), anyMap());
  }

  @Test
  void uncertainQueryKeepsReservationAndDoesNotSendAnotherRefund() throws Exception {
    claim("UNKNOWN");
    when(gateway.queryRefund(request)).thenReturn(result("UNKNOWN"));
    service.syncRefund(7);
    verify(gateway, never()).refund(anyMap());
    verify(db, never()).list(startsWith("EXEC usp_confirm_refund"), anyMap());
    verify(db).exec(contains("status='UNKNOWN',query_count"), argThat(p -> p.get("token") != null));
  }

  @Test
  void occupiedLeaseOrUnapprovedRequestNeverCallsChannel() {
    when(db.one(contains("DECLARE @claimed"), anyMap())).thenReturn(null);
    service.syncRefund(7);
    verifyNoInteractions(gateway);
  }

  @Test
  void transportFailureRetainsUnknownAndReleasesOnlyItsOwnLease() throws Exception {
    claim("APPROVED");
    when(gateway.refund(request)).thenThrow(new java.io.IOException("timeout"));
    service.executeRefund(7);
    verify(db)
        .exec(contains("status='UNKNOWN',next_query_at"), argThat(p -> p.get("token") != null));
    verify(db).exec(contains("dispatch_token=NULL"), argThat(p -> p.get("token") != null));
    verify(db, never()).list(startsWith("EXEC usp_confirm_refund"), anyMap());
  }
}
