<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus, Search, Upload, Download } from "@element-plus/icons-vue";
import {
  codeName,
  api,
  post,
  patch,
  query,
  action,
  exportFile,
  type Row,
} from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
const session = useSession(),
  list = ref<Row>({ items: [], total: 0 }),
  busy = ref(false),
  filters = reactive({ q: "", status: "", page: 1 }),
  dialog = ref(false),
  editing = ref<Row | null>(null);
const form = reactive({
    name: "",
    categoryCode: "咖啡餐饮",
    contact: "",
    version: "",
  }),
  selected = ref<Row | null>(null),
  stores = ref<Row[]>([]),
  members = ref<Row[]>([]),
  resource = ref("stores"),
  storeDialog = ref(false),
  memberDialog = ref(false),
  storeEdit = ref<Row | null>(null);
const storeForm = reactive({
    storeCode: "",
    name: "",
    locationText: "",
    status: "ACTIVE",
    version: "",
  }),
  memberForm = reactive({ username: "", roleCode: "CASHIER" });
const importing = ref(false),
  file = ref<File>(),
  importResult = ref<Row | null>(null),
  importBusy = ref(false);
async function load() {
  busy.value = true;
  try {
    list.value = await api("/merchants?" + query(filters));
  } finally {
    busy.value = false;
  }
}
onMounted(() => action(load));
function edit(row?: Row) {
  editing.value = row || null;
  Object.assign(form, {
    name: row?.proposedName || row?.name || "",
    categoryCode: row?.proposedCategory || row?.categoryCode || "咖啡餐饮",
    contact: row?.proposedContact || row?.contact || "",
    version: row?.version || "",
  });
  dialog.value = true;
}
async function save() {
  if (editing.value) await patch("/merchants/" + editing.value.id, form);
  else await post("/merchants", form);
  ElMessage.success(editing.value ? "变更已提交审核" : "商户已申请入驻");
  dialog.value = false;
  await load();
}
async function review(row: Row, approve: boolean) {
  const { value } = await ElMessageBox.prompt(
    "填写本次审核意见",
    "审核商户资料",
    { inputValidator: (v) => !!v || "请填写意见" },
  );
  await post(`/merchants/${row.id}/review`, { approve, reason: value });
  await load();
}
async function open(row: Row) {
  selected.value = row;
  await resources();
}
async function resources() {
  if (!selected.value) return;
  stores.value = await api(`/merchants/${selected.value.id}/stores`);
  if (
    !session.admin &&
    session.can(selected.value!.id, ["OWNER", "FINANCE", "REVIEWER"])
  )
    members.value = await api(`/merchants/${selected.value.id}/members`);
}
function editStore(row?: Row) {
  storeEdit.value = row || null;
  Object.assign(storeForm, {
    storeCode: row?.storeCode || "",
    name: row?.name || "",
    locationText: row?.locationText || "",
    status: row?.status || "ACTIVE",
    version: row?.version || "",
  });
  storeDialog.value = true;
}
async function saveStore() {
  if (storeEdit.value)
    await patch(
      `/merchants/${selected.value!.id}/stores/${storeEdit.value.id}`,
      storeForm,
    );
  else await post(`/merchants/${selected.value!.id}/stores`, storeForm);
  storeDialog.value = false;
  await resources();
}
async function removeStore(row: Row) {
  await ElMessageBox.confirm(
    "仅未被订单引用的门店可删除，是否继续？",
    "删除门店",
    { type: "warning" },
  );
  await api(`/merchants/${selected.value!.id}/stores/${row.id}`, {
    method: "DELETE",
  });
  await resources();
}
async function addMember() {
  await post(`/merchants/${selected.value!.id}/members`, memberForm);
  memberDialog.value = false;
  await resources();
}
async function saveMember(row: Row) {
  await patch(`/merchants/${selected.value!.id}/members/${row.id}`, row);
  ElMessage.success("成员权限已更新");
  await resources();
}
async function importFile(preview: boolean) {
  if (!file.value) return ElMessage.warning("先选择文件");
  const b = new FormData();
  b.append("file", file.value);
  b.append("preview", String(preview));
  importBusy.value = true;
  try {
    importResult.value = await post("/merchants/imports", b);
    if (!preview) {
      ElMessage.success("有效资料已提交为待审候选");
      await load();
    }
  } finally {
    importBusy.value = false;
  }
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">MERCHANT DIRECTORY</div>
        <h1>商户与门店</h1>
        <p>管理商户、门店和成员。</p>
      </div>
      <div class="heading-actions">
        <el-dropdown
          @command="(v: string) => action(() => exportFile('merchants', v))"
          ><el-button :icon="Download">导出资料</el-button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="xlsx">Excel 工作簿</el-dropdown-item
              ><el-dropdown-item command="xml"
                >XML 数据</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        ><el-button
          v-if="session.admin"
          :icon="Upload"
          @click="importing = true"
          >导入资料</el-button
        ><el-button
          v-if="session.writable"
          type="primary"
          :icon="Plus"
          @click="edit()"
          >申请入驻</el-button
        >
      </div>
    </div>
    <section class="panel" v-loading="busy">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          :prefix-icon="Search"
          placeholder="商户名称 / 编号"
          clearable
          @keyup.enter="action(load)"
        /><el-select v-model="filters.status" placeholder="全部状态" clearable
          ><el-option label="待审核" value="PENDING" /><el-option
            label="已启用"
            value="ACTIVE" /><el-option
            label="已退回"
            value="REJECTED" /></el-select
        ><el-button
          @click="
            filters.page = 1;
            action(load);
          "
          >查询</el-button
        ><span class="grow"></span
        ><span class="muted">共 {{ list.total }} 家商户</span>
      </div>
      <el-table :data="list.items"
        ><el-table-column label="商户" min-width="200"
          ><template #default="{ row }"
            ><b>{{ row.name }}</b
            ><span class="subline mono">{{ row.merchantCode }}</span></template
          ></el-table-column
        ><el-table-column
          label="经营类别"
          prop="categoryCode"
          width="120"
        /><el-table-column
          label="联系信息"
          prop="contact"
          min-width="140"
        /><el-table-column label="当前状态" width="110"
          ><template #default="{ row }"
            ><StatusTag :value="row.status" /><span
              v-if="row.proposedName"
              class="subline"
              >有变更待审</span
            ></template
          ></el-table-column
        ><el-table-column label="操作" width="235"
          ><template #default="{ row }"
            ><div class="row-actions">
              <el-button link type="primary" @click="action(() => open(row))"
                >门店与成员</el-button
              ><template v-if="session.admin"
                ><el-button
                  v-if="row.status === 'PENDING' || row.proposedName"
                  link
                  type="primary"
                  @click="edit(row)"
                  >审核资料</el-button
                ></template
              ><el-button
                v-else-if="session.can(row.id, ['OWNER'])"
                link
                @click="edit(row)"
                >变更资料</el-button
              >
            </div></template
          ></el-table-column
        ></el-table
      >
      <div class="pagination">
        <el-pagination
          v-model:current-page="filters.page"
          :total="Number(list.total)"
          :page-size="20"
          layout="prev,pager,next"
          @current-change="action(load)"
        />
      </div>
    </section>
    <el-dialog
      v-model="dialog"
      :title="
        session.admin ? '资料审核' : editing ? '申请资料变更' : '申请商户入驻'
      "
      width="560"
      ><el-form label-position="top"
        ><el-form-item label="商户名称"
          ><el-input
            v-model="form.name"
            maxlength="100"
            :disabled="session.admin" /></el-form-item
        ><el-form-item label="经营类别"
          ><el-select
            v-model="form.categoryCode"
            allow-create
            filterable
            :disabled="session.admin"
            ><el-option
              v-for="c in ['咖啡餐饮', '校园零售', '文印服务', '生活服务']"
              :key="c"
              :label="codeName(c)"
              :value="c" /></el-select></el-form-item
        ><el-form-item label="联系信息"
          ><el-input
            v-model="form.contact"
            maxlength="150"
            :disabled="session.admin"
        /></el-form-item>
        <div v-if="editing?.proposedName" class="help-note">
          现行资料：{{ editing.name }} / {{ editing.categoryCode }} /
          {{ editing.contact }}<br />本次提案：{{ editing.proposedName }} /
          {{ editing.proposedCategory }} / {{ editing.proposedContact }}
        </div></el-form
      ><template #footer
        ><el-button @click="dialog = false">关闭</el-button
        ><template v-if="session.admin"
          ><el-button
            @click="
              action(async () => {
                await review(editing!, false);
                dialog = false;
              })
            "
            >退回</el-button
          ><el-button
            type="primary"
            @click="
              action(async () => {
                await review(editing!, true);
                dialog = false;
              })
            "
            >审核通过</el-button
          ></template
        ><el-button v-else type="primary" @click="action(save)"
          >提交审核</el-button
        ></template
      ></el-dialog
    >
    <el-drawer
      :model-value="!!selected"
      :title="selected?.name + ' · 门店与成员'"
      size="720px"
      @close="selected = null"
      ><el-tabs v-model="resource"
        ><el-tab-pane label="门店管理" name="stores"
          ><div class="table-toolbar">
            <p class="muted">管理门店信息与营业状态。</p>
            <span class="grow"></span
            ><el-button
              v-if="session.writable"
              type="primary"
              @click="editStore()"
              >新增门店</el-button
            >
          </div>
          <el-table :data="stores"
            ><el-table-column
              label="门店"
              prop="name"
              min-width="150"
            /><el-table-column
              label="位置"
              prop="locationText"
              min-width="150"
            /><el-table-column label="状态" width="90"
              ><template #default="{ row }"
                ><StatusTag :value="row.status" /></template></el-table-column
            ><el-table-column v-if="session.writable" label="操作" width="100"
              ><template #default="{ row }"
                ><el-button link @click="editStore(row)">编辑</el-button
                ><el-button
                  link
                  type="danger"
                  @click="action(() => removeStore(row))"
                  >删除</el-button
                ></template
              ></el-table-column
            ></el-table
          ></el-tab-pane
        ><el-tab-pane
          v-if="
            !session.admin &&
            session.can(selected!.id, ['OWNER', 'FINANCE', 'REVIEWER'])
          "
          label="成员权限"
          name="members"
          ><div class="table-toolbar">
            <p class="muted">分配成员职责。</p>
            <span class="grow"></span
            ><el-button v-if="session.writable" @click="memberDialog = true"
              >添加成员</el-button
            >
          </div>
          <el-table :data="members"
            ><el-table-column
              label="成员"
              prop="displayName"
              min-width="100"
            /><el-table-column
              label="账号"
              prop="username"
              width="110"
            /><el-table-column label="商户角色" width="140"
              ><template #default="{ row }"
                ><el-select
                  v-model="row.roleCode"
                  :disabled="!session.writable"
                  size="small"
                  ><el-option
                    v-for="r in ['OWNER', 'CASHIER', 'FINANCE', 'REVIEWER']"
                    :key="r"
                    :value="r"
                    :label="
                      codeName(r)
                    " /></el-select></template></el-table-column
            ><el-table-column label="状态" width="110"
              ><template #default="{ row }"
                ><el-select
                  v-model="row.status"
                  :disabled="!session.writable"
                  size="small"
                  ><el-option label="已启用" value="ACTIVE" /><el-option
                    label="已停用"
                    value="DISABLED" /></el-select></template></el-table-column
            ><el-table-column v-if="session.writable" width="70"
              ><template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  @click="action(() => saveMember(row))"
                  >保存</el-button
                ></template
              ></el-table-column
            ></el-table
          ></el-tab-pane
        ></el-tabs
      ></el-drawer
    >
    <el-dialog
      v-model="storeDialog"
      :title="storeEdit ? '编辑门店' : '新增门店'"
      width="500"
      ><el-form label-position="top"
        ><el-form-item label="门店编号"
          ><el-input
            v-model="storeForm.storeCode"
            :disabled="!!storeEdit"
            maxlength="32" /></el-form-item
        ><el-form-item label="门店名称"
          ><el-input v-model="storeForm.name" maxlength="100" /></el-form-item
        ><el-form-item label="位置"
          ><el-input
            v-model="storeForm.locationText"
            maxlength="200" /></el-form-item
        ><el-form-item v-if="storeEdit" label="状态"
          ><el-select v-model="storeForm.status"
            ><el-option label="启用" value="ACTIVE" /><el-option
              label="停用"
              value="DISABLED" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button @click="storeDialog = false">取消</el-button
        ><el-button type="primary" @click="action(saveStore)"
          >保存门店</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="memberDialog" title="添加商户成员" width="450"
      ><el-form label-position="top"
        ><el-form-item label="已审核账号的用户名"
          ><el-input v-model="memberForm.username" /></el-form-item
        ><el-form-item label="商户角色"
          ><el-select v-model="memberForm.roleCode"
            ><el-option
              v-for="r in ['OWNER', 'CASHIER', 'FINANCE', 'REVIEWER']"
              :key="r"
              :value="r"
              :label="codeName(r)" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button type="primary" @click="action(addMember)"
          >添加成员</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="importing" title="导入商户资料" width="650"
      ><div class="row-actions">
        <a href="/api/v1/templates?kind=merchants&format=xlsx"
          ><el-button>Excel模板 ↓</el-button></a
        ><a href="/api/v1/templates?kind=merchants&format=xml"
          ><el-button>XML模板 ↓</el-button></a
        >
      </div>
      <div class="help-note">导入后进入待审列表。</div>
      <input
        type="file"
        accept=".xlsx,.xml"
        @change="
          (e: Event) => {
            file = (e.target as HTMLInputElement).files?.[0];
            importResult = null;
          }
        "
      />
      <div v-if="importResult" class="help-note">
        总行数 {{ importResult.rows }} · 可接收 {{ importResult.accepted }} ·
        错误 {{ importResult.errors.length }}
      </div>
      <el-table v-if="importResult?.errors?.length" :data="importResult.errors"
        ><el-table-column prop="line" label="行号" /><el-table-column
          prop="reason"
          label="错误原因" /></el-table
      ><template #footer
        ><el-button
          :loading="importBusy"
          @click="action(() => importFile(true))"
          >预览校验</el-button
        ><el-button
          type="primary"
          :loading="importBusy"
          :disabled="!importResult"
          @click="action(() => importFile(false))"
          >提交待审资料</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
