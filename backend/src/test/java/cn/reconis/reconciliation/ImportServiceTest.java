package cn.reconis.reconciliation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import cn.reconis.payment.PaymentGateway;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ImportServiceTest {
  private Db db;
  private FileStore files;
  private Access access;
  private ImportService service;

  @Test
  @SuppressWarnings("unchecked")
  void completePlatformBillIncludesMerchantsEntirelyAbsentFromChannelRows() throws Exception {
    when(db.one(contains("WHERE p.out_trade_no="), anyMap())).thenReturn(owner(1, 1, "P1"));
    when(db.list(startsWith("SELECT DISTINCT merchant_id"), anyMap()))
        .thenReturn(List.of(Api.params("merchantId", 1L), Api.params("merchantId", 2L)));
    var result =
        (Map<String, Object>)
            service.statement(
                statement(event("P1", "2026-10-20", "M1")),
                "xml",
                null,
                "2026-10-20",
                "2026-10-20",
                "DECLARED_COMPLETE",
                true);
    var partitions = (List<Map<String, Object>>) result.get("partitions");
    assertEquals(2, partitions.size());
    assertEquals(
        0,
        partitions.stream()
            .filter(p -> p.get("merchantId").equals(2L))
            .findFirst()
            .orElseThrow()
            .get("rows"));
    assertEquals(true, result.get("canImport"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void completeEmptyPlatformBillCreatesZeroChannelInputsForAllLocalMerchants() throws Exception {
    when(db.list(startsWith("SELECT DISTINCT merchant_id"), anyMap()))
        .thenReturn(List.of(Api.params("merchantId", 1L), Api.params("merchantId", 2L)));
    var inserted = new ArrayList<Map<String, Object>>();
    when(db.insert(anyString(), anyMap()))
        .thenAnswer(
            call -> {
              inserted.add(call.getArgument(1));
              return 100L + inserted.size();
            });
    var result =
        (Map<String, Object>)
            service.statement(
                statement(), "xml", null, "2026-10-20", "2026-10-20", "DECLARED_COMPLETE", false);
    assertEquals(true, result.get("emptyComplete"));
    assertEquals(0, result.get("accepted"));
    assertEquals(2, inserted.size());
    assertEquals(
        Set.of(1L, 2L),
        inserted.stream().map(p -> p.get("m")).collect(java.util.stream.Collectors.toSet()));
    assertTrue(inserted.stream().allMatch(p -> p.get("complete").equals("DECLARED_COMPLETE")));
    verify(db, never()).exec(startsWith("INSERT statement_line"), anyMap());
  }

  @Test
  void emptyOrMalformedFilesCannotBecomeCompleteWithoutExplicitValidDeclaration() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.statement(
                statement(), "xml", 1L, "2026-10-20", "2026-10-20", "UNKNOWN", false));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.statement(
                "<statement schemaVersion=\"1\" channelEnv=\"ALIPAY_SANDBOX\"/>"
                    .getBytes(StandardCharsets.UTF_8),
                "xml",
                1L,
                "2026-10-20",
                "2026-10-20",
                "DECLARED_COMPLETE",
                false));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.statement(
                statement("<unknown/>"),
                "xml",
                1L,
                "2026-10-20",
                "2026-10-20",
                "DECLARED_COMPLETE",
                false));
    verify(db, never()).insert(anyString(), anyMap());
  }

  @Test
  @SuppressWarnings("unchecked")
  void validEmptyExcelTemplateCanRepresentCompleteSingleMerchantBill() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
      var header = book.createSheet("statement").createRow(0);
      for (int i = 0; i < ImportService.HEADERS.size(); i++)
        header.createCell(i).setCellValue(ImportService.HEADERS.get(i));
      book.write(bytes);
    }
    var result =
        (Map<String, Object>)
            service.statement(
                bytes.toByteArray(),
                "xlsx",
                1L,
                "2026-10-20",
                "2026-10-20",
                "DECLARED_COMPLETE",
                true);
    assertEquals(true, result.get("canImport"));
    assertEquals(true, result.get("emptyComplete"));
    assertEquals(1, ((List<?>) result.get("partitions")).size());
  }

  @Test
  void invalidRowsMustNotGenerateAbsentMerchantBatchesFromACompleteDeclaration() throws Exception {
    service.statement(
        statement(event("UNKNOWN", "2026-10-21", "M1")),
        "xml",
        null,
        "2026-10-20",
        "2026-10-20",
        "DECLARED_COMPLETE",
        true);
    verify(db, never()).list(startsWith("SELECT DISTINCT merchant_id"), anyMap());
  }

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    files = mock(FileStore.class);
    access = mock(Access.class);
    when(db.one(anyString(), anyMap())).thenReturn(null);
    service =
        new ImportService(db, files, access, new PaymentGateway(files, "DEMO", "", "", "", "", ""));
    when(access.actor())
        .thenReturn(new RequestContext.Actor(1L, "finance", "财务", "PLATFORM_FINANCE"));
    when(db.need(contains("FROM merchant"), anyMap()))
        .thenAnswer(
            call -> {
              long id = ((Number) ((Map<?, ?>) call.getArgument(1)).get("id")).longValue();
              return Api.params("id", id, "merchantCode", "M" + id, "name", "商户" + id);
            });
    when(files.write(anyString(), anyString(), any())).thenReturn("imports/source.xml");
  }

  private static Map<String, Object> owner(long payment, long merchant, String trade) {
    return Api.params(
        "paymentId",
        payment,
        "merchantId",
        merchant,
        "merchantCode",
        "M" + merchant,
        "outTradeNo",
        trade,
        "channelTradeNo",
        "C" + trade,
        "channelEnv",
        "DEMO",
        "appId",
        "DEMO_APP");
  }

  private static Map<String, Object> refund(String trade, String request) {
    return Api.params(
        "event_type",
        "REFUND",
        "out_trade_no",
        trade,
        "out_request_no",
        request,
        "channel_trade_no",
        "",
        "merchant_code",
        "");
  }

  @Test
  void rejectsRefundThatReferencesAnotherPaymentEvenWithinSameMerchant() {
    when(db.one(contains("WHERE p.out_trade_no="), anyMap())).thenReturn(owner(2, 1, "P2"));
    when(db.one(contains("JOIN refund_request"), anyMap())).thenReturn(owner(1, 1, "P1"));
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveMerchant(refund("P2", "R1"), 1L, new HashMap<>()));
    assertEquals(422, error.getStatusCode().value());
  }

  @Test
  void rejectsForeignRefundEvenWhenOriginalPaymentIsBlank() {
    when(db.one(contains("JOIN refund_request"), anyMap())).thenReturn(owner(2, 2, "P2"));
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resolveMerchant(refund("", "R2"), 1L, new HashMap<>()));
    assertEquals(403, error.getStatusCode().value());
  }

  @Test
  void identifiesRefundOwnerFromRequestAndNormalizesMissingPaymentKey() {
    when(db.one(contains("JOIN refund_request"), anyMap())).thenReturn(owner(2, 2, "P2"));
    var row = refund("", "R2");
    assertEquals(2L, service.resolveMerchant(row, null, new HashMap<>()));
    assertEquals("P2", row.get("out_trade_no"));
  }

  @Test
  void unknownReferenceDoesNotTrustMerchantNameInPlatformBill() {
    var row = refund("UNKNOWN", "UNKNOWN_REFUND");
    row.put("merchant_code", "M1");
    assertNull(service.resolveMerchant(row, null, new HashMap<>()));
  }

  @Test
  void optionsIdentityChangesForPeriodCompletenessAndResolvedOwnership() {
    String original =
        ImportService.optionsHash("xml", 1L, "2026-10-20", "2026-10-20", "UNKNOWN", "DEMO", "1:1");
    assertEquals(
        original,
        ImportService.optionsHash("xml", 1L, "2026-10-20", "2026-10-20", "UNKNOWN", "DEMO", "1:1"));
    assertNotEquals(
        original,
        ImportService.optionsHash("xml", 1L, "2026-10-20", "2026-10-21", "UNKNOWN", "DEMO", "1:1"));
    assertNotEquals(
        original,
        ImportService.optionsHash(
            "xml", 1L, "2026-10-20", "2026-10-20", "DECLARED_COMPLETE", "DEMO", "1:1"));
    assertNotEquals(
        original,
        ImportService.optionsHash(
            "xml", 1L, "2026-10-20", "2026-10-20", "UNKNOWN", "DEMO", "1:unassigned"));
  }

  private static byte[] statement(String... events) {
    return ("<statement schemaVersion=\"1\" channelEnv=\"DEMO\">"
            + String.join("", events)
            + "</statement>")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String event(String trade, String day, String merchant) {
    return "<event merchantCode=\""
        + merchant
        + "\" type=\"RECEIPT\"><outTradeNo>"
        + trade
        + "</outTradeNo><amountCent>100</amountCent><occurredAt>"
        + day
        + "T12:00:00+08:00</occurredAt></event>";
  }

  @Test
  @SuppressWarnings("unchecked")
  void changingPeriodActuallyCreatesNewInputInsteadOfReusingPartialImport() throws Exception {
    Map<String, Map<String, Object>> saved = new HashMap<>();
    var ids = new AtomicLong(100);
    when(db.one(contains("ingest_options_sha256="), anyMap()))
        .thenAnswer(
            call -> {
              Map<String, Object> p = call.getArgument(1);
              return saved.get(p.get("options").toString());
            });
    when(db.insert(anyString(), anyMap()))
        .thenAnswer(
            call -> {
              Map<String, Object> p = call.getArgument(1);
              long id = ids.incrementAndGet();
              saved.put(
                  p.get("options").toString(),
                  Api.params("id", id, "batchNo", p.get("no"), "status", "READY"));
              return id;
            });
    byte[] source = statement(event("P1", "2026-10-20", "M1"), event("P2", "2026-10-21", "M1"));
    var narrow =
        (Map<String, Object>)
            service.statement(source, "xml", 1L, "2026-10-20", "2026-10-20", "UNKNOWN", false);
    var wide =
        (Map<String, Object>)
            service.statement(source, "xml", 1L, "2026-10-20", "2026-10-21", "UNKNOWN", false);
    var repeat =
        (Map<String, Object>)
            service.statement(source, "xml", 1L, "2026-10-20", "2026-10-21", "UNKNOWN", false);
    assertEquals(1, narrow.get("accepted"));
    assertEquals(2, wide.get("accepted"));
    assertNotEquals(
        ((Map<?, ?>) narrow.get("batch")).get("id"), ((Map<?, ?>) wide.get("batch")).get("id"));
    assertEquals(true, repeat.get("reused"));
    verify(db, times(2)).insert(anyString(), anyMap());
    verify(db, times(3)).exec(startsWith("INSERT statement_line"), anyMap());
  }

  @Test
  @SuppressWarnings("unchecked")
  void platformPreviewSplitsKnownMerchantsAndQuarantinesUnknownRowsWithoutWriting()
      throws Exception {
    when(db.one(contains("WHERE p.out_trade_no="), anyMap()))
        .thenAnswer(
            call -> {
              Map<String, Object> p = call.getArgument(1);
              return switch (p.get("key").toString()) {
                case "P1" -> owner(1, 1, "P1");
                case "P2" -> owner(2, 2, "P2");
                default -> null;
              };
            });
    var result =
        (Map<String, Object>)
            service.statement(
                statement(
                    event("P1", "2026-10-20", "M1"),
                    event("P2", "2026-10-20", "M2"),
                    event("UNKNOWN", "2026-10-20", "UNTRUSTED")),
                "xml",
                null,
                "2026-10-20",
                "2026-10-20",
                "DECLARED_COMPLETE",
                true);
    assertEquals(3, result.get("accepted"));
    assertEquals(1, result.get("unassigned"));
    assertEquals(3, ((List<?>) result.get("partitions")).size());
    verify(access).role("PLATFORM_FINANCE");
    verify(db, never()).insert(anyString(), anyMap());
    verify(db, never()).exec(anyString(), anyMap());
  }
}
