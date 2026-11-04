<script setup lang="ts">
import { reactive, ref, onMounted, computed } from "vue";
import { api, query, time, action, type Row } from "../api";
import Chart from "../components/Chart.vue";
import StatusTag from "../components/StatusTag.vue";
const filters = reactive({
    q: "",
    category: "",
    actorId: "",
    merchantId: "",
    result: "",
    start: "",
    end: "",
    page: 1,
  }),
  list = ref<Row>({ items: [], total: 0 }),
  stats = ref<Row>({ categories: [], actors: [], results: [] }),
  busy = ref(false),
  detail = ref<Row | null>(null);
async function load() {
  busy.value = true;
  try {
    const q = query(filters);
    [list.value, stats.value] = await Promise.all([
      api("/audit/events?" + q),
      api("/audit/statistics?" + q),
    ]);
  } finally {
    busy.value = false;
  }
}
onMounted(() => action(load));
const chart = computed(() => ({
  color: ["#325ddb"],
  tooltip: { trigger: "axis" },
  grid: { left: 10, right: 24, top: 12, bottom: 12, containLabel: true },
  xAxis: { type: "value", splitLine: { lineStyle: { color: "#edf0f5", type: "dashed" } } },
  yAxis: {
    type: "category",
    data: stats.value.categories.map((r: Row) => r.eventCategory),
    axisTick: { show: false },
    axisLine: { show: false },
    axisLabel: { fontSize: 12 },
  },
  series: [
    {
      type: "bar",
      barMaxWidth: 18,
      data: stats.value.categories.map((r: Row) => Number(r.count)),
      itemStyle: { borderRadius: [0, 3, 3, 0] },
    },
  ],
}));
</script>
<template>
  <div v-loading="busy">
    <div class="page-heading">
      <div>
        <h1>审计中心</h1>
        <p>查询操作记录与业务变更。</p>
      </div>
      <el-button @click="action(load)">刷新审计</el-button>
    </div>
    <div class="overview-bottom" style="margin-bottom: 23px">
      <section class="panel">
        <div class="panel-heading"><h2>事件类别分布</h2></div>
        <Chart :option="chart" :height="210" />
      </section>
      <section class="panel">
        <div class="panel-heading"><h2>操作人员统计</h2></div>
        <el-table :data="stats.actors" max-height="210"
          ><el-table-column label="人员" prop="actorName" /><el-table-column
            label="事件数"
            prop="count"
            align="right"
        /></el-table>
      </section>
    </div>
    <section class="panel">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          placeholder="动作路径 / 操作摘要"
          clearable
        /><el-select v-model="filters.category" placeholder="全部类别" clearable
          ><el-option
            v-for="v in [
              'ACCESS',
              'AUTH',
              'BUSINESS',
              'DB_CHANGE',
              'CHANNEL_QUERY',
              'IMPORT_EXPORT',
            ]"
            :key="v"
            :value="v"
            :label="v" /></el-select
        ><el-select v-model="filters.result" placeholder="全部结果" clearable
          ><el-option label="成功" value="SUCCESS" /><el-option
            label="失败"
            value="FAILED" /><el-option
            label="待确认"
            value="UNKNOWN" /></el-select
        ><el-input
          v-model="filters.actorId"
          placeholder="用户ID"
          style="width: 100px"
        /><el-input
          v-model="filters.merchantId"
          placeholder="商户ID"
          style="width: 100px"
        />
      </div>
      <div class="table-toolbar">
        <el-date-picker
          v-model="filters.start"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss+08:00"
          placeholder="开始时间"
        /><el-date-picker
          v-model="filters.end"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss+08:00"
          placeholder="结束时间"
        /><el-button
          type="primary"
          @click="
            filters.page = 1;
            action(load);
          "
          >查询与统计</el-button
        ><span class="grow"></span
        ><span class="muted">{{ list.total }} 条事件</span>
      </div>
      <el-table
        :data="list.items"
        class="clickable"
        @row-click="(r: Row) => (detail = r)"
        ><el-table-column label="发生时间" width="160"
          ><template #default="{ row }"
            ><span class="mono">{{ time(row.occurredAt) }}</span></template
          ></el-table-column
        ><el-table-column label="类别 / 人员" width="140"
          ><template #default="{ row }"
            >{{ row.eventCategory
            }}<span class="subline">{{
              row.actorName || "系统 / 未登录"
            }}</span></template
          ></el-table-column
        ><el-table-column
          label="操作动作"
          prop="action"
          min-width="220"
          show-overflow-tooltip /><el-table-column
          label="摘要"
          prop="summary"
          min-width="200"
          show-overflow-tooltip /><el-table-column label="结果" width="100"
          ><template #default="{ row }"
            ><StatusTag :value="row.result" /></template></el-table-column
      ></el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="filters.page"
          :total="Number(list.total)"
          :page-size="30"
          layout="total,prev,pager,next"
          @current-change="action(load)"
        />
      </div>
    </section>
    <el-dialog
      :model-value="!!detail"
      title="审计事件详情"
      width="700"
      @close="detail = null"
      ><template v-if="detail"
        ><div class="description-grid">
          <div>
            <label>动作</label><b>{{ detail.action }}</b>
          </div>
          <div>
            <label>请求关联号</label
            ><b class="mono">{{ detail.requestId || "系统任务" }}</b>
          </div>
          <div>
            <label>人员 / 商户</label
            ><b
              >{{ detail.actorName || "系统" }} /
              {{ detail.merchantName || "—" }}</b
            >
          </div>
          <div>
            <label>对象</label
            ><b>{{ detail.objectType }} #{{ detail.objectId }}</b>
          </div>
        </div>
        <div class="help-note">{{ detail.summary }}</div>
        <div class="comparison">
          <section>
            <h3>变更前快照</h3>
            <pre style="white-space: pre-wrap; font-size: 11px">{{
              detail.beforeJson || "无"
            }}</pre>
          </section>
          <section>
            <h3>变更后快照</h3>
            <pre style="white-space: pre-wrap; font-size: 11px">{{
              detail.afterJson || "无"
            }}</pre>
          </section>
        </div></template
      ></el-dialog
    >
  </div>
</template>
