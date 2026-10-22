<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from "vue";
import { api, action, money, time, codeName, type Row } from "../api";
import StatusTag from "./StatusTag.vue";
const props = defineProps<{ caseId: string; version: string }>();
const data = ref<Row | null>(null), busy = ref(false), opened = ref(false), error = ref("");
const tab = ref("diagnosis");
const labels: Record<string, string> = {
  VERIFIED: "散列一致", NO_BASELINE_HASH: "缺少原始散列", HASH_MISMATCH: "散列不一致",
  MISSING: "文件缺失", INVALID_PATH: "路径无效", UNREADABLE: "文件无法读取",
  TOO_LARGE: "超出打包上限", METADATA_MISMATCH: "附件归属记录异常", RESTRICTED: "原件受权限限制",
  ORIGINAL_BILL: "原始账单", CHANNEL_RESPONSE: "渠道响应", PAYMENT_NOTIFICATION: "支付通知",
  CASE_ATTACHMENT: "当前处理证据", HISTORICAL_ATTACHMENT: "历史处理证据",
};
let revision = 0;
function current(id: string, version: string, request: number) {
  return props.caseId === id && props.version === version && revision === request;
}
async function load() {
  const id = props.caseId, version = props.version, request = ++revision;
  busy.value = true;
  error.value = "";
  try {
    const next = await api(`/cases/${id}/dossier`, { quiet: true });
    if (current(id, version, request)) { data.value = next; opened.value = true; }
  } catch (e) {
    if (current(id, version, request)) error.value = e instanceof Error ? e.message : "证据档案加载失败";
  } finally { if (current(id, version, request)) busy.value = false; }
}
async function download() {
  const id = props.caseId, version = props.version, request = ++revision;
  busy.value = true;
  try {
    const response = await fetch(`/api/v1/cases/${id}/dossier/archive`, { credentials: "same-origin" });
    if (!response.ok) { const body = await response.json(); throw new Error(body.message || "档案下载失败"); }
    const blob = await response.blob();
    if (!current(id, version, request)) return;
    const url = URL.createObjectURL(blob), link = document.createElement("a");
    link.href = url; link.download = `Reconis-case-${id}-evidence.zip`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    if (current(id, version, request)) throw error;
  } finally { if (current(id, version, request)) busy.value = false; }
}
watch(() => [props.caseId, props.version], ([id], [previousId]) => {
  revision++;
  busy.value = false;
  data.value = null;
  error.value = "";
  if (id !== previousId) { opened.value = false; tab.value = "diagnosis"; }
  else if (opened.value) void load();
});
onBeforeUnmount(() => { revision++; });
</script>

<template>
  <section class="panel dossier" v-loading="busy">
    <div class="panel-heading">
      <div><h2>差错诊断与证据档案</h2><p>关联交易、原始账单、历次对账和复核依据</p></div>
      <div class="row-actions">
        <el-button @click="load">{{ data ? "刷新核验" : "查看档案" }}</el-button>
        <el-button v-if="data" type="primary" @click="action(download)">下载 ZIP</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="data">
      <el-alert v-if="data.integrity.failedFiles !== '0' && data.integrity.failedFiles !== 0"
        title="部分证据缺失、无法核验或散列不一致，请核查文件清单。" type="error" :closable="false" show-icon />
      <div class="dossier-stats">
        <span>对账版本 <b>{{ data.reconciliationVersions.length }}</b></span>
        <span>账本事实 <b>{{ data.ledger.length }}</b></span>
        <span>原始行 <b>{{ data.statementLines.length }}</b></span>
        <span>证据文件 <b>{{ data.files.length }}</b></span>
      </div>
      <p class="muted">采集于 {{ time(data.collectionCompletedAt) }}；金额单位为人民币元。差额 = 本地金额 − 渠道金额。</p>
      <el-tabs v-model="tab">
        <el-tab-pane label="事实与核查" name="diagnosis">
          <h3>已记录事实</h3>
          <div v-for="(fact, index) in data.diagnosis.facts" :key="index" class="dossier-fact">
            <p>{{ fact.text }}</p>
            <span v-if="fact.differenceCent !== undefined">本地 ¥{{ money(fact.localAmountCent) }} / 渠道 ¥{{ money(fact.channelAmountCent) }} / 差额 ¥{{ money(fact.differenceCent) }}</span>
            <small class="muted">来源 {{ fact.sourceType === 'CASE' ? '工单' : '对账结果' }} #{{ fact.sourceId }}</small>
          </div>
          <h3>待验证的可能原因</h3>
          <p v-for="(h, index) in data.diagnosis.hypotheses" :key="index">{{ h.text }}</p>
          <h3>下一步核查</h3>
          <ol><li v-for="(check, index) in data.diagnosis.nextChecks" :key="index">{{ check }}</li></ol>
          <h3>{{ data.diagnosis.conclusion.status === 'REVIEWED' ? '已复核结论' : '当前处理意见（待复核）' }}</h3>
          <p style="white-space: pre-wrap">{{ data.diagnosis.conclusion.text || '尚未填写处理意见' }}</p>
        </el-tab-pane>
        <el-tab-pane label="交易与对账链路" name="chain">
          <h3>关联订单与支付</h3>
          <div v-for="order in data.orders" :key="order.id" class="dossier-fact">
            <router-link :to="'/orders/' + order.id">{{ order.orderNo }} ↗</router-link>
            <span>{{ order.merchantName }} / {{ order.storeName }} · ¥{{ money(order.amountCent) }}</span>
          </div>
          <el-table :data="data.payments" empty-text="暂无关联支付" size="small">
            <el-table-column prop="outTradeNo" label="支付号" min-width="180" />
            <el-table-column label="状态" width="110"><template #default="s"><StatusTag :value="s.row.status" /></template></el-table-column>
            <el-table-column label="确认时间" min-width="150"><template #default="s">{{ time(s.row.confirmedAt) }}</template></el-table-column>
          </el-table>
          <h3>退款与入账事实</h3>
          <el-table :data="data.refunds" empty-text="暂无退款" size="small">
            <el-table-column prop="outRequestNo" label="退款请求号" min-width="180" />
            <el-table-column label="金额" width="100"><template #default="s">¥{{ money(s.row.amountCent) }}</template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="s"><StatusTag :value="s.row.status" /></template></el-table-column>
          </el-table>
          <el-table :data="data.ledger" empty-text="暂无本地入账事实" size="small">
            <el-table-column prop="entryNo" label="流水号" min-width="180" />
            <el-table-column label="类型" width="80"><template #default="s"><StatusTag :value="s.row.entryType" /></template></el-table-column>
            <el-table-column label="金额" width="100"><template #default="s">¥{{ money(s.row.amountCent) }}</template></el-table-column>
            <el-table-column label="渠道时间" min-width="150"><template #default="s">{{ time(s.row.channelOccurredAt) }}</template></el-table-column>
          </el-table>
          <h3>渠道通知</h3>
          <el-table :data="data.notifications" empty-text="暂无留存通知" size="small">
            <el-table-column prop="id" label="记录号" width="90" />
            <el-table-column label="验签" width="80"><template #default="s">{{ s.row.signatureValid ? '通过' : '未通过' }}</template></el-table-column>
            <el-table-column prop="handlingResult" label="处理结果" min-width="110" />
            <el-table-column label="收到时间" min-width="150"><template #default="s">{{ time(s.row.receivedAt) }}</template></el-table-column>
          </el-table>
          <h3>原始账单行</h3>
          <el-table :data="data.statementLines" empty-text="暂无对应渠道账单行" size="small">
            <el-table-column prop="importBatchId" label="来源批次" width="100" />
            <el-table-column prop="lineNo" label="原始行号" width="90" />
            <el-table-column prop="outTradeNo" label="支付号" min-width="180" />
            <el-table-column label="金额" width="100"><template #default="s">¥{{ money(s.row.amountCent) }}</template></el-table-column>
          </el-table>
          <h3>渠道查询与退款审核历史</h3>
          <el-table :data="data.channelHistory" empty-text="暂无相关历史" size="small">
            <el-table-column label="操作" min-width="130"><template #default="s">{{ codeName(s.row.action) }}</template></el-table-column>
            <el-table-column label="结果" min-width="110"><template #default="s"><StatusTag :value="s.row.result" /></template></el-table-column>
            <el-table-column prop="summary" label="记录" min-width="170" />
            <el-table-column label="时间" min-width="150"><template #default="s">{{ time(s.row.occurredAt) }}</template></el-table-column>
          </el-table>
          <h3>历次对账与复核引用</h3>
          <div v-for="r in data.reconciliationVersions" :key="r.id" class="dossier-fact">
            <router-link :to="'/reconciliation?batchId=' + r.batchId">{{ r.batchNo }} ↗</router-link>
            <span>{{ codeName(r.classification) }} · 结果 #{{ r.id }} · {{ time(r.detectedAt) }}</span>
            <div><el-tag v-if="r.origin" size="small">工单起因</el-tag> <el-tag v-if="r.currentEvidence" size="small" type="success">当前引用</el-tag> <el-tag v-if="r.referencedInWorkflow" size="small" type="info">审核历史引用</el-tag></div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="文件完整性" name="files">
          <p class="muted">下载包包含结构化档案、可访问文件和 SHA-256 清单。缺少历史散列的文件仅记录本次散列；多商户原件仅向平台财务和审计开放。</p>
          <div v-for="(gap, index) in data.availabilityGaps" :key="'gap-' + index" class="dossier-fact">
            <b>未留存文件</b><span>{{ gap.message }} · {{ labels[gap.kind] || gap.kind }} #{{ gap.sourceId }}</span>
          </div>
          <div v-for="file in data.files" :key="file.sourceUri" class="dossier-fact">
            <b>{{ labels[file.verification] || file.verification }}</b>
            <span>{{ file.originalName || file.sourceUri }}</span>
            <small v-for="(source, index) in file.sources" :key="index" class="muted">{{ labels[source.kind] || source.kind }} #{{ source.id }} · {{ time(source.at) }}</small>
            <code v-if="file.sha256" class="dossier-hash">SHA-256 {{ file.sha256 }}</code>
            <p v-if="file.message">{{ file.message }}</p>
          </div>
          <el-empty v-if="!data.files.length" description="当前记录没有关联文件" :image-size="60" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </section>
</template>

<style scoped>
.dossier { min-width: 0; }
.dossier .panel-heading { flex-wrap: wrap; gap: 12px; }
.dossier-stats { display: flex; flex-wrap: wrap; gap: 14px; margin: 16px 0; }
.dossier-stats span { background: #f3f6f0; border-radius: 8px; padding: 9px 12px; }
.dossier-fact { display: flex; flex-direction: column; gap: 6px; padding: 12px 0; border-bottom: 1px solid #edf0e9; overflow-wrap: anywhere; }
.dossier-fact p { margin: 0; }
.dossier h3 { margin: 20px 0 10px; font-size: 14px; }
.dossier li { margin: 8px 0; }
.dossier-hash { font-size: 11px; overflow-wrap: anywhere; }
.dossier :deep(.el-table) { margin-top: 12px; }
</style>
