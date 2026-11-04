<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from "vue";
import { api, post, time, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
const data = ref<Row>({ recentQueries: [] }),
  session = useSession(),
  busy = ref(false);
const canAuto = computed(() => session.finance || session.auditor);
const view = ref<"automatic" | "channel">(canAuto.value ? "automatic" : "channel");
const tasks = ref<Row>({ items: [] }), page = ref(1), taskBusy = ref(false), selected = ref<Row | null>(null);
const yesterday = new Date(Date.now() - 86400000).toLocaleDateString("sv-SE", { timeZone: "Asia/Shanghai" });
const range = ref<[string, string]>([yesterday, yesterday]), daily = ref(false);
const stateNames: Record<string, string> = { QUEUED: "排队中", FETCHING: "获取账单", VALIDATING: "校验原件", RECONCILING: "逐户对账", RETRY_WAIT: "等待重试", SUCCEEDED: "已完成", FAILED: "需处理", UNCONFIGURED: "待配置", UNSUPPORTED: "渠道未支持", TEMPLATE_ONLY: "仅沙箱模板" };
const retryable = ["FAILED", "RETRY_WAIT", "UNCONFIGURED", "UNSUPPORTED"];
const eventNames: Record<string, string> = { BILL_ATTEMPT: "开始执行", BILL_SCHEDULE: "更新每日获取设置", BILL_ORIGINAL_SAVED: "保存原始账单", BILL_QUEUED: "创建账期任务", BILL_REQUEUED: "申请补跑" };
let refreshTimer: ReturnType<typeof setInterval> | undefined;
function parse(value: unknown): Row { try { return JSON.parse(String(value || "{}")); } catch { return {}; } }
async function loadTasks() {
  if (!canAuto.value) return;
  tasks.value = await api(`/reconciliation/automatic?page=${page.value}`);
  const id = selected.value?.id;
  if (id) {
    const detail = await api(`/reconciliation/automatic/${id}`);
    if (selected.value?.id === id) selected.value = detail;
  }
}
async function createTasks() {
  if (!range.value?.[0] || !range.value?.[1]) return;
  taskBusy.value = true;
  try { await post("/reconciliation/automatic", { start: range.value[0], end: range.value[1], daily: daily.value }); await loadTasks(); }
  finally { taskBusy.value = false; }
}
async function retryTask(row: Row) { await post(`/reconciliation/automatic/${row.id}/retry`); await loadTasks(); }
async function schedule(row: Row) { await post(`/reconciliation/automatic/${row.id}/schedule`, { enabled: !row.acquisitionDaily }); await loadTasks(); }
async function showTask(row: Row) { selected.value = await api(`/reconciliation/automatic/${row.id}`); }
async function load() {
  busy.value = true;
  try {
    data.value = await api("/integration/status");
    session.integration = data.value;
    await loadTasks();
  } finally {
    busy.value = false;
  }
}
onMounted(() => {
  action(load);
  refreshTimer = setInterval(() => {
    if (!document.hidden && tasks.value.items?.some((t: Row) => ["QUEUED", "FETCHING", "VALIDATING", "RECONCILING"].includes(t.acquisitionState)))
      loadTasks().catch(() => {});
  }, 5000);
});
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer); });
</script>
<template>
  <div v-loading="busy">
    <div class="page-heading">
      <h1>{{ canAuto ? "渠道管理" : "渠道状态" }}</h1>
      <div class="heading-actions">
        <el-button v-if="!session.admin && !session.consumer" @click="$router.push('/reconciliation')">返回对账</el-button>
        <el-button @click="action(view === 'automatic' ? loadTasks : load)">刷新</el-button>
      </div>
    </div>
    <div v-if="canAuto" class="section-switch" role="group" aria-label="渠道管理类别">
      <button type="button" :aria-pressed="view === 'automatic'" @click="view = 'automatic'">自动对账</button>
      <button type="button" :aria-pressed="view === 'channel'" @click="view = 'channel'">渠道状态</button>
    </div>
    <section v-if="view === 'automatic' && canAuto" class="panel auto-bill-panel">
      <div class="panel-heading"><h2>账单获取任务</h2></div>
      <p v-if="tasks.capabilityMessage" class="muted auto-bill-note">{{ tasks.capabilityMessage }}</p>
      <div v-if="session.finance" class="auto-bill-form">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始账期" end-placeholder="结束账期" :disabled-date="(date: Date) => date.toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }) > yesterday" />
        <el-checkbox v-model="daily">每日获取</el-checkbox>
        <el-button type="primary" :loading="taskBusy" @click="action(createTasks)">新建任务</el-button>
      </div>
      <p v-if="tasks.scheduleTime" class="muted auto-bill-note">{{ tasks.scheduleTime }} · 单次最多 31 天</p>
      <el-table :data="tasks.items" empty-text="尚未创建自动对账任务">
        <el-table-column label="账期" width="125"><template #default="{ row }">{{ new Date(row.periodStart).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }) }}</template></el-table-column>
        <el-table-column label="进度" width="130"><template #default="{ row }"><el-tag :type="row.acquisitionState === 'SUCCEEDED' ? 'success' : retryable.includes(row.acquisitionState) ? 'warning' : 'info'">{{ stateNames[row.acquisitionState] || row.acquisitionState }}</el-tag></template></el-table-column>
        <el-table-column label="执行次数" width="100"><template #default="{ row }">{{ row.acquisitionAttempts }} / 4</template></el-table-column>
        <el-table-column label="处理说明" prop="acquisitionMessage" min-width="240" />
        <el-table-column label="下次重试" width="175"><template #default="{ row }">{{ time(row.acquisitionNextAt) }}</template></el-table-column>
        <el-table-column label="操作" width="120"><template #default="{ row }">
          <el-button link type="primary" @click="action(() => showTask(row))">详情</el-button>
          <el-button v-if="session.finance && retryable.includes(row.acquisitionState)" link type="primary" @click="action(() => retryTask(row))">补跑</el-button>
        </template></el-table-column>
      </el-table>
      <div class="pagination"><el-pagination v-model:current-page="page" :page-size="20" :total="Number(tasks.total || 0)" layout="prev, pager, next, total" @current-change="action(loadTasks)" /></div>
    </section>
    <el-dialog :model-value="!!selected" title="自动对账任务" width="min(900px, 94vw)" @close="selected = null">
      <template v-if="selected">
        <p><strong>{{ stateNames[selected.acquisitionState] }}</strong> · {{ selected.acquisitionMessage || '等待处理' }}</p>
        <p>第 {{ selected.acquisitionGeneration }} 轮 · 本轮 {{ selected.acquisitionAttempts }} 次尝试</p>
        <div v-if="selected.sourceFileUri" class="help-note"><a :href="`/api/v1/reconciliation/automatic/${selected.id}/original`">下载原始账单</a><br /><span style="overflow-wrap:anywhere">SHA-256：{{ selected.fileSha256 }}</span></div>
        <el-table v-if="parse(selected.acquisitionResult).batches" :data="parse(selected.acquisitionResult).batches">
          <el-table-column label="对账批次"><template #default="{ row }"><router-link :to="`/reconciliation?batchId=${row.id}`">查看批次 {{ row.id }}</router-link></template></el-table-column><el-table-column label="商户" prop="merchantId" /><el-table-column label="处理状态"><template #default="{ row }"><StatusTag :value="row.status" /></template></el-table-column>
        </el-table>
        <el-table v-if="parse(selected.acquisitionResult).errors?.length" :data="parse(selected.acquisitionResult).errors"><el-table-column label="原件行号" prop="line" width="100" /><el-table-column label="校验问题" prop="reason" /></el-table>
        <h3>执行记录</h3>
        <el-timeline><el-timeline-item v-for="event in selected.events" :key="event.id" :timestamp="time(event.occurredAt)"><strong>{{ eventNames[event.action] || stateNames[event.action.replace('BILL_', '')] || '任务记录' }}</strong><p style="overflow-wrap:anywhere">{{ parse(event.afterJson).message || (event.action === 'BILL_ORIGINAL_SAVED' ? '原件已保留，散列已记录' : event.action === 'BILL_ATTEMPT' ? `开始第${parse(event.afterJson).attempt}次执行` : event.action === 'BILL_QUEUED' ? '账期任务已创建' : event.action === 'BILL_SCHEDULE' ? (parse(event.afterJson).enabled ? '已启用后续每日获取' : '已停止后续每日获取') : '任务状态已记录') }}</p></el-timeline-item></el-timeline>
      </template>
      <template v-if="session.finance && selected" #footer>
        <el-button @click="action(() => schedule(selected!))">{{ selected.acquisitionDaily ? '停止每日获取' : '从此账期开始每日获取' }}</el-button>
      </template>
    </el-dialog>
    <div v-if="view === 'channel'">
    <div class="kpi-grid two">
      <section class="kpi-card">
        <span>当前环境</span
        ><strong style="font-size: 24px">{{ data.environment === "DEMO" ? "本地渠道" : data.environment === "ALIPAY_SANDBOX" ? "支付宝沙箱" : "—" }}</strong
        ><small>{{
          data.environment === "DEMO"
            ? "模拟支付"
            : "支付宝官方测试网关"
        }}</small>
      </section>
      <section class="kpi-card">
        <span>渠道配置</span
        ><strong style="font-size: 24px">{{
          data.configured ? "配置有效" : "待完善"
        }}</strong
        ><small>{{ data.configurationMessage || "正在检查配置" }}</small>
      </section>
    </div>
    <details class="panel disclosure channel-details">
      <summary>连接详情</summary>
      <div class="description-grid">
        <div><label>渠道</label><b>支付宝 · 人民币</b></div>
        <div>
          <label>交易能力</label
          ><b>预下单、交易查询、部分 / 全额退款、退款查询</b>
        </div>
        <div>
          <label>通知校验</label
          ><b>SDK RSA2 验签、应用及收款方身份、金额与订单核验</b>
        </div>
        <div>
          <label>结果恢复</label
          ><b>PENDING / UNKNOWN 及滞留 PROCESSING 主动查询</b>
        </div>
        <div>
          <label>定时扫描</label
          ><b
            >每
            {{
              data.automaticQueryIntervalSeconds ?? 15
            }}
            秒，失败后延长重试间隔</b
          >
        </div>
        <div>
          <label>异步通知</label
          ><b>{{ data.callbackMessage || "正在检查通知配置" }}</b>
        </div>
      </div>
    </details>
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h2>最近渠道查询</h2>
        </div>
      </div>
      <el-table :data="data.recentQueries"
        ><el-table-column label="查询时间" width="180"
          ><template #default="{ row }">{{
            time(row.occurredAt)
          }}</template></el-table-column
        ><el-table-column
          label="操作"
          prop="action"
          min-width="160" /><el-table-column
          label="渠道返回摘要"
          prop="summary"
          min-width="180" /><el-table-column label="结果" width="120"
          ><template #default="{ row }"
            ><StatusTag :value="row.result" /></template></el-table-column
      ></el-table>
    </section>
    </div>
  </div>
</template>
<style scoped>
.auto-bill-form { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.auto-bill-note { margin: 0 0 12px; font-size: 13px; }
.auto-bill-form + .auto-bill-note { margin-top: 10px; }
.channel-details { margin-bottom: 18px; }
.auto-bill-form :deep(.el-date-editor) { max-width: 100%; }
</style>
