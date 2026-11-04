<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { codeName, api, post, query, time, action, type Row } from "../api";
import StatusTag from "../components/StatusTag.vue";
const filters = reactive({ q: "", page: 1 }),
  list = ref<Row>({ items: [], total: 0 }),
  busy = ref(false),
  selected = ref<Row | null>(null),
  form = reactive({ status: "ACTIVE", platformRole: "MEMBER", reason: "" });
async function load() {
  busy.value = true;
  try {
    list.value = await api("/admin/users?" + query(filters));
  } finally {
    busy.value = false;
  }
}
onMounted(() => action(load));
function open(r: Row) {
  selected.value = r;
  Object.assign(form, {
    status: r.status === "PENDING" ? "ACTIVE" : r.status,
    platformRole: r.platformRole,
    reason: "",
  });
}
async function save() {
  await post(`/admin/users/${selected.value!.id}/review`, form);
  selected.value = null;
  ElMessage.success("审核与平台权限已保存");
  await load();
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <h1>账号审核与平台权限</h1>
        <p>审核账号，分配平台权限。</p>
      </div>
    </div>
    <section class="panel" v-loading="busy">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          placeholder="用户名 / 显示姓名"
          clearable
          @keyup.enter="action(load)"
        /><el-button
          @click="
            filters.page = 1;
            action(load);
          "
          >查询账号</el-button
        ><span class="grow"></span
        ><span class="muted">共 {{ list.total }} 个账号</span>
      </div>
      <el-table :data="list.items"
        ><el-table-column label="用户" min-width="180"
          ><template #default="{ row }"
            ><b>{{ row.displayName }}</b
            ><span class="subline"
              >{{ row.username }} · #{{ row.id }}</span
            ></template
          ></el-table-column
        ><el-table-column
          label="平台角色"
          prop="platformRole"
          min-width="150"
        /><el-table-column label="状态" width="120"
          ><template #default="{ row }"
            ><StatusTag :value="row.status" /></template></el-table-column
        ><el-table-column label="注册时间" width="180"
          ><template #default="{ row }">{{
            time(row.createdAt)
          }}</template></el-table-column
        ><el-table-column label="操作" width="140"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="open(row)"
              >审核与权限</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <div class="pagination">
        <el-pagination
          v-model:current-page="filters.page"
          :total="Number(list.total)"
          :page-size="20"
          layout="total,prev,pager,next"
          @current-change="action(load)"
        />
      </div>
    </section>
    <el-dialog
      :model-value="!!selected"
      :title="'审核账号 · ' + selected?.displayName"
      width="520"
      @close="selected = null"
      ><el-form label-position="top"
        ><el-form-item label="账号状态"
          ><el-select v-model="form.status"
            ><el-option label="通过 / 启用" value="ACTIVE" /><el-option
              label="退回注册"
              value="REJECTED" /><el-option
              label="停用账号"
              value="DISABLED" /></el-select></el-form-item
        ><el-form-item label="平台角色"
          ><el-select v-model="form.platformRole"
            ><el-option
              label="普通成员（消费者 / 商户成员）"
              value="MEMBER" /><el-option
              label="平台财务"
              value="PLATFORM_FINANCE" /><el-option
              label="平台管理员"
              value="ADMIN" /><el-option
              label="只读审计员"
              value="AUDITOR" /></el-select></el-form-item
        ><el-form-item label="审核意见"
          ><el-input
            v-model="form.reason"
            type="textarea"
            maxlength="500"
            :rows="3" /></el-form-item
      ></el-form>
      <div class="help-note">保存后立即更新账号权限。</div>
      <template #footer
        ><el-button type="primary" @click="action(save)"
          >保存审核结果</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
