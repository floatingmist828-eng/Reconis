<script setup lang="ts">
import { computed, ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
  codeName,
  api,
  query,
  money,
  dateDays,
  exportFile,
  action,
  type Row,
} from "../api";
import Chart from "../components/Chart.vue";
import StatusTag from "../components/StatusTag.vue";
const router = useRouter(),
  filters = reactive({
    start: dateDays(-29),
    end: dateDays(),
    merchantId: "",
    storeId: "",
    minCent: "",
    maxCent: "",
    dimension: "merchant",
  }),
  merchants = ref<Row[]>([]),
  data = ref<Row>({ summary: {}, groups: [], trend: [] }),
  reconciliation = ref<Row>({ distribution: [], associations: [] }),
  cases = ref<Row[]>([]),
  details = ref<Row[] | null>(null),
  busy = ref(false),
  history = ref<{ label: string; filters: Row }[]>([]),
  detailPage = ref(1);
async function load() {
  busy.value = true;
  try {
    const q = query(filters);
    [data.value, reconciliation.value, cases.value] = await Promise.all([
      api("/analytics/transactions?" + q),
      api("/analytics/reconciliation?" + q),
      api("/analytics/cases?" + q),
    ]);
    details.value = null;
  } finally {
    busy.value = false;
  }
}
onMounted(() =>
  action(async () => {
    await load();
    merchants.value = (await api("/merchants?pageSize=100")).items;
  }),
);
const trend = computed(() => ({
  color: ["#3b8b71", "#c6ad7d"],
  tooltip: { trigger: "axis" },
  legend: { data: ["收款", "退款"], top: 5, right: 0 },
  grid: { top: 45, right: 22, bottom: 35, left: 60 },
  xAxis: {
    type: "category",
    data: data.value.trend.map((r: Row) => r.day),
    axisLabel: { fontSize: 10 },
    axisTick: { show: false },
  },
  yAxis: {
    type: "value",
    name: "元",
    splitLine: { lineStyle: { color: "#edf1e7" } },
  },
  series: [
    {
      name: "收款",
      type: "bar",
      barMaxWidth: 20,
      data: data.value.trend.map((r: Row) => Number(r.receiptCent) / 100),
    },
    {
      name: "退款",
      type: "bar",
      barMaxWidth: 20,
      data: data.value.trend.map((r: Row) => Number(r.refundCent) / 100),
    },
  ],
}));
const distribution = computed(() => ({
  color: [
    "#54957a",
    "#c5aa6e",
    "#d4b594",
    "#a0b6a7",
    "#d4a38c",
    "#a7baa0",
    "#c8cfb0",
    "#8ea39b",
  ],
  tooltip: { trigger: "item" },
  legend: { bottom: 0, textStyle: { fontSize: 9 } },
  series: [
    {
      type: "pie",
      radius: ["42%", "65%"],
      center: ["50%", "43%"],
      label: { show: false },
      data: reconciliation.value.distribution.map((r: Row) => ({
        name: codeName(r.classification),
        value: Number(r.count),
      })),
    },
  ],
}));
async function drill(g: Row) {
  history.value.push({ label: g.groupName, filters: { ...filters } });
  if (filters.dimension === "merchant") {
    filters.merchantId = g.groupKey;
    filters.dimension = "store";
    await load();
  } else if (filters.dimension === "store") {
    filters.storeId = g.groupKey;
    await loadDetails();
  } else if (filters.dimension === "year") {
    filters.start = g.groupKey + "-01-01";
    filters.end = g.groupKey + "-12-31";
    filters.dimension = "month";
    await load();
  } else if (filters.dimension === "month") {
    filters.start = g.groupKey + "-01";
    const [y, m] = g.groupKey.split("-").map(Number);
    filters.end = new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);
    filters.dimension = "day";
    await load();
  } else {
    filters.start = g.groupKey;
    filters.end = g.groupKey;
    await loadDetails();
  }
}
async function restore(index: number) {
  if (index === -1) {
    Object.assign(filters, {
      merchantId: "",
      storeId: "",
      dimension: "merchant",
      start: dateDays(-29),
      end: dateDays(),
    });
    history.value = [];
  } else {
    Object.assign(filters, history.value[index]!.filters);
    history.value = history.value.slice(0, index);
  }
  await load();
}
async function loadDetails() {
  details.value = await api(
    "/analytics/transactions/details?" + query(filters),
  );
  detailPage.value = 1;
}
</script>
<template>
  <div v-loading="busy">
    <div class="page-heading">
      <div>
        <div class="eyebrow">FINANCIAL INSIGHTS</div>
        <h1>经营与差错分析</h1>
        <p>查看经营数据，点开分组了解明细。</p>
      </div>
      <div class="heading-actions">
        <el-dropdown
          @command="
            (format: string) =>
              action(() => exportFile('transactions', format, filters))
          "
          ><el-button type="primary">导出分析 / 流水 ↓</el-button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="pdf"
                >PDF 图文统计报告</el-dropdown-item
              ><el-dropdown-item command="docx"
                >Word 图文统计报告</el-dropdown-item
              ><el-dropdown-item command="xlsx">Excel 交易明细</el-dropdown-item
              ><el-dropdown-item command="xml"
                >XML 标准账单</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        >
      </div>
    </div>
    <section class="panel">
      <div class="table-toolbar">
        <el-date-picker
          v-model="filters.start"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="开始日"
          style="width: 150px"
        /><span class="muted">至</span
        ><el-date-picker
          v-model="filters.end"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="结束日"
          style="width: 150px"
        /><el-select
          v-model="filters.merchantId"
          placeholder="全部商户"
          clearable
          @change="filters.storeId = ''"
          ><el-option
            v-for="m in merchants"
            :key="m.id"
            :label="m.name"
            :value="m.id" /></el-select
        ><el-select v-model="filters.dimension" placeholder="汇总维度"
          ><el-option label="按商户" value="merchant" /><el-option
            label="按门店"
            value="store" /><el-option label="按年" value="year" /><el-option
            label="按月"
            value="month" /><el-option label="按日" value="day" /></el-select
        ><el-button
          type="primary"
          @click="
            history = [];
            action(load);
          "
          >更新分析</el-button
        >
      </div>
      <div class="table-toolbar" style="margin-bottom: 0">
        <el-input
          v-model="filters.minCent"
          placeholder="最小金额（分）"
          inputmode="numeric"
          style="width: 160px"
        /><el-input
          v-model="filters.maxCent"
          placeholder="最大金额（分）"
          inputmode="numeric"
          style="width: 160px"
        /><span class="grow"></span
        ><small class="muted"
          >截止
          {{
            data.asOf ? new Date(data.asOf).toLocaleString("zh-CN") : "—"
          }}</small
        >
      </div>
    </section>
    <div class="kpi-grid" style="margin-top: 23px">
      <div
        v-for="m in [
          { label: '成功收款', key: 'receiptCent' },
          { label: '成功退款', key: 'refundCent' },
          { label: '净收款', key: 'netCent' },
        ]"
        :key="m.key"
        class="kpi-card"
      >
        <span class="muted">{{ m.label }}</span
        ><strong><em>¥</em>{{ money(data.summary[m.key]) }}</strong
        ><small>按交易日期统计</small>
      </div>
      <div class="kpi-card">
        <span class="muted">资金事件数</span
        ><strong>{{ data.summary.eventCount || "0" }}</strong
        ><small>收款与退款分别计数</small>
      </div>
    </div>
    <div
      class="overview-main"
      style="grid-template-columns: minmax(0, 1.65fr) minmax(270px, 1fr)"
    >
      <section class="panel">
        <div class="panel-heading">
          <h2>收退款时间分布</h2>
          <span class="muted">金额 / 元</span>
        </div>
        <Chart v-if="data.trend.length" :option="trend" /><el-empty
          v-else
          description="所选期间暂无流水"
          :image-size="80"
        />
      </section>
      <section class="panel">
        <div class="panel-heading"><h2>最近批次结果分布</h2></div>
        <Chart
          v-if="reconciliation.distribution.length"
          :option="distribution"
        /><el-empty v-else description="暂无完成的对账结果" :image-size="80" />
      </section>
    </div>
    <section class="panel" style="margin-bottom: 23px">
      <div class="panel-heading">
        <div>
          <h2>{{ details ? "交易明细" : "分组汇总与钻取" }}</h2>
          <p>点击分组：商户 → 门店 → 流水；年 → 月 → 日 → 流水</p>
        </div>
        <el-button link type="primary" @click="action(loadDetails)"
          >查看当前范围明细 →</el-button
        >
      </div>
      <div v-if="history.length" class="filter-breadcrumb">
        <el-button link @click="action(() => restore(-1))">全部范围</el-button
        ><template v-for="(h, i) in history" :key="i"
          ><span>›</span
          ><el-button link @click="action(() => restore(i))">{{
            h.label
          }}</el-button></template
        >
      </div>
      <el-table
        v-if="!details"
        :data="data.groups"
        class="clickable"
        @row-click="(r: Row) => action(() => drill(r))"
        ><el-table-column
          label="统计分组"
          prop="groupName"
          min-width="170"
        /><el-table-column
          label="事件数"
          prop="eventCount"
          width="100"
          align="right"
        /><el-table-column
          v-for="m in [
            { label: '收款', key: 'receiptCent' },
            { label: '退款', key: 'refundCent' },
            { label: '净收款', key: 'netCent' },
          ]"
          :key="m.key"
          :label="m.label + '（元）'"
          align="right"
          ><template #default="{ row }">{{
            money(row[m.key])
          }}</template></el-table-column
        ><el-table-column width="90"
          ><template #default>钻取 →</template></el-table-column
        ></el-table
      ><template v-else
        ><el-table
          :data="details.slice((detailPage - 1) * 20, detailPage * 20)"
          class="clickable"
          @row-click="(r: Row) => router.push('/orders/' + r.orderId)"
          ><el-table-column label="事件" width="90"
            ><template #default="{ row }"
              ><StatusTag :value="row.entryType" /></template></el-table-column
          ><el-table-column
            label="业务日"
            prop="businessDate"
            width="110"
          /><el-table-column label="商户 / 门店" min-width="140"
            ><template #default="{ row }"
              >{{ row.merchantName
              }}<span class="subline">{{ row.storeName }}</span></template
            ></el-table-column
          ><el-table-column
            label="事件标识"
            prop="eventKey"
            min-width="220"
          /><el-table-column label="金额（元）" align="right" width="120"
            ><template #default="{ row }">{{
              money(row.amountCent)
            }}</template></el-table-column
          ></el-table
        >
        <div class="pagination">
          <el-pagination
            v-model:current-page="detailPage"
            :total="details.length"
            :page-size="20"
            layout="total,prev,pager,next"
          /></div
      ></template>
    </section>
    <div class="overview-bottom">
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>差错关联分析</h2>
            <p>商户 / 门店 × 业务环节 · 最近完成批次</p>
          </div>
        </div>
        <el-table :data="reconciliation.associations"
          ><el-table-column label="商户与门店" min-width="160"
            ><template #default="{ row }"
              >{{ row.merchantName
              }}<span class="subline">{{ row.storeName }}</span></template
            ></el-table-column
          ><el-table-column
            label="环节"
            prop="reasonCode"
            min-width="150"
          /><el-table-column
            label="数量"
            prop="count"
            width="60"
          /><el-table-column label="涉及金额" align="right" width="110"
            ><template #default="{ row }">{{
              money(row.amountCent)
            }}</template></el-table-column
          ></el-table
        >
      </section>
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>差错协作效率</h2>
            <p>已关闭工单平均处理时长，未完成单独统计</p>
          </div>
        </div>
        <el-table :data="cases"
          ><el-table-column label="状态" width="105"
            ><template #default="{ row }"
              ><StatusTag :value="row.status" /></template></el-table-column
          ><el-table-column
            label="差错类型"
            prop="classification"
            min-width="150"
          /><el-table-column
            label="数量"
            prop="count"
            width="60"
          /><el-table-column label="平均分钟" width="100"
            ><template #default="{ row }">{{
              row.averageMinutes ? Number(row.averageMinutes).toFixed(1) : "—"
            }}</template></el-table-column
          ></el-table
        >
      </section>
    </div>
    <div class="help-note">
      {{ data.definition }}。{{
        reconciliation.definition
      }}。明细单次导出上限10000条。
    </div>
  </div>
</template>
