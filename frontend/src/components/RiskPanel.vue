<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { api, query, money, time, action, codeName, type Row } from "../api";
import { ratioPercent, type AnalysisFilters } from "../analyticsFilters";
import StatusTag from "./StatusTag.vue";

const props = defineProps<{ data: Row; filters: AnalysisFilters }>();
const router = useRouter(), summary = computed(() => props.data.summary || {});
const visible = ref(false), loading = ref(false), rows = ref<Row[]>([]), total = ref(0), page = ref(1), kind = ref("exceptions"), detailTitle = ref("");
const caseDetails = computed(() => ["overdue", "closed", "onTime", "slaEligible", "incomplete"].includes(kind.value));
let detailFilters: Row = {}, version = 0;
const ageNames: Record<string,string> = { UNDER_1: "不足1天", DAYS_1_2: "1–2天", DAYS_3_6: "3–6天", DAYS_7_PLUS: "7天及以上" };
watch(() => props.filters, () => { ++version; visible.value = false; loading.value = false; });
async function openDetails(type: string, title: string, extra: Row = {}) {
  kind.value = type; detailTitle.value = title;
  detailFilters = { ...props.filters, ...extra, kind: type };
  visible.value = true; rows.value = []; total.value = 0;
  await loadDetails(1);
}
async function loadDetails(next: number) {
  const request = ++version; loading.value = true;
  try {
    const result = await api("/analytics/risk/details?" + query({ ...detailFilters, page: next, pageSize: 20 }));
    if (request !== version) return;
    rows.value = result.items; total.value = Number(result.total); page.value = next;
  } finally { if (request === version) loading.value = false; }
}
function storeDetails(row: Row) {
  return openDetails("exceptions", `${row.storeName || "渠道待定位"}的差错事件`, {
    merchantId: row.merchantId == null ? "" : String(row.merchantId),
    storeId: row.storeId == null ? "" : String(row.storeId), unlocated: row.storeId == null ? "true" : "",
    unassignedMerchant: row.merchantId == null ? "true" : "",
  });
}
function minutes(value: unknown) { return value == null ? "—" : Number(value).toFixed(1); }
function caseIds(row: Row): string[] { return row.caseIds ? String(row.caseIds).split(",") : row.caseNo ? [String(row.id)] : []; }
function closedMinutes(row: Row): string {
  if (!row.closedAt || !row.createdAt) return "—";
  const value = (Date.parse(row.closedAt) - Date.parse(row.createdAt)) / 60000;
  return Number.isFinite(value) && value >= 0 ? value.toFixed(1) : "—";
}
</script>
<template>
  <section class="panel risk-panel">
    <div class="panel-heading"><div><h2>风险与处理质量</h2><p>点击指标查看对应明细，再进入订单、对账批次或工单。</p></div></div>
    <div class="risk-metrics">
      <button class="risk-metric" @click="action(() => openDetails('pending', '待核实资金事件'))"><span>待核实金额</span><strong>¥{{ money(summary.pendingCent) }}</strong><small>{{ summary.pendingEventCount || 0 }} 个事件 · {{ summary.openCaseCount || 0 }} 张未关闭工单</small></button>
      <button class="risk-metric" @click="action(() => openDetails('exceptions', '有效差错事件'))"><span>差错率</span><strong>{{ ratioPercent(summary.exceptionEventCount, summary.eventCount) }}</strong><small>{{ summary.exceptionEventCount || 0 }} / {{ summary.eventCount || 0 }} 个已对账事件</small></button>
      <button class="risk-metric" @click="action(() => openDetails('exceptions', '差额及待核实项目'))"><span>可计算绝对差额</span><strong>¥{{ money(summary.differenceCent) }}</strong><small>另有 {{ summary.uncertainDifferenceCount || 0 }} 个事件差额待核实</small></button>
      <button class="risk-metric" @click="action(() => openDetails('overdue', '逾期未关闭工单'))"><span>逾期未关闭</span><strong>{{ summary.overdueCaseCount || 0 }}</strong><small>按截止时间和工单期限计算</small></button>
      <button class="risk-metric" @click="action(() => openDetails('slaEligible', '按时关闭率的统计工单'))"><span>按时关闭率</span><strong>{{ ratioPercent(summary.onTimeCount, summary.slaEligibleCount) }}</strong><small>{{ summary.onTimeCount || 0 }} / {{ summary.slaEligibleCount || 0 }} 张期限及关闭时间有效的工单</small></button>
      <button class="risk-metric" @click="action(() => openDetails('closed', '处理时长的统计工单'))"><span>关闭耗时 · 中位数 / P95</span><strong>{{ minutes(summary.medianMinutes) }} / {{ minutes(summary.p95Minutes) }}</strong><small>分钟 · {{ summary.closedCaseCount || 0 }} 张关闭时间有效的工单</small></button>
    </div>
    <p class="help-note">待核实金额及差额不代表实际损失。P95是关闭耗时的第95百分位，用于观察较慢工单。前期 {{ data.previous?.start }} 至 {{ data.previous?.end }}：{{ data.previous?.exceptionEventCount || 0 }} / {{ data.previous?.eventCount || 0 }} 个事件存在差错，差错率 {{ ratioPercent(data.previous?.exceptionEventCount, data.previous?.eventCount) }}；与本期使用同一数据截止时间。</p>
    <el-alert v-if="Number(summary.incompleteHistoryCount) > 0" type="warning" :closable="false" show-icon>
      <template #title>{{ summary.incompleteHistoryCount }} 张工单的历史状态、期限或关闭时间不完整，相应指标未将未知值当作零。<el-button link @click="action(() => openDetails('incomplete', '历史记录不完整的工单'))">查看工单</el-button></template>
    </el-alert>
    <div class="risk-columns">
      <div><h3>待核实账龄</h3><el-table :data="data.aging || []" class="clickable" @row-click="(r: Row) => action(() => openDetails('pending', ageNames[r.ageBucket] + '待核实事件', { ageBucket: r.ageBucket }))">
        <el-table-column label="账龄"><template #default="{ row }">{{ ageNames[row.ageBucket] }}</template></el-table-column>
        <el-table-column prop="eventCount" label="事件数" width="85" />
        <el-table-column label="待核实金额（元）" align="right"><template #default="{ row }">{{ money(row.pendingCent) }}</template></el-table-column>
      </el-table></div>
      <div><h3>按交易日查看差错</h3><el-table :data="data.trend || []" max-height="275" class="clickable" @row-click="(r: Row) => action(() => openDetails('events', r.day + '已对账事件', { day: r.day }))">
        <el-table-column label="交易日" prop="day" min-width="110" />
        <el-table-column label="差错 / 已对账" min-width="115"><template #default="{ row }">{{ row.exceptionEventCount }} / {{ row.eventCount }}</template></el-table-column>
        <el-table-column label="差错率" align="right"><template #default="{ row }">{{ ratioPercent(row.exceptionEventCount, row.eventCount) }}</template></el-table-column>
      </el-table></div>
    </div>
    <h3>门店差错排名 <small class="muted">前20项 · 多个发生日可帮助识别重复问题</small></h3>
    <el-table :data="data.stores || []" class="clickable" @row-click="(r: Row) => action(() => storeDetails(r))">
      <el-table-column label="商户 / 门店" min-width="180"><template #default="{ row }">{{ row.merchantName || '归属待定位' }}<span class="subline">{{ row.storeName }}{{ row.storeId ? ` [${row.storeId}]` : '' }}</span></template></el-table-column>
      <el-table-column label="差错事件" prop="exceptionEventCount" width="95" />
      <el-table-column label="发生天数" prop="exceptionDays" width="95" />
      <el-table-column label="差错率" width="100"><template #default="{ row }">{{ ratioPercent(row.exceptionEventCount, row.eventCount) }}</template></el-table-column>
      <el-table-column label="可计算差额（元）" align="right" min-width="140"><template #default="{ row }">{{ money(row.differenceCent) }}</template></el-table-column>
      <el-table-column label="差额待核实事件" prop="uncertainDifferenceCount" width="130" />
    </el-table>
    <el-collapse style="margin-top: 16px"><el-collapse-item title="统计口径与数据来源" name="definitions"><p v-for="definition in data.definitions || []" :key="definition">{{ definition }}</p><el-button link type="primary" @click="action(() => openDetails('events', '差错率分母：全部已对账事件'))">查看全部已对账事件</el-button><el-button link type="primary" @click="action(() => openDetails('onTime', '按时关闭的工单'))">查看按时关闭工单</el-button></el-collapse-item></el-collapse>
  </section>
  <el-dialog v-model="visible" :title="detailTitle" width="min(1100px, 96vw)">
    <p class="help-note">{{ detailFilters.start }} 至 {{ detailFilters.end }} · 截止 {{ detailFilters.asOf }}。进入业务详情后可查看当前处理进度与原始证据。</p>
    <el-table v-loading="loading" :data="rows" max-height="480">
      <el-table-column label="事件 / 工单" min-width="200"><template #default="{ row }">{{ row.caseNo || row.sourceEventKey }}<span v-if="row.caseCount" class="subline">关联 {{ row.caseCount }} 张未关闭工单</span></template></el-table-column>
      <el-table-column label="商户 / 门店" min-width="160"><template #default="{ row }">{{ row.merchantName || '归属待定位' }}<span class="subline">{{ row.storeName }}</span></template></el-table-column>
      <el-table-column label="状态 / 结果" min-width="135"><template #default="{ row }"><StatusTag v-if="row.status && row.status !== 'HISTORY_UNAVAILABLE'" :value="row.status" /><span v-else>{{ row.status === 'HISTORY_UNAVAILABLE' ? '历史状态缺失' : row.classifications?.split(',').map(codeName).join('、') || '待核实' }}</span></template></el-table-column>
      <el-table-column label="涉及金额（元）" align="right" width="140"><template #default="{ row }">{{ money(row.amountCent) }}</template></el-table-column>
      <el-table-column v-if="kind === 'events' || kind === 'exceptions'" label="绝对差额（元）" align="right" width="140"><template #default="{ row }">{{ row.differenceCent == null ? '待核实' : money(row.differenceCent) }}</template></el-table-column>
      <el-table-column v-if="kind === 'pending'" label="账龄（天）" prop="ageDays" width="95" />
      <el-table-column v-if="caseDetails" label="期限 / 关闭时间" min-width="175"><template #default="{ row }">{{ time(row.dueAt) }}<span class="subline">关闭：{{ time(row.closedAt) }}</span></template></el-table-column>
      <el-table-column v-if="caseDetails" label="关闭耗时（分钟）" align="right" width="140"><template #default="{ row }">{{ closedMinutes(row) }}</template></el-table-column>
      <el-table-column label="来源与处理" min-width="160"><template #default="{ row }"><el-button v-if="row.orderId" link type="primary" @click="router.push('/orders/' + row.orderId)">订单</el-button><el-button v-if="row.batchId" link type="primary" @click="router.push('/reconciliation?batchId=' + row.batchId)">对账批次</el-button><el-button v-for="id in caseIds(row)" :key="id" link type="primary" @click="router.push('/cases/' + id)">工单 {{ id }}</el-button></template></el-table-column>
    </el-table>
    <div class="pagination"><el-pagination :current-page="page" :total="total" :page-size="20" layout="total,prev,pager,next,jumper" @current-change="(value: number) => action(() => loadDetails(value))" /></div>
  </el-dialog>
</template>
<style scoped>
.risk-panel { margin: 23px 0; }
.risk-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.risk-metric { display:flex; flex-direction:column; align-items:flex-start; text-align:left; gap:10px; padding:18px; background:#f5f8f3; border:1px solid #e1e8df; border-radius:12px; cursor:pointer; color:inherit; font:inherit; }
.risk-metric:hover { border-color:#3b8b71; }
.risk-metric:focus-visible { outline:2px solid #3b8b71; outline-offset:2px; }
.risk-metric strong { font-size:23px; overflow-wrap:anywhere; }
.risk-metric small { color:#68766a; line-height:1.5; }
.risk-columns { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:24px; }
h3 { margin-top:24px; }
@media(max-width:900px) { .risk-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); } .risk-columns { grid-template-columns:minmax(0,1fr); } }
@media(max-width:520px) { .risk-metrics { grid-template-columns:minmax(0,1fr); } }
</style>
