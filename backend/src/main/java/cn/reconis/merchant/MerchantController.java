package cn.reconis.merchant;

import cn.reconis.common.*;
import cn.reconis.identity.Access;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MerchantController {
  private final Db db;
  private final Access access;

  public MerchantController(Db db, Access access) {
    this.db = db;
    this.access = access;
  }

  @GetMapping("/merchants")
  Object list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    var p = Api.params("q", "%" + q + "%", "status", status);
    String scope = access.actor().role().equals("ADMIN") ? "1=1" : access.scope("m.id", p);
    return Api.ok(
        db.page(
            "FROM merchant m",
            scope
                + " AND (name LIKE #{p.q} OR merchant_code LIKE #{p.q}) AND (#{p.status}='' OR"
                + " status=#{p.status})",
            "id DESC",
            p,
            page,
            pageSize));
  }

  @PostMapping("/merchants")
  @Transactional
  public Object create(@RequestBody Map<String, Object> b) {
    access.role("MEMBER", "PLATFORM_FINANCE");
    long id =
        db.insert(
            "INSERT merchant(merchant_code,name,category_code,contact) OUTPUT INSERTED.id"
                + " VALUES(#{p.code},#{p.name},#{p.category},#{p.contact})",
            Api.params(
                "code",
                Api.no("M").substring(0, 16),
                "name",
                Api.required(b, "name"),
                "category",
                Api.required(b, "categoryCode"),
                "contact",
                Api.required(b, "contact")));
    db.exec(
        "INSERT merchant_member(merchant_id,user_id,role_code) VALUES(#{p.id},#{p.user},'OWNER')",
        Api.params("id", id, "user", access.actor().id()));
    return Api.ok(Map.of("id", id));
  }

  @PatchMapping("/merchants/{id}")
  Object update(@PathVariable long id, @RequestBody Map<String, Object> b) {
    access.merchant(id, "OWNER");
    int n =
        db.exec(
            "UPDATE merchant SET"
                + " proposed_name=#{p.name},proposed_category=#{p.category},proposed_contact=#{p.contact},updated_at=SYSUTCDATETIME()"
                + " WHERE id=#{p.id} AND version=CONVERT(BINARY(8),#{p.version},2)",
            Api.params(
                "id",
                id,
                "name",
                Api.required(b, "name"),
                "category",
                Api.required(b, "categoryCode"),
                "contact",
                Api.required(b, "contact"),
                "version",
                Api.required(b, "version")));
    if (n == 0) throw Api.fail(409, "资料已变更，请刷新后重新提交");
    return Api.ok(Map.of("message", "变更已提交审核，现行资料继续有效"));
  }

  @PostMapping("/merchants/{id}/review")
  Object review(@PathVariable long id, @RequestBody Map<String, Object> b) {
    access.role("ADMIN");
    boolean approve = Boolean.TRUE.equals(b.get("approve"));
    int changed =
        db.exec(
            "UPDATE merchant SET name=CASE WHEN #{p.approve}=1 THEN COALESCE(proposed_name,name)"
                + " ELSE name END,category_code=CASE WHEN #{p.approve}=1 THEN"
                + " COALESCE(proposed_category,category_code) ELSE category_code END,contact=CASE"
                + " WHEN #{p.approve}=1 THEN COALESCE(proposed_contact,contact) ELSE contact"
                + " END,status=CASE WHEN status='ACTIVE' THEN status WHEN #{p.approve}=1 THEN"
                + " 'ACTIVE' ELSE 'REJECTED'"
                + " END,proposed_name=NULL,proposed_category=NULL,proposed_contact=NULL,reviewed_by=#{p.actor},reviewed_at=SYSUTCDATETIME(),review_reason=#{p.reason},updated_at=SYSUTCDATETIME()"
                + " WHERE id=#{p.id} AND version=CONVERT(BINARY(8),#{p.version},2) AND"
                + " (status='PENDING' OR proposed_name IS NOT NULL)",
            Api.params(
                "id",
                id,
                "approve",
                approve,
                "actor",
                access.actor().id(),
                "reason",
                Api.required(b, "reason"),
                "version",
                Api.required(b, "version")));
    if (changed == 0) throw Api.fail(409, "待审资料已变更或已审核，请刷新后重新查看");
    RequestContext.merchant(id);
    return Api.ok(Map.of("message", "商户审核已完成"));
  }

  @GetMapping("/merchants/{id}/stores")
  Object stores(@PathVariable long id) {
    if (access.actor().role().equals("ADMIN")) {
      db.need("SELECT id FROM merchant WHERE id=#{p.id}", Api.params("id", id));
      RequestContext.merchant(id);
    } else access.readMerchant(id);
    return Api.ok(
        db.list("SELECT * FROM store WHERE merchant_id=#{p.id} ORDER BY id", Api.params("id", id)));
  }

  @PostMapping("/merchants/{id}/stores")
  Object addStore(@PathVariable long id, @RequestBody Map<String, Object> b) {
    access.merchant(id, "OWNER");
    long sid =
        db.insert(
            "INSERT store(merchant_id,store_code,name,location_text) OUTPUT INSERTED.id"
                + " VALUES(#{p.id},#{p.code},#{p.name},#{p.location})",
            Api.params(
                "id",
                id,
                "code",
                Api.required(b, "storeCode"),
                "name",
                Api.required(b, "name"),
                "location",
                Api.text(b, "locationText")));
    return Api.ok(Map.of("id", sid));
  }

  @PatchMapping("/merchants/{id}/stores/{sid}")
  Object updateStore(
      @PathVariable long id, @PathVariable long sid, @RequestBody Map<String, Object> b) {
    access.merchant(id, "OWNER");
    String status = Api.required(b, "status");
    if (!Set.of("ACTIVE", "DISABLED").contains(status)) throw Api.fail(422, "状态无效");
    int n =
        db.exec(
            "UPDATE store SET"
                + " name=#{p.name},location_text=#{p.location},status=#{p.status},updated_at=SYSUTCDATETIME()"
                + " WHERE id=#{p.sid} AND merchant_id=#{p.id} AND"
                + " version=CONVERT(BINARY(8),#{p.version},2)",
            Api.params(
                "id",
                id,
                "sid",
                sid,
                "name",
                Api.required(b, "name"),
                "location",
                Api.text(b, "locationText"),
                "status",
                status,
                "version",
                Api.required(b, "version")));
    if (n == 0) throw Api.fail(409, "门店已修改，请刷新");
    return Api.ok(Map.of("message", "门店已保存"));
  }

  @DeleteMapping("/merchants/{id}/stores/{sid}")
  Object deleteStore(@PathVariable long id, @PathVariable long sid) {
    access.merchant(id, "OWNER");
    if (db.exec(
            "DELETE store WHERE id=#{p.sid} AND merchant_id=#{p.id} AND NOT EXISTS(SELECT 1 FROM"
                + " business_order WHERE store_id=#{p.sid})",
            Api.params("id", id, "sid", sid))
        == 0) throw Api.fail(409, "已有交易的门店只能停用");
    return Api.ok(Map.of("message", "门店已删除"));
  }

  @GetMapping("/merchants/{id}/members")
  Object members(@PathVariable long id) {
    if (!access.platform()) access.merchant(id, "OWNER", "FINANCE", "REVIEWER");
    return Api.ok(
        db.list(
            "SELECT mm.*,u.username,u.display_name FROM merchant_member mm JOIN app_user u ON"
                + " u.id=mm.user_id WHERE mm.merchant_id=#{p.id}",
            Api.params("id", id)));
  }

  @PostMapping("/merchants/{id}/members")
  Object addMember(@PathVariable long id, @RequestBody Map<String, Object> b) {
    access.merchant(id, "OWNER");
    String role = Api.required(b, "roleCode");
    if (!Set.of("OWNER", "CASHIER", "FINANCE", "REVIEWER").contains(role))
      throw Api.fail(422, "成员角色不合法");
    var u =
        db.need(
            "SELECT id FROM app_user WHERE username=#{p.name} AND status='ACTIVE'",
            Api.params("name", Api.required(b, "username")));
    db.exec(
        "INSERT merchant_member(merchant_id,user_id,role_code) VALUES(#{p.id},#{p.user},#{p.role})",
        Api.params("id", id, "user", u.get("id"), "role", role));
    return Api.ok(Map.of("message", "成员已添加"));
  }

  @PatchMapping("/merchants/{id}/members/{member}")
  Object updateMember(
      @PathVariable long id, @PathVariable long member, @RequestBody Map<String, Object> b) {
    access.merchant(id, "OWNER");
    String role = Api.required(b, "roleCode"), status = Api.required(b, "status");
    if (!Set.of("OWNER", "CASHIER", "FINANCE", "REVIEWER").contains(role)
        || !Set.of("ACTIVE", "DISABLED").contains(status)) throw Api.fail(422, "成员角色或状态无效");
    if (db.exec(
            "UPDATE merchant_member SET role_code=#{p.role},status=#{p.status} WHERE id=#{p.member}"
                + " AND merchant_id=#{p.id} AND user_id<>#{p.actor}",
            Api.params(
                "id",
                id,
                "member",
                member,
                "role",
                role,
                "status",
                status,
                "actor",
                access.actor().id()))
        == 0) throw Api.fail(409, "不能在此修改自己的成员权限");
    return Api.ok(Map.of("message", "成员权限已更新"));
  }
}
