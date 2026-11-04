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
    requestId: "",
    start: "",
    end: "",
    page: 1,
  }),
  list = ref<Row>({ items: [], total: 0 }),
  stats = ref<Row>({ categories: [], actors: [], results: [] }),
  busy = ref(false),
  detail = ref<Row | null>(null);
const categories: Record<string, string> = {
  ACCESS: "访问",
  AUTH: "账号认证",
  BUSINESS: "业务操作",
  DB_CHANGE: "数据变更",
  CHANNEL_QUERY: "渠道查询",
  IMPORT_EXPORT: "导入与导出",
};
const outcomes = [
  { key: "SUCCESS", label: "成功" },
  { key: "FAILED", label: "失败" },
  { key: "UNKNOWN", label: "待确认" },
];
const resultCounts = computed(() => Object.fromEntries(
  stats.value.results.map((r: Row) => [r.result, r.count]),
));
const totalEvents = computed(() => stats.value.results.reduce(
  (sum: bigint, row: Row) => sum + BigInt(row.count), 0n,
).toLocaleString("zh-CN"));
const applied = ref({ ...filters });
let request = 0;
async function load(paginate = false) {
  const current = ++request;
  const snapshot = paginate ? { ...applied.value, page: filters.page } : { ...filters };
  busy.value = true;
  try {
    const q = query(snapshot);
    const [events, statistics] = await Promise.all([
      api("/audit/events?" + q),
      paginate ? stats.value : api("/audit/statistics?" + q),
    ]);
    if (current !== request) return;
    list.value = events;
    stats.value = statistics;
    applied.value = snapshot;
  } finally {
    if (current === request) busy.value = false;
  }
}
async function search() {
  filters.page = 1;
  await load();
}
async function resetFilters() {
  Object.assign(filters, { q: "", category: "", actorId: "", merchantId: "", result: "", requestId: "", start: "", end: "" });
  await search();
}
async function drill(key: "result" | "actorId" | "requestId", value: string) {
  Object.assign(filters, applied.value);
  filters[key] = filters[key] === value ? "" : value;
  if (key === "requestId") {
    filters.requestId = value;
    Object.assign(filters, { q: "", category: "", actorId: "", merchantId: "", result: "", start: "", end: "" });
    detail.value = null;
  }
  await search();
}
onMounted(() => action(search));
const chart = computed(() => ({
  color: ["#325ddb"],
  tooltip: { trigger: "axis" },
  grid: { left: 10, right: 24, top: 12, bottom: 12, containLabel: true },
  xAxis: { type: "value", splitLine: { lineStyle: { color: "#edf0f5", type: "dashed" } } },
  yAxis: {
    type: "category",
    data: stats.value.categories.map((r: Row) => categories[r.eventCategory] || r.eventCategory),
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
  <div class="audit-page" v-loading="busy" :aria-busy="busy">
    <div class="page-heading">
      <div>
        <h1>审计中心</h1>
      </div>
      <el-button @click="action(() => load(true))">刷新记录</el-button>
    </div>
    <div class="audit-summary" aria-label="按结果筛选审计事件">
      <button class="audit-metric" type="button" :aria-pressed="!applied.result" @click="action(() => drill('result', ''))">
        <span>事件总数</span><strong>{{ totalEvents }}</strong>
      </button>
      <button v-for="item in outcomes" :key="item.key" type="button" class="audit-metric" :data-outcome="item.key" :aria-pressed="applied.result === item.key" @click="action(() => drill('result', item.key))">
        <span>{{ item.label }}</span><strong>{{ resultCounts[item.key] || 0 }}</strong>
      </button>
    </div>
    <details class="panel disclosure audit-charts">
      <summary>统计分布</summary>
      <div class="overview-bottom">
      <section class="panel">
        <div class="panel-heading"><h2>事件类别分布</h2></div>
        <Chart :option="chart" :height="210" />
      </section>
      <section class="panel">
        <div class="panel-heading"><h2>操作人员统计</h2></div>
        <el-table :data="stats.actors" max-height="210" empty-text="暂无记录">
          <el-table-column label="人员">
            <template #default="{ row }">
              <el-button v-if="row.actorUserId" link type="primary" @click="action(() => drill('actorId', String(row.actorUserId)))">{{ row.actorName }}</el-button>
              <span v-else>{{ row.actorName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="事件数" prop="count" align="right" />
        </el-table>
      </section>
      </div>
    </details>
    <section class="panel">
      <form @submit.prevent="action(search)">
      <div class="table-toolbar audit-filters">
        <el-input
          v-model="filters.q"
          placeholder="动作路径 / 操作摘要"
          clearable
        /><el-select v-model="filters.category" placeholder="全部类别" clearable
          ><el-option
            v-for="(label, key) in categories"
            :key="key"
            :value="key"
            :label="label" /></el-select
        ><el-select v-model="filters.result" placeholder="全部结果" clearable
          ><el-option label="成功" value="SUCCESS" /><el-option
            label="失败"
            value="FAILED" /><el-option
            label="待确认"
            value="UNKNOWN" /></el-select
        >
        <el-button type="primary" native-type="submit" :loading="busy">查询</el-button>
        <el-button @click="action(resetFilters)">清除</el-button>
      </div>
      <details class="disclosure audit-advanced">
        <summary>更多筛选</summary>
        <div class="table-toolbar audit-filters">
          <el-input v-model="filters.actorId" placeholder="用户 ID" aria-label="用户 ID" />
          <el-input v-model="filters.merchantId" placeholder="商户 ID" aria-label="商户 ID" />
          <el-input v-model="filters.requestId" placeholder="请求关联号" aria-label="请求关联号" clearable />
          <el-date-picker v-model="filters.start" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss+08:00" placeholder="开始时间" />
          <el-date-picker v-model="filters.end" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss+08:00" placeholder="结束时间" />
        </div>
      </details>
      <div v-if="applied.actorId || applied.merchantId || applied.requestId || applied.start || applied.end" class="audit-applied muted">
        <span v-if="applied.actorId">用户 {{ applied.actorId }}</span>
        <span v-if="applied.merchantId">商户 {{ applied.merchantId }}</span>
        <span v-if="applied.requestId">请求 {{ applied.requestId }}</span>
        <span v-if="applied.start">{{ time(applied.start) }} 起</span>
        <span v-if="applied.end">截至 {{ time(applied.end) }}</span>
      </div>
      </form>
      <el-table
        :data="list.items"
        class="clickable"
        empty-text="没有符合条件的事件"
        @row-click="(r: Row) => (detail = r)"
        ><el-table-column label="发生时间" width="160"
          ><template #default="{ row }"
            ><span class="mono">{{ time(row.occurredAt) }}</span></template
          ></el-table-column
        ><el-table-column label="类别 / 人员" width="140"
          ><template #default="{ row }"
            >{{ categories[row.eventCategory] || row.eventCategory
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
          @current-change="action(() => load(true))"
        />
      </div>
    </section>
    <el-dialog
      :model-value="!!detail"
      title="审计事件详情"
      width="min(740px, calc(100vw - 28px))"
      @close="detail = null"
      ><template v-if="detail"
        ><div class="description-grid">
          <div>
            <label>动作</label><b>{{ detail.action }}</b>
          </div>
          <div>
            <label>请求关联号</label>
            <b class="mono">{{ detail.requestId || "—" }}</b>
            <el-button v-if="detail.requestId" link type="primary" @click="action(() => drill('requestId', detail!.requestId))">查看同一请求</el-button>
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
            <pre class="audit-snapshot">{{
              detail.beforeJson || "无"
            }}</pre>
          </section>
          <section>
            <h3>变更后快照</h3>
            <pre class="audit-snapshot">{{
              detail.afterJson || "无"
            }}</pre>
          </section>
        </div></template
      ></el-dialog
    >
  </div>
</template>


<style scoped>
.audit-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 20px; }
.audit-metric {
  text-align: left;
  padding: 16px 20px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface);
  color: var(--muted);
  transition: border-color .18s, background .18s, transform .18s;
}
.audit-metric:hover { transform: translateY(-2px); }
.audit-metric:hover, .audit-metric[aria-pressed="true"] { background: #f3f6ff; border-color: #bdcaf1; }
.audit-metric span { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.audit-metric span::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: #6285d1; }
.audit-metric[data-outcome="SUCCESS"] span::before { background: #3d9b83; }
.audit-metric[data-outcome="FAILED"] span::before { background: #c56a70; }
.audit-metric[data-outcome="UNKNOWN"] span::before { background: #8295b8; }
.audit-metric strong { display: block; margin-top: 9px; font-size: 26px; color: var(--ink); font-variant-numeric: tabular-nums; }
.audit-charts { margin-bottom: 18px; }
.audit-charts > div > section { min-width: 0; }
.audit-advanced { margin: 0 0 16px; }
.audit-advanced .table-toolbar { margin-bottom: 0; }
.audit-advanced .el-input { width: 155px; }
.audit-applied { display: flex; flex-wrap: wrap; gap: 8px 14px; margin: 0 0 14px; font-size: 12px; overflow-wrap: anywhere; }
.audit-snapshot { overflow-wrap: anywhere; white-space: pre-wrap; font-size: 12px; }
@media (max-width: 650px) {
  .audit-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .audit-metric { padding: 13px 16px; }
  .audit-filters > .el-input, .audit-filters > .el-select { flex: 1 1 125px; }
}
@media (prefers-reduced-motion: reduce) {
  .audit-metric { transition: none; }
  .audit-metric:hover { transform: none; }
}
</style>
