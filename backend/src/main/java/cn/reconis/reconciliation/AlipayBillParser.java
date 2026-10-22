package cn.reconis.reconciliation;

import cn.reconis.common.Api;
import cn.reconis.payment.PaymentGateway;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/**
 * Adapter for Alipay trade-detail bills, not account-balance or settlement summaries. Field
 * mapping: https://opendocs.alipay.com/open/00dn7a (bill_type=trade). Unknown layouts fail
 * explicitly; they must not silently become successful imports.
 */
final class AlipayBillParser {
  static final int MAX_BYTES = 20 * 1024 * 1024;
  static final int MAX_ROWS = 10000;
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
          .withResolverStyle(java.time.format.ResolverStyle.STRICT);

  private AlipayBillParser() {}

  static List<Map<String, Object>> parse(byte[] bytes, String format, String environment)
      throws IOException {
    return parse(bytes, format, environment, false);
  }

  static List<Map<String, Object>> parse(
      byte[] bytes, String format, String environment, boolean allowEmpty) throws IOException {
    if (bytes.length > MAX_BYTES) throw Api.fail(422, "账单文件不能超过20MB");
    var rows = new ArrayList<Map<String, Object>>();
    boolean foundDetail = false;
    if (format.equals("csv")) {
      csv(bytes, "账单.csv", environment, rows, false);
      foundDetail = true;
    } else {
      // Nothing is extracted onto disk, so ZIP paths cannot escape the data directory.
      try (var zip =
          new ZipInputStream(new ByteArrayInputStream(bytes), Charset.forName("GB18030"))) {
        int entries = 0, expanded = 0;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          if (++entries > 32) throw Api.fail(422, "ZIP最多包含32个文件");
          if (entry.isDirectory()) continue;
          var content = new ByteArrayOutputStream();
          byte[] buffer = new byte[8192];
          int n;
          while ((n = zip.read(buffer)) != -1) {
            expanded += n;
            if (expanded > MAX_BYTES) throw Api.fail(422, "ZIP解压后总大小不能超过20MB");
            content.write(buffer, 0, n);
          }
          String name = entry.getName();
          if (!name.toLowerCase(Locale.ROOT).endsWith(".csv"))
            throw Api.fail(422, "ZIP仅接受支付宝CSV明细及汇总文件");
          if (!name.contains("汇总")) {
            csv(content.toByteArray(), name, environment, rows, true);
            foundDetail = true;
          }
        }
      } catch (ZipException | IllegalArgumentException ex) {
        throw Api.fail(422, "ZIP文件损坏或文件名编码不支持");
      }
    }
    if (!foundDetail) throw Api.fail(422, "未找到支付宝交易明细；请上传交易账单，不能使用资金账单或汇总表");
    if (rows.isEmpty() && !allowEmpty) throw Api.fail(422, "交易明细没有数据；空账单必须明确声明账期完整");
    return rows;
  }

  private static String decode(byte[] bytes) {
    for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"))) {
      try {
        return charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
      } catch (CharacterCodingException ignored) {
      }
    }
    throw Api.fail(422, "CSV必须使用UTF-8或GB18030/GBK编码");
  }

  private static void csv(
      byte[] bytes,
      String source,
      String environment,
      List<Map<String, Object>> rows,
      boolean inZip) {
    List<CsvRow> records = records(decode(bytes));
    Map<String, Integer> headers = null;
    for (var record : records) {
      List<String> cells = record.cells();
      String first = cells.isEmpty() ? "" : cells.getFirst();
      if (cells.stream().allMatch(String::isBlank) || first.startsWith("#")) continue;
      if (headers == null) {
        if (!cells.contains("支付宝交易号") || !cells.contains("商户订单号")) continue;
        headers = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
          String key = header(cells.get(i));
          if (!key.isEmpty() && headers.put(key, i) != null) throw Api.fail(422, "CSV表头重复：" + key);
        }
        for (String required : List.of("支付宝交易号", "商户订单号", "业务类型", "订单金额(元)", "完成时间"))
          if (!headers.containsKey(required)) throw Api.fail(422, "交易明细缺少字段：" + required);
        continue;
      }
      if (first.startsWith("合计") || first.startsWith("总计")) continue;
      if (rows.size() >= MAX_ROWS) throw Api.fail(422, "每次最多导入10000行");
      var row = new LinkedHashMap<String, Object>();
      row.put("line", rows.size() + 1);
      row.put("sourceFile", source);
      row.put("sourceLine", record.line());
      row.put("schema_version", "1");
      row.put("channel_env", environment);
      row.put("merchant_code", "");
      row.put("out_trade_no", value(cells, headers, "商户订单号"));
      row.put("channel_trade_no", value(cells, headers, "支付宝交易号"));
      row.put("out_request_no", value(cells, headers, "退款批次号/请求号", "退款批次号"));
      try {
        String type =
            switch (value(cells, headers, "业务类型")) {
              case "交易", "收款" -> "RECEIPT";
              case "退款" -> "REFUND";
              default -> throw Api.fail(422, "仅支持交易/收款/退款明细，无法识别此业务类型");
            };
        row.put("event_type", type);
        long signedAmount = PaymentGateway.cent(value(cells, headers, "订单金额(元)"));
        if (signedAmount == Long.MIN_VALUE
            || signedAmount == 0
            || (type.equals("RECEIPT") && signedAmount < 0))
          throw Api.fail(422, "交易金额必须为正，退款金额须非零，精确到分");
        row.put("amount_cent", Long.toString(Math.abs(signedAmount)));
        row.put(
            "occurred_at",
            LocalDateTime.parse(value(cells, headers, "完成时间"), TIME)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toOffsetDateTime()
                .toString());
      } catch (org.springframework.web.server.ResponseStatusException e) {
        row.put("parseError", Objects.toString(e.getReason()));
      } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
        row.put("parseError", "金额或完成时间格式错误，金额单位为元，时间格式为yyyy-MM-dd HH:mm:ss");
      }
      rows.add(row);
    }
    if (headers == null && !inZip) throw Api.fail(422, "找不到支付宝交易明细表头，请下载中文交易账单明细");
    if (headers == null && inZip)
      throw Api.fail(422, "ZIP中存在无法识别的CSV：" + source + "；请只保留交易明细及汇总文件");
  }

  private static String header(String value) {
    return value.replace('（', '(').replace('）', ')');
  }

  private static String value(List<String> cells, Map<String, Integer> headers, String... keys) {
    for (String key : keys) {
      Integer index = headers.get(key);
      if (index != null && index < cells.size()) return cells.get(index);
    }
    return "";
  }

  private record CsvRow(int line, List<String> cells) {}

  /** RFC-style quoting, including escaped quotes and quoted newlines. */
  private static List<CsvRow> records(String text) {
    var records = new ArrayList<CsvRow>();
    var fields = new ArrayList<String>();
    var cell = new StringBuilder();
    boolean quoted = false, closed = false;
    int line = 1, start = 1;
    for (int i = 0; i <= text.length(); i++) {
      char c = i == text.length() ? '\n' : text.charAt(i);
      if (quoted) {
        if (i == text.length()) throw Api.fail(422, "CSV引号未闭合，起始行：" + start);
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
            closed = true;
          }
        } else {
          cell.append(c);
          if (c == '\n') line++;
        }
      } else if (c == '"') {
        if (!cell.toString().isBlank() || closed) throw Api.fail(422, "CSV引号位置不合法，行：" + line);
        cell.setLength(0);
        quoted = true;
      } else if (c == ',' || c == '\n' || c == '\r') {
        String value = cell.toString().replace("\uFEFF", "").strip();
        if (value.startsWith("`")) value = value.substring(1);
        fields.add(value);
        if (fields.size() > 128) throw Api.fail(422, "CSV列数超过128");
        cell.setLength(0);
        closed = false;
        if (c != ',') {
          records.add(new CsvRow(start, List.copyOf(fields)));
          fields.clear();
          if (records.size() > MAX_ROWS + 100) throw Api.fail(422, "CSV记录数超过限制");
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
          line++;
          start = line;
        }
      } else {
        if (closed && !Character.isWhitespace(c)) throw Api.fail(422, "CSV引号后存在非法字符，行：" + line);
        cell.append(c);
      }
      if (cell.length() > 32768) throw Api.fail(422, "CSV单元格过长");
    }
    return records;
  }
}
