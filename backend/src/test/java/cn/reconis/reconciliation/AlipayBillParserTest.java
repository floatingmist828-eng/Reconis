package cn.reconis.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AlipayBillParserTest {
  private static final String HEADER = "支付宝交易号,商户订单号,业务类型,订单金额（元）,完成时间,退款批次号/请求号,商品名称\n";

  @Test
  void completeEmptyNativeBillRequiresValidDetailHeaderAndExplicitOptIn() throws Exception {
    byte[] bytes = HEADER.getBytes(StandardCharsets.UTF_8);
    assertThrows(ResponseStatusException.class, () -> AlipayBillParser.parse(bytes, "csv", "DEMO"));
    assertTrue(AlipayBillParser.parse(bytes, "csv", "DEMO", true).isEmpty());
    assertThrows(
        ResponseStatusException.class,
        () ->
            AlipayBillParser.parse(
                "#仅汇总\n总金额,总笔数\n0,0".getBytes(StandardCharsets.UTF_8), "csv", "DEMO", true));
  }

  @Test
  void missingChannelIdDoesNotSilentlyDiscardAnOtherwisePresentRow() throws Exception {
    var rows =
        AlipayBillParser.parse(
            (HEADER + ",P1,交易,1.00,2026-10-20 12:00:00,,午餐\n").getBytes(StandardCharsets.UTF_8),
            "csv",
            "DEMO");
    assertEquals(1, rows.size());
    assertEquals("P1", rows.getFirst().get("out_trade_no"));
  }

  @Test
  void acceptsUtf8BomQuotedCommasAndMultilineCells() throws Exception {
    String csv =
        "\uFEFF#支付宝交易明细\n"
            + HEADER
            + "20260001\t,PAY_1,交易,10.15,2026-10-20 00:00:01,,\"午餐,套餐\n第二行\"\n";
    var rows =
        AlipayBillParser.parse(csv.getBytes(StandardCharsets.UTF_8), "csv", "ALIPAY_SANDBOX");
    assertEquals(1, rows.size());
    assertEquals("1015", rows.getFirst().get("amount_cent"));
    assertEquals("2026-10-20T00:00:01+08:00", rows.getFirst().get("occurred_at"));
    assertEquals("20260001", rows.getFirst().get("channel_trade_no"));
  }

  @Test
  void acceptsGbkRefundAndPreservesRefundRequestKey() throws Exception {
    String csv = HEADER + "20260001,PAY_1,退款,-3.25,2026-10-20 23:59:59,REF_1,退款\n";
    var row =
        AlipayBillParser.parse(csv.getBytes(Charset.forName("GBK")), "csv", "DEMO").getFirst();
    assertEquals("REFUND", row.get("event_type"));
    assertEquals("325", row.get("amount_cent"));
    assertEquals("REF_1", row.get("out_request_no"));
  }

  @Test
  void rejectsNonIntegralCentAndInvalidCalendarDatePerRow() throws Exception {
    String csv =
        HEADER
            + "T1,P1,交易,1.001,2026-10-20 12:00:00,,x\n"
            + "T2,P2,交易,1.00,2026-02-30 12:00:00,,x\n";
    var rows = AlipayBillParser.parse(csv.getBytes(StandardCharsets.UTF_8), "csv", "DEMO");
    assertTrue(rows.stream().allMatch(r -> r.containsKey("parseError")));
  }

  @Test
  void rejectsUnrecognizedFundsLayoutAndUnclosedQuotes() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            AlipayBillParser.parse(
                "账务流水号,收入,支出\n123,1,0".getBytes(StandardCharsets.UTF_8), "csv", "DEMO"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            AlipayBillParser.parse(
                (HEADER + "T,P,交易,1,2026-10-20 12:00:00,,\"broken")
                    .getBytes(StandardCharsets.UTF_8),
                "csv",
                "DEMO"));
  }

  @Test
  void readsOnlyDetailFilesFromZipAndUsesGlobalLineNumbers() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (String file : new String[] {"账单/交易汇总.csv", "账单/交易明细1.csv", "账单/交易明细2.csv"}) {
        zip.putNextEntry(new ZipEntry(file));
        zip.write(
            (file.contains("汇总") ? "汇总数据" : HEADER + "T,P,交易,1,2026-10-20 12:00:00,,x")
                .getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    var rows = AlipayBillParser.parse(bytes.toByteArray(), "zip", "DEMO");
    assertEquals(2, rows.size());
    assertEquals(1, rows.get(0).get("line"));
    assertEquals(2, rows.get(1).get("line"));
    assertEquals("账单/交易明细2.csv", rows.get(1).get("sourceFile"));
  }

  @Test
  void rejectsZipBombBeforeAllocatingExpandedArchive() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("交易明细.csv"));
      byte[] block = new byte[1024 * 1024];
      for (int i = 0; i < 21; i++) zip.write(block);
      zip.closeEntry();
    }
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> AlipayBillParser.parse(bytes.toByteArray(), "zip", "DEMO"));
    assertTrue(error.getReason().contains("解压"));
  }
}
