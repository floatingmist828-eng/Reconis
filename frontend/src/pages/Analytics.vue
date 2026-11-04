<script setup lang="ts">
import { computed, ref, reactive, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { Filter, ArrowDown } from "@element-plus/icons-vue";
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
import RiskPanel from "../components/RiskPanel.vue";
import { drillFilters, type AnalysisFilters } from "../analyticsFilters";
const analysisSections = [
  { id: "analysis-overview", label: "交易概况" },
  { id: "analysis-groups", label: "分组钻取" },
  { id: "analysis-risk", label: "差错风险" },
  { id: "analysis-associations", label: "关联分析" },
  { id: "analysis-sources", label: "对账来源" },
];
const activeSection = ref("analysis-overview");
let sectionFrame = 0;
function updateActiveSection() {
  sectionFrame = 0;
  const offset = window.matchMedia("(max-width: 650px)").matches ? 140 : 152;
  let current = analysisSections[0]!.id;
  for (const section of analysisSections) {
    if ((document.getElementById(section.id)?.getBoundingClientRect().top ?? Infinity) <= offset) current = section.id;
  }
  activeSection.value = current;
}
function onSectionScroll() {
  if (!sectionFrame) sectionFrame = requestAnimationFrame(updateActiveSection);
}
function navigateToSection(id: string) {
  const target = document.getElementById(id);
  if (!target) return;
  activeSection.value = id;
  target.focus({ preventScroll: true });
  target.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
}
onMounted(() => {
  window.addEventListener("scroll", onSectionScroll, { passive: true });
  window.addEventListener("resize", onSectionScroll, { passive: true });
  updateActiveSection();
});
onBeforeUnmount(() => {
  window.removeEventListener("scroll", onSectionScroll);
  window.removeEventListener("resize", onSectionScroll);
  cancelAnimationFrame(sectionFrame);
});
const router = useRouter(),
  filters = reactive<AnalysisFilters>({
    start: dateDays(-29),
    end: dateDays(),
    merchantId: "",
    storeId: "",
    minCent: "",
    maxCent: "",
    dimension: "merchant",
    reconciliationSource: "BILL",
  }),
  applied = ref<AnalysisFilters>({ ...filters }),
  merchants = ref<Row[]>([]),
  data = ref<Row>({ summary: {}, groups: [], trend: [] }),
  reconciliation = ref<Row>({
    distribution: [],
    associations: [],
    batches: [],
  }),
  cases = ref<Row[]>([]),
  risk = ref<Row>({ summary: {}, aging: [], stores: [], trend: [] }),
  details = ref<Row[] | null>(null),
  busy = ref(false),
  loaded = ref(false),
  loadError = ref(false),
  advancedOpen = ref(false),
  history = ref<{ label: string; filters: AnalysisFilters }[]>([]),
  detailPage = ref(1),
  detailTotal = ref(0),
  detailSize = ref(20);
let requestVersion = 0;
const filtersChanged = computed(() =>
  Object.keys(filters).some(
    (key) =>
      key !== "asOf" &&
      filters[key as keyof AnalysisFilters] !==
        applied.value[key as keyof AnalysisFilters],
  ),
);
async function load(
  next: AnalysisFilters = { ...filters },
  showDetails = false,
) {
  const version = ++requestVersion;
  busy.value = true;
  loadError.value = false;
  try {
    // The server chooses the cutoff; subsequent panels, drilldowns and exports reuse it.
    const transactionData = await api("/analytics/transactions?" + query(next));
    const snapshot: AnalysisFilters = { ...next, ...transactionData.filters };
    const q = query(snapshot);
    const [reconciliationData, caseData, riskData, page] = await Promise.all([
      api("/analytics/reconciliation?" + q),
      api("/analytics/cases?" + q),
      api("/analytics/risk?" + q),
      showDetails
        ? api(
            "/analytics/transactions/details?" +
              query({ ...snapshot, page: 1, pageSize: detailSize.value }),
          )
        : Promise.resolve(null),
    ]);
    if (version !== requestVersion) return;
    data.value = transactionData;
    reconciliation.value = reconciliationData;
    cases.value = caseData;
    risk.value = riskData;
    applied.value = snapshot;
    Object.assign(filters, snapshot);
    details.value = page?.items ?? null;
    detailTotal.value = Number(page?.total ?? 0);
    detailPage.value = 1;
    loaded.value = true;
  } catch (error) {
    if (version === requestVersion) loadError.value = true;
    throw error;
  } finally {
    if (version === requestVersion) busy.value = false;
  }
}
async function applyFilters() {
  const next = { ...filters };
  delete next.asOf;
  await load(next);
  history.value = [];
}
onMounted(() =>
  action(async () => {
    await load();
    merchants.value = (await api("/merchants?pageSize=100")).items;
  }),
);
const trend = computed(() => ({
  color: ["#325ddb", "#d89536"],
  tooltip: { trigger: "axis" },
  legend: { data: ["收款", "退款"], top: 5, right: 0 },
  grid: { top: 45, right: 22, bottom: 35, left: 60 },
  xAxis: {
    type: "category",
    data: data.value.trend.map((r: Row) => r.day),
    axisLabel: { fontSize: 12 },
    axisTick: { show: false },
  },
  yAxis: {
    type: "value",
    name: "元",
    splitLine: { lineStyle: { color: "#edf0f5", type: "dashed" } },
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
    "#325ddb",
    "#d89536",
    "#23958b",
    "#8094cf",
    "#c66c72",
    "#75aabd",
    "#b6a0ce",
    "#7e8fa6",
  ],
  tooltip: { trigger: "item" },
  legend: { bottom: 0, type: "scroll", textStyle: { fontSize: 12 } },
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
  const parent = { ...applied.value };
  const showDetails =
    parent.dimension === "store" || parent.dimension === "day";
  await load(drillFilters(parent, String(g.groupKey)), showDetails);
  history.value.push({ label: String(g.groupName), filters: parent });
}
async function restore(index: number) {
  if (index === history.value.length - 1) return;
  const target = history.value[index === -1 ? 0 : index + 1];
  if (!target) return;
  await load({ ...target.filters });
  history.value = index === -1 ? [] : history.value.slice(0, index + 1);
}
async function loadDetails(page = 1) {
  const version = ++requestVersion;
  busy.value = true;
  try {
    const result = await api(
      "/analytics/transactions/details?" +
        query({ ...applied.value, page, pageSize: detailSize.value }),
    );
    if (version !== requestVersion) return;
    details.value = result.items;
    detailTotal.value = Number(result.total);
    detailPage.value = Number(result.page);
  } finally {
    if (version === requestVersion) busy.value = false;
  }
}
</script>
<template>
  <div class="analysis-page" v-loading="busy && !loaded" :aria-busy="busy">
    <div class="page-heading">
      <div>
        <h1>经营与差错分析</h1>

      </div>
      <div class="heading-actions">
        <el-dropdown
          @command="
            (format: string) =>
              action(() => exportFile('transactions', format, applied))
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
    <el-alert v-if="loadError" :title="loaded ? '分析未能更新，当前保留上次结果' : '分析数据未能加载'" type="error" :closable="false" show-icon class="analysis-error"><el-button link type="primary" @click="action(applyFilters)">重新加载</el-button></el-alert>
    <section class="panel analysis-filters" aria-label="分析筛选">
      <div class="filter-caption"><b>统计范围</b><span role="status">{{ busy ? '正在更新…' : data.asOf ? '截止 ' + new Date(data.asOf).toLocaleString('zh-CN') : '等待数据' }}</span></div>
      <div class="table-toolbar analysis-main-filters">
        <div class="analysis-date-range"><el-date-picker
          v-model="filters.start"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="开始日" aria-label="统计开始日期"
          style="width: 150px"
        /><span class="muted">至</span
        ><el-date-picker
          v-model="filters.end"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="结束日" aria-label="统计结束日期"
          style="width: 150px"
        /></div><el-select
          v-model="filters.merchantId"
          placeholder="全部商户" aria-label="统计商户"
          clearable
          @change="filters.storeId = ''"
          ><el-option
            v-for="m in merchants"
            :key="m.id"
            :label="m.name"
            :value="m.id" /></el-select
        ><el-select v-model="filters.dimension" placeholder="汇总维度" aria-label="汇总维度"
          ><el-option label="按商户" value="merchant" /><el-option
            label="按门店"
            value="store" /><el-option label="按年" value="year" /><el-option
            label="按月"
            value="month" /><el-option label="按日" value="day" /></el-select
        ><el-button type="primary" :loading="busy" @click="action(applyFilters)"
          >更新分析</el-button
        ><el-button class="advanced-toggle" :icon="Filter" :aria-expanded="advancedOpen" aria-controls="analysis-advanced" @click="advancedOpen = !advancedOpen">高级筛选<el-icon :class="{ rotated: advancedOpen }"><ArrowDown /></el-icon></el-button>
      </div>
      <el-alert
        v-if="filtersChanged"
        title="筛选已修改，更新分析后生效"
        type="info"
        :closable="false"
        style="margin-bottom: 12px"
      />
      <div id="analysis-advanced" class="advanced-collapse" :class="{ expanded: advancedOpen }" :inert="!advancedOpen"><div><div class="table-toolbar analysis-advanced-filters">
        <el-input
          v-model="filters.minCent"
          placeholder="最小金额（分）" aria-label="最小金额（分）"
          inputmode="numeric"
          style="width: 160px"
        /><el-input
          v-model="filters.maxCent"
          placeholder="最大金额（分）" aria-label="最大金额（分）"
          inputmode="numeric"
          style="width: 160px"
        /><el-select
          v-model="filters.reconciliationSource"
          aria-label="对账来源"
          style="width: 180px"
        >
          <el-option label="账单对账统计" value="BILL" />
          <el-option label="单笔渠道核查统计" value="TRADE_QUERY" /> </el-select
        >
      </div></div></div>
    </section>
    <p class="help-note">
      {{ applied.start }} 至 {{ applied.end }} · {{
        data.scopeDescription
      }}
    </p>
    <nav class="analysis-navigation" aria-label="分析页面分区">
      <a v-for="section in analysisSections" :key="section.id" :href="'#'+section.id" :class="{ active: activeSection === section.id }" :aria-current="activeSection === section.id ? 'location' : undefined" @click.prevent="navigateToSection(section.id)">{{ section.label }}</a>
    </nav>
    <div id="analysis-overview" class="kpi-grid analysis-section" tabindex="-1" aria-label="交易概况" style="margin-top: 23px">
      <div
        v-for="m in [
          { label: '净收款', key: 'netCent' },
          { label: '成功收款', key: 'receiptCent' },
          { label: '成功退款', key: 'refundCent' },
        ]"
        :key="m.key"
        class="kpi-card" :class="{ 'net-kpi': m.key === 'netCent' }"
      >
        <span class="muted">{{ m.label }}</span
        ><strong><em>¥</em>{{ money(data.summary[m.key]) }}</strong
        ><small>{{ m.key === 'netCent' ? '收款减去退款' : '按资金发生日期' }}</small>
      </div>
      <div class="kpi-card">
        <span class="muted">资金事件数</span
        ><strong>{{ data.summary.eventCount || "0" }}</strong
        ><small>收款与退款分别计数</small>
      </div>
    </div>
    <div
      class="overview-main analysis-charts"
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
        <div class="panel-heading"><h2>有效结果分布</h2></div>
        <Chart
          v-if="reconciliation.distribution.length"
          :option="distribution"
        /><el-empty v-else description="暂无完成的对账结果" :image-size="80" />
      </section>
    </div>
    <section id="analysis-groups" class="panel analysis-section" tabindex="-1" aria-label="分组钻取" style="margin-bottom: 23px">
      <div class="panel-heading">
        <div>
          <h2>{{ details ? "交易明细" : "分组汇总与钻取" }}</h2>
          <p>点击分组查看下一级，直至交易明细</p>
        </div>
        <el-button link type="primary" @click="action(() => loadDetails())"
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
          :data="details"
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
            :total="detailTotal"
            v-model:page-size="detailSize"
            :page-sizes="[20, 50, 100]"
            layout="total,sizes,prev,pager,next,jumper"
            @current-change="(page: number) => action(() => loadDetails(page))"
            @size-change="() => action(() => loadDetails())"
          /></div
      ></template>
    </section>
    <div id="analysis-risk" class="analysis-section" tabindex="-1" aria-label="差错风险"><RiskPanel :data="risk" :filters="applied" /></div>
    <div id="analysis-associations" class="overview-bottom analysis-section" tabindex="-1" aria-label="关联分析">
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>差错关联分析</h2>
            <p>商户 / 门店 × 业务环节 · 所选范围的有效观察</p>
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
          ><template #default="{ row }">{{ codeName(row.reasonCode) }}</template></el-table-column><el-table-column
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
            <p>
              按起因交易筛选，以数据截止时间的审计历史还原状态；缺关闭时间不计平均时长
            </p>
          </div>
        </div>
        <el-table :data="cases"
          ><el-table-column label="状态" width="105"
            ><template #default="{ row }"
              ><el-tag
                v-if="row.status === 'HISTORY_UNAVAILABLE'"
                type="warning"
                >历史状态未记录</el-tag
              ><StatusTag
                v-else
                :value="row.status" /></template></el-table-column
          ><el-table-column
            label="差错类型"
            prop="classification"
            min-width="150"
          ><template #default="{ row }">{{ codeName(row.classification) }}</template></el-table-column><el-table-column
            label="数量"
            prop="count"
            width="60"
          /><el-table-column label="平均分钟" width="100"
            ><template #default="{ row }">{{
              row.averageMinutes != null
                ? Number(row.averageMinutes).toFixed(1)
                : "—"
            }}</template></el-table-column
          ></el-table
        >
      </section>
    </div>
    <section
      id="analysis-sources"
      class="panel analysis-section"
      tabindex="-1"
      aria-label="对账来源"
      style="margin-top: 23px"
    >
      <div class="panel-heading">
        <div>
          <h2>统计采用的对账批次</h2>
          <p>
            同账期优先采用渠道确认完整的账单，其次声明完整、完整性未知；同等级取最新。单笔核查另行统计，可凭批次号核对来源。
          </p>
        </div>
      </div>
      <el-table :data="reconciliation.batches">
        <el-table-column label="批次号" prop="batchNo" min-width="220" />
        <el-table-column label="商户" prop="merchantName" min-width="130" />
        <el-table-column label="来源" prop="sourceType" width="130"><template #default="{ row }">{{ codeName(row.sourceType) }}</template></el-table-column>
        <el-table-column label="完整性" prop="completeness" min-width="150"><template #default="{ row }">{{ codeName(row.completeness) }}</template></el-table-column>
        <el-table-column label="纳入结果数" prop="resultCount" width="120" />
      </el-table>
    </section>
    <details class="analysis-definition"><summary>统计口径与导出说明</summary><div class="help-note">
      {{ data.definition }}。{{
        reconciliation.definition
      }}。单次最多导出 100000 条。
    </div></details>
  </div>
</template>

<style scoped>
.analysis-page { min-width: 0; }

.analysis-filters { padding: 22px 24px; margin-bottom: 16px; }
.filter-caption { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 17px; font-size: 13px; }
.filter-caption b { font-weight: 600; }
.filter-caption span { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
.analysis-main-filters { margin-bottom: 0; gap: 10px; }
.analysis-main-filters > .el-select { width: 160px; }
.analysis-date-range { display: grid; grid-template-columns: minmax(0,1fr) 16px minmax(0,1fr); gap: 8px; align-items: center; width: 328px; }
.analysis-date-range .el-date-editor { width: 100% !important; min-width: 0; }
.analysis-date-range .muted { text-align: center; }
.advanced-toggle .el-icon:last-child { margin-left: 7px; transition: transform .2s; }
.advanced-toggle .rotated { transform: rotate(180deg); }
.advanced-collapse { display: grid; grid-template-rows: 0fr; transition: grid-template-rows .22s ease; }
.advanced-collapse.expanded { grid-template-rows: 1fr; }
.advanced-collapse > div { overflow: hidden; min-height: 0; }
.analysis-advanced-filters { padding-top: 18px; margin-top: 18px; margin-bottom: 0; border-top: 1px solid var(--line); }
.analysis-filters .el-alert { margin-top: 14px; margin-bottom: 0 !important; }
.analysis-error { margin-bottom: 20px; }
.analysis-page #analysis-overview { gap: 0; background: #fff; border: 1px solid var(--line); border-radius: 12px; margin: 0 0 24px; overflow: hidden; grid-template-columns: 1.2fr 1fr 1fr 1fr; }
.analysis-page #analysis-overview .kpi-card { border: 0; border-right: 1px solid var(--line); border-radius: 0; box-shadow: none; padding: 26px 25px; min-width: 0; background: transparent; }
.analysis-page #analysis-overview .kpi-card:last-child { border-right: 0; }
.analysis-page #analysis-overview .net-kpi { background: #edf2fc; }
.analysis-page #analysis-overview strong { font-size: clamp(26px, 2.2vw, 37px); font-weight: 550; letter-spacing: -1px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.analysis-page #analysis-overview small { font-size: 11px; }
.analysis-charts { grid-template-columns: minmax(0, 1.65fr) minmax(260px, 1fr); }
.analysis-definition { margin-top: 24px; padding: 20px 0; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; }
.analysis-definition summary { width: fit-content; cursor: pointer; padding: 6px 0; }
.analysis-definition .help-note { max-width: 95ch; line-height: 1.9; margin-top: 12px; }

.analysis-navigation {
  position: sticky;
  top: 72px;
  z-index: 12;
  display: flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  min-width: 0;
  padding: 6px;
  margin: 20px 0 24px;
  background: #ffffffed;
  border: 1px solid #e2e8f2;
  border-radius: 11px;
  box-shadow: 0 4px 14px #172b4d06;
  backdrop-filter: blur(12px);
  overflow-x: auto;
  scrollbar-width: thin;
  scrollbar-color: #c4cee1 transparent;
}
.analysis-navigation a {
  display: block;
  flex: none;
  padding: 10px 20px;
  font-size: 13px;
  line-height: 1.4;
  color: #62738e;
  border-radius: 7px;
  white-space: nowrap;
  transition: background .18s, color .18s;
}
.analysis-navigation a:hover { color: #325ddb; background: #f5f7fd; }
.analysis-navigation a.active { color: #325ddb; background: #edf2ff; font-weight: 600; }
.analysis-navigation a:focus-visible { outline: 2px solid #325ddb; outline-offset: -2px; }
.analysis-section { scroll-margin-top: 148px; min-width: 0; }
.analysis-section:focus { outline: none; }
@media (max-width: 1050px) {
  .analysis-page #analysis-overview { grid-template-columns: repeat(2, minmax(0,1fr)); }
  .analysis-page #analysis-overview .kpi-card:nth-child(2) { border-right: 0; }
  .analysis-page #analysis-overview .kpi-card:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .analysis-charts { grid-template-columns: 1fr; }
}
@media (max-width: 650px) {
  .analysis-filters { padding: 20px 16px; }
  .filter-caption { flex-wrap: wrap; }
  .filter-caption span { font-size: 11px; }
  .analysis-main-filters > .el-select { flex: 1 1 110px; min-width: 0; }
  .analysis-date-range { width: 100%; flex-basis: 100%; }
  .analysis-advanced-filters > .el-input { width: calc(50% - 8px) !important; min-width: 0; }
  .analysis-page #analysis-overview .kpi-card { padding: 23px 17px; }
  .analysis-page #analysis-overview strong { font-size: 26px; }

  .analysis-navigation { top: 64px; padding: 6px; margin-top: 16px; margin-bottom: 18px; gap: 2px; }
  .analysis-navigation a { padding: 10px 9px; font-size: 12px; text-align: center; }
  .analysis-section { scroll-margin-top: 136px; }
}
@media (prefers-reduced-motion: reduce) {
  .analysis-navigation a, .advanced-collapse, .advanced-toggle .el-icon { transition: none; }
}
</style>
