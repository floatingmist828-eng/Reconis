package cn.reconis.reconciliation;

import cn.reconis.common.*;
import cn.reconis.common.FileStore;
import cn.reconis.identity.Access;
import cn.reconis.payment.PaymentGateway;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.stream.XMLOutputFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable acquisition parents share the batch entity, never the reconciliation result set. */
@Service
public class AutoReconciliationService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private final Db db;
  private final FileStore files;
  private final Access access;
  private final PaymentGateway gateway;
  private final ImportService importer;
  private final TransactionTemplate tx;
  private final AtomicBoolean working = new AtomicBoolean();

  public AutoReconciliationService(Db db, FileStore files, Access access, PaymentGateway gateway,
      ImportService importer, TransactionTemplate tx) {
    this.db = db; this.files = files; this.access = access; this.gateway = gateway;
    this.importer = importer; this.tx = tx;
  }

  public List<Map<String, Object>> enqueue(String start, String end, boolean daily) {
    access.role("PLATFORM_FINANCE");
    LocalDate from = date(start), until = date(end);
    validateRange(from, until, LocalDate.now(ZONE));
    return tx.execute(status -> {
      lockQueue();
      var result = new ArrayList<Map<String, Object>>();
      for (LocalDate day = from; !day.isAfter(until); day = day.plusDays(1)) result.add(enqueueDay(day));
      if (daily) setDaily(Db.id(result.getLast(), "id"), true);
      return result;
    });
  }

  static void validateRange(LocalDate from, LocalDate until, LocalDate today) {
    if (until.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, until) > 30)
      throw Api.fail(422, "每次补跑范围为1至31天");
    if (!until.isBefore(today)) throw Api.fail(422, "只能获取已经结束的账期，最晚为昨天");
    if (from.isBefore(today.minusYears(6))) throw Api.fail(422, "仅支持近六年的账期");
  }

  private static LocalDate date(String value) {
    try { return LocalDate.parse(value); }
    catch (DateTimeException | NullPointerException e) { throw Api.fail(422, "账期格式应为yyyy-MM-dd"); }
  }

  private void lockQueue() {
    // SQL Server JDBC's autoCommit=false enables implicit transactions. EXEC alone does not
    // open one, so first touch a real table on this same Spring-bound connection.
    db.exec("DECLARE @anchor BIGINT; SELECT TOP(1) @anchor=id FROM reconciliation_batch;"
        + " DECLARE @r INT; EXEC @r=sys.sp_getapplock @Resource='ReconisBillQueue',"
        + "@LockMode='Exclusive',@LockOwner='Transaction',@LockTimeout=5000;"
        + " IF @r<0 BEGIN DECLARE @message NVARCHAR(200)=CONCAT(N'账单任务锁获取失败，返回码 ',@r,N'，请稍后重试');"
        + " THROW 51065,@message,1; END;", Api.params());
  }

  private Map<String, Object> enqueueDay(LocalDate day) {
    String key = FileStore.sha((gateway.environment + "|" + gateway.appId + "|" + day).getBytes(StandardCharsets.UTF_8));
    var old = db.one("SELECT id,batch_no,acquisition_state FROM reconciliation_batch WHERE acquisition_key=#{p.key}", Api.params("key", key));
    if (old != null) return old;
    long id = db.insert("INSERT reconciliation_batch(batch_no,scope_type,period_start,period_end,source_type,status,"
        + "created_by,acquisition_owner,acquisition_key,ingest_options_sha256,acquisition_env,acquisition_app,acquisition_state,acquisition_next_at)"
        + " OUTPUT INSERTED.id VALUES(#{p.no},'PLATFORM_UNASSIGNED',#{p.start},#{p.end},'AUTO_JOB','IMPORTING',"
        + "#{p.actor},#{p.actor},#{p.key},#{p.key},#{p.env},#{p.app},'QUEUED',SYSUTCDATETIME())",
        Api.params("no", Api.no("AUTO"), "start", utc(day), "end", utc(day.plusDays(1)),
            "actor", access.actor().id(), "key", key, "env", gateway.environment, "app", gateway.appId));
    audit(id, "BILL_QUEUED", Map.of("date", day.toString(), "source", source()));
    return Api.params("id", id, "acquisitionState", "QUEUED");
  }

  public void daily(long id, boolean enabled) {
    access.role("PLATFORM_FINANCE"); job(id);
    tx.executeWithoutResult(status -> { lockQueue(); setDaily(id, enabled); });
  }

  private void setDaily(long id, boolean enabled) {
    if (enabled) db.exec("UPDATE reconciliation_batch SET acquisition_daily=0 WHERE source_type='AUTO_JOB'"
        + " AND acquisition_env=#{p.env} AND acquisition_app=#{p.app}", environment());
    db.exec("UPDATE reconciliation_batch SET acquisition_daily=#{p.enabled},acquisition_owner=#{p.actor} WHERE id=#{p.id}",
        Api.params("enabled", enabled, "actor", access.actor().id(), "id", id));
    audit(id, "BILL_SCHEDULE", Map.of("enabled", enabled));
  }

  public void retry(long id) {
    access.role("PLATFORM_FINANCE"); job(id);
    tx.executeWithoutResult(status -> {
      int changed = db.exec("UPDATE reconciliation_batch SET acquisition_state='QUEUED',acquisition_attempts=0,"
          + "acquisition_generation=acquisition_generation+1,acquisition_next_at=SYSUTCDATETIME(),"
          + "acquisition_token=NULL,acquisition_lease_until=NULL,acquisition_message=N'已申请补跑',acquisition_owner=#{p.actor}"
          + " WHERE id=#{p.id} AND acquisition_state IN ('FAILED','RETRY_WAIT','UNCONFIGURED','UNSUPPORTED')",
          Api.params("id", id, "actor", access.actor().id()));
      if (changed != 1) throw Api.fail(409, "任务正在执行或已完成，无需重复补跑");
      audit(id, "BILL_REQUEUED", Map.of("preserveOriginal", true));
    });
  }

  public Map<String, Object> list(int page) {
    access.role("PLATFORM_FINANCE", "AUDITOR");
    var result = new LinkedHashMap<>(db.page("FROM reconciliation_batch", "source_type='AUTO_JOB'"
        + " AND acquisition_env=#{p.env} AND acquisition_app=#{p.app}", "id DESC", environment(), page, 20));
    result.put("source", source());
    result.put("scheduleTime", "每日上海时间10:00后获取前日账单，停机后补建遗漏账期");
    result.put("maxAttempts", 4);
    result.put("capabilityMessage", gateway.environment.equals("DEMO")
        ? "账单由本地渠道按账期生成。"
        : "支付宝沙箱账单接口仅提供模板；成功下载会保留原件，但不据此判断漏单或生成差错。");
    return result;
  }

  public Map<String, Object> detail(long id) {
    access.role("PLATFORM_FINANCE", "AUDITOR");
    var item = new LinkedHashMap<>(job(id));
    item.put("events", db.list("SELECT id,action,occurred_at,after_json FROM audit_log WHERE"
        + " object_type='AUTO_BILL_JOB' AND object_id=#{p.id} ORDER BY id", Api.params("id", id)));
    return item;
  }

  public byte[] original(long id) throws Exception {
    access.role("PLATFORM_FINANCE", "AUDITOR");
    var item = job(id);
    if (item.get("sourceFileUri") == null) throw Api.fail(404, "任务尚未获取原始文件");
    return verifiedBytes(item);
  }

  private Map<String, Object> job(long id) {
    var p = environment(); p.put("id", id);
    return db.need("SELECT * FROM reconciliation_batch WHERE id=#{p.id} AND source_type='AUTO_JOB'"
        + " AND acquisition_env=#{p.env} AND acquisition_app=#{p.app}", p);
  }

  private Map<String, Object> environment() { return Api.params("env", gateway.environment, "app", gateway.appId); }
  private String source() { return gateway.environment.equals("DEMO") ? "DEMO_SIMULATED" : "ALIPAY_SANDBOX_TEMPLATE"; }
  private static LocalDateTime utc(LocalDate day) { return day.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
  private static LocalDate day(Map<String, Object> item) {
    return ((java.sql.Timestamp)item.get("periodStart")).toLocalDateTime().atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).toLocalDate();
  }

  @Scheduled(initialDelayString="${reconis.bill-initial-delay-ms:20000}", fixedDelayString="${reconis.bill-delay-ms:10000}")
  public void tick() {
    if (!working.compareAndSet(false, true)) return;
    // The channel's bounded network wait must not block payment recovery's scheduler thread.
    Thread.ofVirtual().name("reconis-bill-worker").start(() -> {
      try { drainOnce(); }
      catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("自动账单扫描暂未完成 ({})", e.getClass().getSimpleName()); }
      finally { RequestContext.clear(); working.set(false); }
    });
  }

  void drainOnce() {
    RequestContext.set(null, UUID.randomUUID().toString());
    try {
      try { extendDaily(); }
      catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("每日账期扩展暂未完成 ({})", e.getClass().getSimpleName());
      }
      // A daily schedule may have set a different owner's context. Each queued job authorizes
      // its own owner; rejected jobs must never be audited as that previous owner.
      RequestContext.set(null, UUID.randomUUID().toString());
      var p = environment();
      // One bounded unit per tick: a bad account/day cannot starve later dates.
      var item = db.one("SELECT TOP 1 * FROM reconciliation_batch WHERE source_type='AUTO_JOB'"
          + " AND acquisition_env=#{p.env} AND acquisition_app=#{p.app} AND "
          + "((acquisition_state IN ('QUEUED','RETRY_WAIT') AND acquisition_next_at<=SYSUTCDATETIME())"
          + " OR (acquisition_state IN ('FETCHING','VALIDATING','RECONCILING') AND acquisition_lease_until<SYSUTCDATETIME()))"
          + " ORDER BY acquisition_next_at,id", p);
      if (item == null) return;
      process(Db.id(item, "id"));
    } finally { RequestContext.clear(); }
  }

  private void extendDaily() {
    if (LocalTime.now(ZONE).isBefore(LocalTime.of(10, 0))) return;
    var item = db.one("SELECT TOP 1 * FROM reconciliation_batch WHERE source_type='AUTO_JOB' AND"
        + " acquisition_daily=1 AND acquisition_env=#{p.env} AND acquisition_app=#{p.app} ORDER BY period_start DESC", environment());
    if (item == null) return;
    if (!authorizeCreator(item)) {
      tx.executeWithoutResult(status -> {
        db.exec("UPDATE reconciliation_batch SET acquisition_daily=0,acquisition_message=N'每日获取已暂停：授权执行者权限失效' WHERE id=#{p.id}",Api.params("id",item.get("id")));
        audit(Db.id(item,"id"),"BILL_SCHEDULE_REVOKED",Map.of("message","每日获取已暂停：授权执行者权限失效"));
      });
      return;
    }
    LocalDate next = day(item).plusDays(1), yesterday = LocalDate.now(ZONE).minusDays(1);
    if (next.isAfter(yesterday)) return;
    enqueue(next.toString(), (next.plusDays(6).isBefore(yesterday) ? next.plusDays(6) : yesterday).toString(), true);
  }

  private boolean authorizeCreator(Map<String, Object> item) {
    var actor = db.one("SELECT id,username,display_name,platform_role FROM app_user WHERE id=#{p.id}"
        + " AND status='ACTIVE' AND platform_role='PLATFORM_FINANCE'", Api.params("id", item.get("acquisitionOwner")));
    if (actor == null) return false;
    RequestContext.set(new RequestContext.Actor(Db.id(actor,"id"), actor.get("username").toString(),
        actor.get("displayName").toString(), "PLATFORM_FINANCE"), UUID.randomUUID().toString());
    return true;
  }

  void process(long id) {
    String token = UUID.randomUUID().toString();
    int claimed = db.exec("UPDATE reconciliation_batch SET acquisition_state='FETCHING',acquisition_token=#{p.token},"
        + "acquisition_lease_until=DATEADD(MINUTE,10,SYSUTCDATETIME()),acquisition_attempts=acquisition_attempts+1,"
        + "started_at=COALESCE(started_at,SYSUTCDATETIME()),finished_at=NULL WHERE id=#{p.id} AND source_type='AUTO_JOB'"
        + " AND acquisition_attempts<4 AND ((acquisition_state IN ('QUEUED','RETRY_WAIT') AND acquisition_next_at<=SYSUTCDATETIME())"
        + " OR (acquisition_state IN ('FETCHING','VALIDATING','RECONCILING') AND acquisition_lease_until<SYSUTCDATETIME()))",
        Api.params("id", id, "token", token));
    if (claimed != 1) {
      db.exec("UPDATE reconciliation_batch SET acquisition_state='FAILED',acquisition_token=NULL,acquisition_lease_until=NULL,"
          + "acquisition_message=N'执行次数已达上限，可检查原因后补跑' WHERE id=#{p.id} AND acquisition_attempts>=4"
          + " AND acquisition_lease_until<SYSUTCDATETIME() AND acquisition_state IN ('FETCHING','VALIDATING','RECONCILING')", Api.params("id", id));
      return;
    }
    var item = job(id);
    try {
      if (!authorizeCreator(item)) { finish(id, token, "FAILED", "授权执行者已停用或不再具备平台财务权限", Map.of()); return; }
      audit(id, "BILL_ATTEMPT", Map.of("attempt", item.get("acquisitionAttempts"), "generation", item.get("acquisitionGeneration")));
      byte[] bytes; String format;
      if (item.get("sourceFileUri") != null) {
        bytes = verifiedBytes(item); format = item.get("acquisitionFormat").toString();
      } else {
        if (gateway.environment.equals("DEMO")) { bytes = demoBill(day(item)); format = "xml"; }
        else {
          var address = gateway.billAddress(day(item));
          if (!address.state().equals("AVAILABLE")) {
            String code = address.code().replaceAll("[^A-Za-z0-9_.-]", "");
            finish(id, token, address.state(), switch (address.state()) {
              case "UNCONFIGURED" -> "缺少沙箱应用、收款方或密钥配置";
              case "UNSUPPORTED" -> "渠道未开放此账单能力：" + code;
              case "RETRY_WAIT" -> "账单尚未生成或渠道暂时不可用：" + code;
              default -> "渠道拒绝账单请求：" + code;
            }, Map.of("code", code)); return;
          }
          bytes = BillDownloader.download(address.url());
          format = bytes.length>=4 && bytes[0]=='P' && bytes[1]=='K' ? "zip" : "csv";
        }
        String uri = files.write("imports", "."+format, bytes), sha = FileStore.sha(bytes);
        ensure(db.exec("UPDATE reconciliation_batch SET source_file_uri=#{p.uri},file_sha256=#{p.sha},acquisition_format=#{p.format}"
            + " WHERE id=#{p.id} AND acquisition_token=#{p.token}", Api.params("id", id,"token",token,"uri",uri,"sha",sha,"format",format)));
        audit(id, "BILL_ORIGINAL_SAVED", Map.of("uri",uri,"sha256",sha,"bytes",bytes.length,"source",source()));
      }
      if (!gateway.environment.equals("DEMO")) {
        // Sandbox downloads are documented templates, never an authoritative complete ledger.
        AlipayBillParser.parse(bytes, format, gateway.environment, true);
        finish(id, token, "TEMPLATE_ONLY", "已下载并保留沙箱账单模板；不代表实际交易，未生成对账差错", Map.of("source", source())); return;
      }
      step(id,token,"VALIDATING");
      var preview = cast(importer.statement(bytes,format,null,day(item).toString(),day(item).toString(),"DECLARED_COMPLETE",true));
      if (!((List<?>)preview.get("errors")).isEmpty()) {
        finish(id,token,"FAILED","账单校验未通过，保留原件，未导入或生成部分结果",preview); return;
      }
      var imported = importer.automaticStatement(bytes,format,day(item).toString());
      step(id,token,"RECONCILING");
      var outcomes = new ArrayList<Map<String,Object>>(); boolean failed = false;
      for (Object value : (List<?>)imported.get("batches")) {
        var batch = cast(value); long batchId = Db.id(batch,"id");
        step(id,token,"RECONCILING");
        try {
          if (!"COMPLETED".equals(batch.get("status"))) db.exec("EXEC usp_run_reconciliation @batch_id=#{p.id}",Api.params("id",batchId));
          outcomes.add(Api.params("id",batchId,"merchantId",batch.get("merchantId"),"status","COMPLETED"));
        } catch (Exception e) {
          var current=db.one("SELECT status FROM reconciliation_batch WHERE id=#{p.id}",Api.params("id",batchId));
          if(current!=null && "COMPLETED".equals(current.get("status"))) {
            // Another authorized runner may have committed while this snapshot lost a race.
            outcomes.add(Api.params("id",batchId,"merchantId",batch.get("merchantId"),"status","COMPLETED"));
          } else {
            failed=true;
            db.exec("UPDATE reconciliation_batch SET status='FAILED' WHERE id=#{p.id} AND status<>'COMPLETED'",Api.params("id",batchId));
            outcomes.add(Api.params("id",batchId,"merchantId",batch.get("merchantId"),"status","FAILED"));
          }
        }
      }
      finish(id,token,failed?"RETRY_WAIT":"SUCCEEDED",failed?"部分商户对账未完成；成功结果已保留，稍后仅重试未完成批次":"账单已校验并完成对账，差错已进入处理队列",
          Map.of("batches",outcomes,"rows",imported.get("accepted"),"unassigned",imported.get("unassigned"),"source",source()));
    } catch (ResponseStatusException e) {
      finish(id,token,"FAILED",Objects.toString(e.getReason(),"任务校验失败"),Map.of());
    } catch (Exception e) {
      finish(id,token,"RETRY_WAIT","获取或处理暂未完成，将按有限次数重试；原件和已完成批次保留",Map.of("errorType",e.getClass().getSimpleName()));
    }
  }

  private byte[] verifiedBytes(Map<String,Object> item) throws Exception {
    Path path=files.resolve(item.get("sourceFileUri").toString());
    if (Files.size(path)>AlipayBillParser.MAX_BYTES) throw Api.fail(409,"原件大小与任务约束不符");
    byte[] bytes=Files.readAllBytes(path);
    if (!FileStore.sha(bytes).equals(item.get("fileSha256"))) throw Api.fail(409,"原始账单散列校验失败，已停止处理");
    return bytes;
  }

  private void step(long id,String token,String state) {
    ensure(db.exec("UPDATE reconciliation_batch SET acquisition_state=#{p.state},acquisition_lease_until=DATEADD(MINUTE,10,SYSUTCDATETIME())"
        + " WHERE id=#{p.id} AND acquisition_token=#{p.token}",Api.params("id",id,"token",token,"state",state)));
  }
  private static void ensure(int changed) { if(changed!=1) throw Api.fail(409,"任务执行租约已转移"); }
  static int retryDelayMinutes(int attempt) { return switch(attempt) { case 1 -> 5; case 2 -> 30; default -> 120; }; }

  private void finish(long id,String token,String state,String message,Object result) {
    var item=job(id); int attempt=(int)Db.id(item,"acquisitionAttempts");
    if(state.equals("RETRY_WAIT") && attempt>=4) { state="FAILED"; message += "；已达到4次上限，可检查后补跑"; }
    final String target=state, note=message.length()>500?message.substring(0,500):message;
    tx.executeWithoutResult(status -> {
      int changed=db.exec("UPDATE reconciliation_batch SET acquisition_state=#{p.state},acquisition_message=#{p.message},"
          + "acquisition_result=#{p.result},acquisition_token=NULL,acquisition_lease_until=NULL,"
          + "acquisition_next_at=CASE WHEN #{p.state}='RETRY_WAIT' THEN DATEADD(MINUTE,#{p.delay},SYSUTCDATETIME()) ELSE NULL END,"
          + "finished_at=CASE WHEN #{p.state}='RETRY_WAIT' THEN NULL ELSE SYSUTCDATETIME() END WHERE id=#{p.id} AND acquisition_token=#{p.token}",
          Api.params("id",id,"token",token,"state",target,"message",note,"result",encode(result),"delay",retryDelayMinutes(attempt)));
      if(changed==1) audit(id,"BILL_"+target,Api.params("message",note,"result",result));
    });
  }
  private String encode(Object value) { return new String(files.jsonBytes(Api.normalize(value)),StandardCharsets.UTF_8); }
  private void audit(long id,String action,Object data) {
    db.exec("INSERT audit_log(event_category,actor_user_id,object_type,object_id,action,result,after_json,request_id)"
        + " VALUES('IMPORT_EXPORT',#{p.actor},'AUTO_BILL_JOB',#{p.id},#{p.action},'RECORDED',#{p.after},#{p.request})",
        Api.params("id",id,"actor",RequestContext.actor()==null?null:RequestContext.actor().id(),"action",action,"after",encode(data),"request",RequestContext.requestId()));
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object value) { return (Map<String,Object>)value; }

  /** Read the simulated channel, not the local immutable ledger being checked against it. */
  byte[] demoBill(LocalDate date) throws Exception {
    var writer=new StringWriter(); var xml=XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
    xml.writeStartDocument("UTF-8","1.0"); xml.writeComment("DEMO_SIMULATED channel statement for "+date);
    xml.writeStartElement("statement"); xml.writeAttribute("schemaVersion","1"); xml.writeAttribute("channelEnv","DEMO");
    int count=0;
    try(var entries=Files.list(files.root.resolve("channel"))) {
      for(Path path:entries.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList()) {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>65536) throw Api.fail(422,"模拟渠道记录不完整或过大");
        var item=files.jsonRead("channel/"+path.getFileName());
        if(!"SUCCEEDED".equals(item.get("status"))) continue;
        if(item.get("occurredAt")==null) throw Api.fail(422,"模拟渠道成功记录缺少发生时间");
        var at=Api.utc(item.get("occurredAt").toString());
        if(at.isBefore(utc(date)) || !at.isBefore(utc(date.plusDays(1)))) continue;
        if(++count>10000) throw Api.fail(422,"单日模拟账单超过10000条，未截断导入");
        String key=path.getFileName().toString().replaceFirst("\\.json$","");
        var refund=db.one("SELECT p.out_trade_no FROM refund_request r JOIN payment_attempt p ON p.id=r.payment_id WHERE r.out_request_no=#{p.key}",Api.params("key",key));
        xml.writeStartElement("event"); xml.writeAttribute("type",refund==null?"RECEIPT":"REFUND");
        element(xml,"outTradeNo",refund==null?key:refund.get("outTradeNo"));
        element(xml,"channelTradeNo",item.get("tradeNo")); element(xml,"outRequestNo",refund==null?"":key);
        element(xml,"amountCent",item.get("amountCent")); element(xml,"occurredAt",at.atOffset(ZoneOffset.UTC)); xml.writeEndElement();
      }
    }
    xml.writeEndElement(); xml.writeEndDocument(); xml.close();
    byte[] bytes=writer.toString().getBytes(StandardCharsets.UTF_8);
    if(bytes.length>AlipayBillParser.MAX_BYTES) throw Api.fail(422,"模拟账单超过20MB");
    return bytes;
  }
  private static void element(javax.xml.stream.XMLStreamWriter xml,String name,Object value) throws Exception {
    xml.writeStartElement(name); xml.writeCharacters(Objects.toString(value,"")); xml.writeEndElement();
  }
}
