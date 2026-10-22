package cn.reconis.reconciliation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import cn.reconis.payment.PaymentGateway;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AutoReconciliationTest {
  @TempDir Path directory;

  @Test void downloaderRejectsLocalUrlsRedirectTricksAndPlainHttp() {
    String resource = "/downloadBillFile.resource?fileId=abc";
    for (String value : List.of("http://dwbillcenter.alipay.com" + resource,
        "http://dwbillcenter.alipaydev.com" + resource, "https://127.0.0.1" + resource,
        "https://[::1]" + resource, "https://dwbillcenter.alipay.com.evil.test" + resource,
        "https://evil@dwbillcenter.alipay.com" + resource, "https://dwbillcenter.alipay.com:8080" + resource,
        "file:///etc/passwd", "https://dwbillcenter.alipay.com" + resource + "#x"))
      assertThrows(ResponseStatusException.class, () -> BillDownloader.approved(value), value);
    assertEquals("dwbillcenter.alipay.com", BillDownloader.approved("https://dwbillcenter.alipay.com/downloadBillFile.resource?fileId=abc").getHost());
  }

  @Test void sandboxHttpAddressIsUpgradedWithoutChangingSignedQuery() {
    String query = "fileId=%2Ftrade%2F20261021.csv.zip&token=a%2Bb%2Fc%3D&name=some+bill&nested=%252F&key=1&key=2";
    String host = "dwbillcenter-sandbox.dl.alipaydev.com";
    for (String port : List.of("", ":80")) {
      var uri = BillDownloader.approved("http://" + host + port + "/downloadBillFile.resource?" + query);
      assertEquals("https", uri.getScheme());
      assertEquals(-1, uri.getPort());
      assertEquals(query, uri.getRawQuery());
      assertEquals("https://" + host + "/downloadBillFile.resource?" + query, uri.toASCIIString());
    }
  }

  @Test void downloaderAcceptsExactHttpsBillEndpoints() {
    for (String host : List.of("dwbillcenter.alipay.com", "dwbillcenter.alipaydev.com",
        "dwbillcenter-sandbox.dl.alipaydev.com")) {
      for (String port : List.of("", ":443")) {
        String value = "https://" + host + port + "/downloadBillFile.resource?fileId=%2Fa";
        assertEquals(value, BillDownloader.approved(value).toASCIIString());
      }
    }
  }

  @Test void sandboxCompatibilityCannotWidenHostPathOrProtocolPermissions() {
    String host = "dwbillcenter-sandbox.dl.alipaydev.com";
    String resource = "/downloadBillFile.resource?fileId=abc";
    for (String value : List.of("http://" + host + ".evil.test" + resource,
        "https://evil-" + host + resource, "https://" + host + "." + resource,
        "http://user@" + host + resource, "https://user@" + host + resource,
        "http://" + host + resource + "#x", "https://" + host + resource + "#x",
        "http://" + host + ":443" + resource, "http://" + host + ":8080" + resource,
        "https://" + host + ":80" + resource, "https://" + host + ":8443" + resource,
        "ftp://" + host + resource, "http://" + host + "/other.resource",
        "https://" + host + "/other.resource", "https://" + host + "/./downloadBillFile.resource",
        "http://" + host + "/download%42illFile.resource", "https://" + host + "/%64ownloadBillFile.resource",
        "https://" + host + "/downloadBillFile.resource%2f..%2fother",
        "https://" + host + "/downloadBillFile.resource/", "https://" + host + "/downloadBillFile.resource;other"))
      assertThrows(ResponseStatusException.class, () -> BillDownloader.approved(value), value);
  }

  @Test void onlyClosedDaysAndBoundedBackfillsAreAccepted() {
    LocalDate today=LocalDate.of(2026,10,22);
    AutoReconciliationService.validateRange(today.minusDays(31),today.minusDays(1),today);
    for (LocalDate[] range : List.of(new LocalDate[]{today,today},new LocalDate[]{today.minusDays(32),today.minusDays(1)},
        new LocalDate[]{today.minusDays(1),today.minusDays(2)},new LocalDate[]{today.minusYears(7),today.minusYears(7)}))
      assertThrows(ResponseStatusException.class,()->AutoReconciliationService.validateRange(range[0],range[1],today));
  }

  @Test void generatedBillUsesIndependentChannelSuccessIncludingLostNotifications() throws Exception {
    var files=new FileStore(directory.resolve("demo").toString(),"DEMO","jdbc:sqlserver://local;databaseName=ReconisDemo");
    files.jsonWrite("channel/LOST_NOTIFY.json",Map.of("status","SUCCEEDED","tradeNo","DEMO_LOST","amountCent","1234","occurredAt","2026-10-20T01:02:03Z"));
    files.jsonWrite("channel/PENDING.json",Map.of("status","PENDING","amountCent","999"));
    files.jsonWrite("channel/NEXT_DAY.json",Map.of("status","SUCCEEDED","tradeNo","DEMO_NEXT","amountCent","300","occurredAt","2026-10-20T16:00:00Z"));
    var db=mock(Db.class);
    when(db.one(anyString(),anyMap())).thenReturn(null);
    var gateway=new PaymentGateway(files,"DEMO","","","","","");
    var service=new AutoReconciliationService(db,files,mock(Access.class),gateway,mock(ImportService.class),mock(TransactionTemplate.class));
    byte[] bytes=service.demoBill(LocalDate.of(2026,10,20));
    String text=new String(bytes,StandardCharsets.UTF_8);
    assertTrue(text.contains("DEMO_SIMULATED"));
    var rows=new ImportService(db,files,mock(Access.class),gateway).parse(bytes,"xml","statement");
    assertEquals(1,rows.size()); assertEquals("LOST_NOTIFY",rows.getFirst().get("out_trade_no"));
    assertEquals("1234",rows.getFirst().get("amount_cent"));
    verify(db,never()).list(anyString(),anyMap()); // No local ledger can be used to synthesize a matching bill.
  }

  @Test void incompleteSuccessfulChannelRecordCannotBecomeCompleteEmptyBill() throws Exception {
    var files=new FileStore(directory.resolve("demo").toString(),"DEMO","jdbc:sqlserver://local;databaseName=ReconisDemo");
    files.jsonWrite("channel/BROKEN.json",Map.of("status","SUCCEEDED","amountCent","100"));
    var service=new AutoReconciliationService(mock(Db.class),files,mock(Access.class),new PaymentGateway(files,"DEMO","","","","",""),mock(ImportService.class),mock(TransactionTemplate.class));
    assertThrows(ResponseStatusException.class,()->service.demoBill(LocalDate.of(2026,10,20)));
  }

  @Test void retriesBackOffWithoutUnboundedAttempts() {
    assertEquals(5,AutoReconciliationService.retryDelayMinutes(1));
    assertEquals(30,AutoReconciliationService.retryDelayMinutes(2));
    assertEquals(120,AutoReconciliationService.retryDelayMinutes(3));
  }
}
