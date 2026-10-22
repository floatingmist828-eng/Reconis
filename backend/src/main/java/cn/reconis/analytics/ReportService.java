package cn.reconis.analytics;

import cn.reconis.common.*;
import java.awt.Font;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.jfree.chart.*;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  private final String fontFile;

  public ReportService(@Value("${reconis.font-file}") String file) {
    fontFile = file;
  }

  public byte[] xlsx(List<Map<String, Object>> rows, List<String> headers) throws Exception {
    try (var book = new SXSSFWorkbook(200);
        var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("Reconis");
      var head = sheet.createRow(0);
      for (int i = 0; i < headers.size(); i++) {
        head.createCell(i).setCellValue(headers.get(i));
        sheet.setColumnWidth(i, Math.min(50, Math.max(18, headers.get(i).length() + 3)) * 256);
      }
      int n = 1;
      for (var row : rows) {
        var line = sheet.createRow(n++);
        for (int i = 0; i < headers.size(); i++)
          line.createCell(i).setCellValue(Objects.toString(row.get(headers.get(i)), ""));
      }
      sheet.createFreezePane(0, 1);
      book.write(out);
      return out.toByteArray();
    }
  }

  public byte[] xml(List<Map<String, Object>> rows, String root, String child) throws Exception {
    var out = new ByteArrayOutputStream();
    var writer = javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
    writer.writeStartDocument("UTF-8", "1.0");
    writer.writeStartElement(root);
    writer.writeAttribute("schemaVersion", "1");
    for (var row : rows) {
      writer.writeStartElement(child);
      for (var e : row.entrySet()) {
        writer.writeStartElement(e.getKey());
        writer.writeCharacters(Objects.toString(e.getValue(), ""));
        writer.writeEndElement();
      }
      writer.writeEndElement();
    }
    writer.writeEndElement();
    writer.writeEndDocument();
    writer.close();
    return out.toByteArray();
  }

  @SuppressWarnings("unchecked")
  public byte[] report(String format, Map<String, Object> data, String environment)
      throws Exception {
    var groups = (List<Map<String, Object>>) data.get("groups");
    var summary = (Map<String, Object>) data.get("summary");
    var filters = (Map<String, String>) data.get("filters");
    String period =
        filters.containsKey("start") ? filters.get("start") + " 至 " + filters.get("end") : "近30日";
    String scope = Objects.toString(data.get("scopeDescription"), "全部可访问商户；全部门店；单笔金额不限");
    String dimension =
        Map.of("merchant", "商户", "store", "门店", "year", "年", "month", "月", "day", "日")
            .getOrDefault(Objects.toString(data.get("dimension"), "merchant"), "商户");
    String asOf =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.Instant.parse(data.get("asOf").toString()));
    List<String> lines =
        new ArrayList<>(
            List.of(
                "Reconis 收退款分析报告",
                "环境：" + environment,
                "统计期间：" + period,
                scope,
                "数据截止：" + asOf + "；汇总维度：" + dimension,
                "按交易发生日期及截止时间前已入账事实统计；净收款 = 收款 - 退款",
                "图表覆盖全部 " + groups.size() + " 个分组，每图最多12组；下表列出完整结果",
                "收款："
                    + money(summary.get("receiptCent"))
                    + " 元    退款："
                    + money(summary.get("refundCent"))
                    + " 元    净收款："
                    + money(summary.get("netCent"))
                    + " 元",
                "分组 | 事件数 | 收款(元) | 退款(元) | 净收款(元)"));
    int metadataLines = lines.size() - 1;
    for (var g : groups)
      lines.add(
          g.get("groupName")
              + " | "
              + g.get("eventCount")
              + " | "
              + money(g.get("receiptCent"))
              + " | "
              + money(g.get("refundCent"))
              + " | "
              + money(g.get("netCent")));
    List<byte[]> charts = new ArrayList<>();
    for (int offset = 0; offset < groups.size(); offset += 12)
      charts.add(chart(groups.subList(offset, Math.min(offset + 12, groups.size()))));
    if (charts.isEmpty()) charts.add(chart(List.of()));
    if (format.equals("docx")) {
      try (var doc = new XWPFDocument();
          var out = new ByteArrayOutputStream()) {
        for (int n = 0; n < metadataLines; n++) {
          var p = doc.createParagraph();
          var r = p.createRun();
          r.setFontFamily("Microsoft YaHei");
          r.setFontSize(n == 0 ? 20 : 10);
          r.setText(lines.get(n));
        }
        for (int i = 0; i < charts.size(); i++) {
          doc.createParagraph()
              .createRun()
              .setText(
                  "收退款分布：第 "
                      + (i + 1)
                      + " / "
                      + charts.size()
                      + " 图（分组 "
                      + (groups.isEmpty() ? 0 : i * 12 + 1)
                      + "–"
                      + Math.min((i + 1) * 12, groups.size())
                      + "）");
          var picture = doc.createParagraph().createRun();
          picture.addPicture(
              new ByteArrayInputStream(charts.get(i)),
              org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
              "chart-" + i + ".png",
              Units.toEMU(460),
              Units.toEMU(200));
        }
        var table = doc.createTable(groups.size() + 1, 5);
        String[] headers = {"分组", "事件数", "收款（元）", "退款（元）", "净收款（元）"};
        for (int i = 0; i < 5; i++) table.getRow(0).getCell(i).setText(headers[i]);
        table.getRow(0).setRepeatHeader(true);
        for (int n = 0; n < groups.size(); n++) {
          var g = groups.get(n);
          String[] values = {
            g.get("groupName").toString(),
            g.get("eventCount").toString(),
            money(g.get("receiptCent")),
            money(g.get("refundCent")),
            money(g.get("netCent"))
          };
          for (int i = 0; i < 5; i++) table.getRow(n + 1).getCell(i).setText(values[i]);
        }
        var footer =
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                .createParagraph();
        footer.createRun().setText("Reconis · " + environment + " · ");
        footer.getCTP().addNewFldSimple().setInstr("PAGE");
        doc.write(out);
        return out.toByteArray();
      }
    }
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream();
        var fonts = fontFile.endsWith(".ttc") ? new TrueTypeCollection(new File(fontFile)) : null) {
      PDType0Font[] selected = new PDType0Font[1];
      if (fontFile.endsWith(".ttc")) {
        fonts.processAllFonts(
            f -> {
              if (selected[0] == null) selected[0] = PDType0Font.load(doc, f, true);
            });
      } else
        try (var in = Files.newInputStream(Path.of(fontFile))) {
          selected[0] = PDType0Font.load(doc, in);
        }
      var wrapped = lines.stream().flatMap(line -> wrap(line, 48).stream()).toList();
      int pageNo = 0;
      for (int offset = 0; offset < wrapped.size(); offset += 38) {
        var page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (var c = new PDPageContentStream(doc, page)) {
          int y = 790;
          c.beginText();
          c.setFont(selected[0], 10);
          c.newLineAtOffset(42, y);
          for (String line : wrapped.subList(offset, Math.min(offset + 38, wrapped.size()))) {
            c.setFont(selected[0], offset == 0 && y == 790 ? 16 : 10);
            c.setNonStrokingColor(
                offset == 0 && y == 790
                    ? new java.awt.Color(18, 60, 53)
                    : new java.awt.Color(62, 74, 63));
            c.showText(line);
            int spacing = offset == 0 && y == 790 ? 29 : 18;
            c.newLineAtOffset(0, -spacing);
            y -= spacing;
          }
          c.endText();
          c.beginText();
          c.setFont(selected[0], 9);
          c.newLineAtOffset(42, 30);
          c.showText("Reconis · " + environment + " · " + (++pageNo));
          c.endText();
        }
      }
      for (int i = 0; i < charts.size(); i++) {
        var chartPage = new PDPage(PDRectangle.A4);
        doc.addPage(chartPage);
        try (var c = new PDPageContentStream(doc, chartPage)) {
          c.drawImage(
              PDImageXObject.createFromByteArray(doc, charts.get(i), "chart-" + i),
              42,
              450,
              510,
              210);
          c.beginText();
          c.setFont(selected[0], 10);
          c.newLineAtOffset(42, 710);
          c.showText(
              "收退款分布（元）：第 "
                  + (i + 1)
                  + " / "
                  + charts.size()
                  + " 图，分组 "
                  + (groups.isEmpty() ? 0 : i * 12 + 1)
                  + "–"
                  + Math.min((i + 1) * 12, groups.size()));
          c.endText();
          c.beginText();
          c.setFont(selected[0], 9);
          c.newLineAtOffset(42, 30);
          c.showText("Reconis · " + environment + " · " + (++pageNo));
          c.endText();
        }
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  private List<String> wrap(String line, int size) {
    line = line.replaceAll("[\\p{Cntrl}]", " ");
    var parts = new ArrayList<String>();
    for (int i = 0; i < line.length(); i += size)
      parts.add(line.substring(i, Math.min(i + size, line.length())));
    return parts;
  }

  private byte[] chart(List<Map<String, Object>> rows) throws Exception {
    var set = chartData(rows);
    var chart = ChartFactory.createBarChart("收退款分布", "统计分组", "元", set);
    Font font = new Font("Microsoft YaHei", Font.PLAIN, 23);
    chart.getTitle().setFont(new Font("Microsoft YaHei", Font.BOLD, 28));
    chart.setBackgroundPaint(java.awt.Color.WHITE);
    chart.getLegend().setItemFont(font);
    var plot = chart.getCategoryPlot();
    plot.setBackgroundPaint(java.awt.Color.WHITE);
    plot.setRangeGridlinePaint(new java.awt.Color(227, 234, 224));
    plot.setOutlineVisible(false);
    var renderer = (org.jfree.chart.renderer.category.BarRenderer) plot.getRenderer();
    renderer.setSeriesPaint(0, new java.awt.Color(54, 132, 110));
    renderer.setSeriesPaint(1, new java.awt.Color(192, 165, 107));
    renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
    renderer.setShadowVisible(false);
    plot.getDomainAxis().setTickLabelFont(font);
    plot.getDomainAxis().setLabelFont(font);
    plot.getRangeAxis().setLabelFont(font);
    plot.getRangeAxis().setTickLabelFont(font);
    var out = new ByteArrayOutputStream();
    ChartUtils.writeChartAsPNG(out, chart, 1100, 450);
    return out.toByteArray();
  }

  static DefaultCategoryDataset chartData(List<Map<String, Object>> rows) {
    var set = new DefaultCategoryDataset();
    for (var row : rows) {
      String category = row.get("groupName") + " (#" + row.get("groupKey") + ")";
      set.addValue(
          new java.math.BigDecimal(Objects.toString(row.get("receiptCent"), "0")).movePointLeft(2),
          "收款",
          category);
      set.addValue(
          new java.math.BigDecimal(Objects.toString(row.get("refundCent"), "0")).movePointLeft(2),
          "退款",
          category);
    }
    return set;
  }

  private String money(Object v) {
    return new java.math.BigDecimal(v == null ? "0" : v.toString())
        .movePointLeft(2)
        .toPlainString();
  }
}
