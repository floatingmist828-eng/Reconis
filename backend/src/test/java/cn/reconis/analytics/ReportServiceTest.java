package cn.reconis.analytics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ReportServiceTest {
  private Map<String, Object> group(int id) {
    return Map.of(
        "groupKey",
        "" + id,
        "groupName",
        "同名门店",
        "eventCount",
        1L,
        "receiptCent",
        (long) id * 100,
        "refundCent",
        0L,
        "netCent",
        (long) id * 100);
  }

  @Test
  void identicalNamesRetainSeparateChartValues() {
    var dataset = ReportService.chartData(List.of(group(1), group(2)));
    assertEquals(2, dataset.getColumnCount());
    assertEquals(1.0, dataset.getValue(0, 0).doubleValue());
    assertEquals(2.0, dataset.getValue(0, 1).doubleValue());
  }

  @Test
  void wordExportIncludesEveryGroupChartAndActualFilters() throws Exception {
    var groups = new ArrayList<Map<String, Object>>();
    for (int id = 1; id <= 13; id++) groups.add(group(id));
    var data = new HashMap<String, Object>();
    data.put("groups", groups);
    data.put("summary", Map.of("receiptCent", 9100L, "refundCent", 0L, "netCent", 9100L));
    data.put("filters", Map.of("start", "2026-10-15", "end", "2026-10-21"));
    data.put("scopeDescription", "商户：测试食堂 [M1]；门店：一号窗口 [S1]；单笔金额（元）：5 至 20");
    data.put("dimension", "store");
    data.put("asOf", "2026-10-21T08:00:00Z");
    byte[] bytes = new ReportService("unused-for-docx.ttf").report("docx", data, "DEMO");
    try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      String text =
          doc.getParagraphs().stream().map(p -> p.getText()).reduce("", (a, b) -> a + "\n" + b);
      assertTrue(text.contains("测试食堂 [M1]"));
      assertTrue(text.contains("一号窗口 [S1]"));
      assertTrue(text.contains("5 至 20"));
      assertTrue(text.contains("2026-10-15 至 2026-10-21"));
      assertEquals(2, doc.getAllPictures().size());
      assertEquals(14, doc.getTables().getFirst().getNumberOfRows());
      assertEquals("13.00", doc.getTables().getFirst().getRow(13).getCell(2).getText());
    }
  }
}
