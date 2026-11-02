<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { codeName, api, query, time, action, type Row } from "../api";
import StatusTag from "../components/StatusTag.vue";
const router = useRouter(),
  route = useRoute(),
  filters = reactive({
    q: "",
    status: String(route.query.status || ""),
    classification: "",
    mine: route.query.mine === "true",
    page: 1,
  }),
  list = ref<Row>({ items: [], total: 0 }),
  busy = ref(false);
async function load() {
  busy.value = true;
  try {
    list.value = await api("/cases?" + query(filters));
  } finally {
    busy.value = false;
  }
}
onMounted(() => action(load));
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">EXCEPTION COLLABORATION</div>
        <h1>差错工作台</h1>
        <p>分派问题，记录处理，提交复核。</p>
      </div>
      <el-button @click="router.push('/search')">检索历史解决案例 →</el-button>
    </div>
    <section class="panel" v-loading="busy">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          placeholder="工单编号 / 问题描述"
          clearable
          @keyup.enter="action(load)"
        /><el-select v-model="filters.status" placeholder="全部状态" clearable
          ><el-option
            v-for="(label, value) in {
              OPEN: '待分派',
              ASSIGNED: '已分派',
              PROCESSING: '处理中',
              PENDING_REVIEW: '待复核',
              CLOSED: '已关闭',
            }"
            :key="value"
            :label="label"
            :value="value" /></el-select
        ><el-select
          v-model="filters.classification"
          placeholder="全部差错类型"
          clearable
          ><el-option
            v-for="c in [
              'CHANNEL_ONLY',
              'LOCAL_ONLY',
              'AMOUNT_MISMATCH',
              'BILL_DUPLICATE',
              'KEY_UNRESOLVED',
              'PENDING_CONFIRMATION',
              'EXTRA_SUCCESS',
            ]"
            :key="c"
            :label="codeName(c)"
            :value="c" /></el-select
        ><el-checkbox v-model="filters.mine">我的待办</el-checkbox
        ><el-button
          @click="
            filters.page = 1;
            action(load);
          "
          >查询</el-button
        >
      </div>
      <el-table
        :data="list.items"
        class="clickable"
        @row-click="(r: Row) => router.push('/cases/' + r.id)"
        ><el-table-column label="问题与来源" min-width="240"
          ><template #default="{ row }"
            ><b>{{ row.title }}</b
            ><span class="subline mono">{{ row.caseNo }}</span></template
          ></el-table-column
        ><el-table-column
          label="所属商户"
          prop="merchantName"
          min-width="125"
        /><el-table-column label="差错类型" width="135"
          ><template #default="{ row }"
            ><StatusTag
              :value="row.classification" /></template></el-table-column
        ><el-table-column label="处理人" width="100"
          ><template #default="{ row }">{{
            row.assigneeName || "待分派"
          }}</template></el-table-column
        ><el-table-column label="状态" width="100"
          ><template #default="{ row }"
            ><StatusTag :value="row.status" /></template></el-table-column
        ><el-table-column label="截止时间" width="160"
          ><template #default="{ row }"
            ><span
              :style="{
                color:
                  row.status !== 'CLOSED' && new Date(row.dueAt) < new Date()
                    ? '#b58e5d'
                    : '',
              }"
              >{{ time(row.dueAt) }}</span
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
  </div>
</template>
