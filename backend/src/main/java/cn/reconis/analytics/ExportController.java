package cn.reconis.analytics;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import cn.reconis.payment.PaymentGateway;
import cn.reconis.reconciliation.ImportService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ExportController {
  private final Db db;
  private final Access access;
  private final AnalyticsService analytics;
  private final ReportService report;
  private final FileStore files;
  private final PaymentGateway gateway;

  public ExportController(
      Db db,
      Access access,
      AnalyticsService analytics,
      ReportService report,
      FileStore files,
      PaymentGateway gateway) {
    this.db = db;
    this.access = access;
    this.analytics = analytics;
    this.report = report;
    this.files = files;
    this.gateway = gateway;
  }

  @GetMapping("/templates")
  Object template(
      @RequestParam(defaultValue = "statement") String kind,
      @RequestParam(defaultValue = "xlsx") String format)
      throws Exception {
    access.actor();
    boolean merchant = kind.equals("merchants");
    byte[] bytes;
    if (format.equals("xlsx")) {
      bytes =
          report.xlsx(
              List.of(),
              merchant
                  ? List.of("merchant_code", "name", "category_code", "contact")
                  : ImportService.HEADERS);
    } else if (format.equals("xml")) {
      String body =
          merchant
              ? "<?xml version=\"1.0\" encoding=\"UTF-8\"?><merchants schemaVersion=\"1\"><merchant"
                    + " code=\"EXAMPLE_M001\"><name>示例商户</name><categoryCode>咖啡</categoryCode><contact>待填写</contact></merchant></merchants>"
              : "<?xml version=\"1.0\" encoding=\"UTF-8\"?><statement schemaVersion=\"1\""
                  + " channelEnv=\""
                  + gateway.environment
                  + "\"><event merchantCode=\"请填写商户编号\""
                  + " type=\"RECEIPT\"><outTradeNo>请填写系统支付号</outTradeNo><channelTradeNo>请填写渠道交易号</channelTradeNo><outRequestNo></outRequestNo><amountCent>10000</amountCent><occurredAt>2026-10-18T15:00:00+08:00</occurredAt></event></statement>";
      bytes = body.getBytes(StandardCharsets.UTF_8);
    } else throw Api.fail(422, "模板支持 XLSX/XML");
    return attachment(bytes, "Reconis-" + kind + "-template." + format, format);
  }

  @SuppressWarnings("unchecked")
  @PostMapping("/exports")
  Object export(@RequestBody Map<String, Object> b) throws Exception {
    String format = Api.required(b, "format"), kind = Api.required(b, "kind");
    if (!Set.of("xlsx", "xml", "pdf", "docx").contains(format)) throw Api.fail(422, "导出格式不支持");
    if (!Set.of("merchants", "transactions", "reconciliation").contains(kind))
      throw Api.fail(422, "导出对象无效");
    if ((format.equals("pdf") || format.equals("docx")) && !kind.equals("transactions"))
      throw Api.fail(422, "PDF/Word 分析报告仅支持交易统计；请为此对象选择 Excel 或 XML");
    Map<String, String> filters = new HashMap<>();
    if (b.get("filters") instanceof Map<?, ?> map)
      map.forEach(
          (k, v) -> {
            if (v != null && !v.toString().isBlank()) filters.put(k.toString(), v.toString());
          });
    byte[] bytes;
    var scopes = new ArrayList<Object>();
    List<Map<String, Object>> rows;
    if (format.equals("pdf") || format.equals("docx")) {
      bytes = report.report(format, analytics.transactions(filters), gateway.environment);
    } else {
      if (kind.equals("merchants")) {
        var p = new HashMap<String, Object>();
        String where = access.actor().role().equals("ADMIN") ? "1=1" : access.scope("id", p);
        rows =
            db.list(
                "SELECT merchant_code,name,category_code,contact FROM merchant WHERE " + where, p);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (var row : rows)
          mapped.add(
              Api.params(
                  "merchant_code",
                  row.get("merchantCode"),
                  "name",
                  row.get("name"),
                  "category_code",
                  row.get("categoryCode"),
                  "contact",
                  row.get("contact")));
        if (format.equals("xlsx"))
          bytes = report.xlsx(mapped, List.of("merchant_code", "name", "category_code", "contact"));
        else bytes = merchantXml(mapped);
      } else if (kind.equals("transactions")) {
        rows = analytics.exportDetails(filters);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (var r : rows) {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("schema_version", "1");
          m.put("merchant_code", r.get("merchantCode"));
          m.put("channel_env", gateway.environment);
          m.put("event_type", r.get("entryType"));
          m.put("out_trade_no", r.get("outTradeNo"));
          m.put("channel_trade_no", r.get("channelTradeNo"));
          m.put("out_request_no", r.get("outRequestNo"));
          m.put("amount_cent", r.get("amountCent").toString());
          m.put("occurred_at", Api.normalize(r.get("channelOccurredAt")));
          mapped.add(m);
        }
        bytes =
            format.equals("xlsx")
                ? report.xlsx(mapped, ImportService.HEADERS)
                : statementXml(mapped);
      } else if (kind.equals("reconciliation")) {
        long id = Long.parseLong(filters.getOrDefault("batchId", "0"));
        var batch = access.batch(id);
        if (batch.get("merchantId") != null) scopes.add(batch.get("merchantId"));
        rows =
            db.list(
                "SELECT r.*,e.amount_cent local_amount_cent,s.amount_cent channel_amount_cent FROM"
                    + " reconciliation_result r LEFT JOIN transaction_entry e ON e.id=r.entry_id"
                    + " LEFT JOIN statement_line s ON s.id=r.statement_line_id WHERE"
                    + " r.batch_id=#{p.id} ORDER BY r.id",
                Api.params("id", id));
        bytes =
            format.equals("xlsx")
                ? report.xlsx(
                    rows,
                    List.of(
                        "id",
                        "batchId",
                        "sourceEventKey",
                        "classification",
                        "reasonCode",
                        "localAmountCent",
                        "channelAmountCent"))
                : report.xml(rows, "reconciliation", "result");
      } else throw Api.fail(422, "导出对象无效");
    }
    if (scopes.isEmpty() && !access.actor().role().equals("ADMIN")) {
      var p = new HashMap<String, Object>();
      for (var r : db.list("SELECT id FROM merchant WHERE " + access.scope("id", p), p))
        scopes.add(r.get("id"));
    }
    String uri = files.write("exports", "." + format, bytes),
        id = Path.of(uri).getFileName().toString();
    String filename = "Reconis-" + gateway.environment + "-" + kind + "." + format;
    files.jsonWrite(
        uri + ".meta.json",
        Map.of(
            "actorId",
            access.actor().id(),
            "merchantIds",
            scopes,
            "uri",
            uri,
            "name",
            filename,
            "sha256",
            FileStore.sha(bytes),
            "filters",
            filters,
            "role",
            access.actor().role()));
    db.exec(
        "INSERT audit_log(event_category,actor_user_id,action,result,summary,request_id)"
            + " VALUES('IMPORT_EXPORT',#{p.actor},'EXPORT','SUCCESS',#{p.summary},#{p.req})",
        Api.params(
            "actor",
            access.actor().id(),
            "summary",
            kind + " / " + format + " / " + gateway.environment,
            "req",
            RequestContext.requestId()));
    return Api.ok(
        Map.of(
            "fileId",
            id,
            "filename",
            filename,
            "sha256",
            FileStore.sha(bytes),
            "url",
            "/api/v1/files/" + id));
  }

  private byte[] merchantXml(List<Map<String, Object>> rows) throws Exception {
    var out = new ByteArrayOutputStream();
    var w = javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
    w.writeStartDocument("UTF-8", "1.0");
    w.writeStartElement("merchants");
    w.writeAttribute("schemaVersion", "1");
    for (var r : rows) {
      w.writeStartElement("merchant");
      w.writeAttribute("code", Objects.toString(r.get("merchant_code"), ""));
      for (String k : List.of("name", "category_code", "contact")) {
        w.writeStartElement(k.equals("category_code") ? "categoryCode" : k);
        w.writeCharacters(Objects.toString(r.get(k), ""));
        w.writeEndElement();
      }
      w.writeEndElement();
    }
    w.writeEndElement();
    w.close();
    return out.toByteArray();
  }

  private byte[] statementXml(List<Map<String, Object>> rows) throws Exception {
    var out = new ByteArrayOutputStream();
    var w = javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
    w.writeStartDocument("UTF-8", "1.0");
    w.writeStartElement("statement");
    w.writeAttribute("schemaVersion", "1");
    w.writeAttribute("channelEnv", gateway.environment);
    for (var r : rows) {
      w.writeStartElement("event");
      w.writeAttribute("merchantCode", r.get("merchant_code").toString());
      w.writeAttribute("type", r.get("event_type").toString());
      for (String k :
          List.of(
              "out_trade_no", "channel_trade_no", "out_request_no", "amount_cent", "occurred_at")) {
        w.writeStartElement(
            Map.of(
                    "out_trade_no",
                    "outTradeNo",
                    "channel_trade_no",
                    "channelTradeNo",
                    "out_request_no",
                    "outRequestNo",
                    "amount_cent",
                    "amountCent",
                    "occurred_at",
                    "occurredAt")
                .get(k));
        w.writeCharacters(Objects.toString(r.get(k), ""));
        w.writeEndElement();
      }
      w.writeEndElement();
    }
    w.writeEndElement();
    w.close();
    return out.toByteArray();
  }

  @SuppressWarnings("unchecked")
  @GetMapping("/files/{id}")
  Object download(@PathVariable String id) throws Exception {
    if (!id.matches("[a-f0-9-]{36}\\.(xlsx|xml|docx|pdf|png|jpg|jpeg|txt)"))
      throw Api.fail(404, "文件不存在");
    String uri = "exports/" + id;
    if (!Files.exists(files.resolve(uri + ".meta.json"))) uri = "evidence/" + id;
    var meta = files.jsonRead(uri + ".meta.json");
    if (meta.containsKey("caseId")) access.caseRow(Long.parseLong(meta.get("caseId").toString()));
    else {
      if (Long.parseLong(meta.get("actorId").toString()) != access.actor().id())
        throw Api.fail(403, "只能下载本人生成的文件");
      if (!Objects.equals(meta.get("role"), access.actor().role()))
        throw Api.fail(403, "角色已变更，请重新生成报告");
      if (!access.platform() && !access.actor().role().equals("ADMIN"))
        for (Object m : (List<Object>) meta.get("merchantIds"))
          access.merchant(Long.parseLong(m.toString()));
    }
    return attachment(
        Files.readAllBytes(files.resolve(uri)),
        meta.get("name").toString(),
        id.substring(id.lastIndexOf('.') + 1));
  }

  private ResponseEntity<byte[]> attachment(byte[] bytes, String name, String format) {
    String mime =
        switch (format) {
          case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
          case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
          case "pdf" -> "application/pdf";
          case "xml" -> "application/xml";
          default -> "application/octet-stream";
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(mime))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(bytes);
  }
}
