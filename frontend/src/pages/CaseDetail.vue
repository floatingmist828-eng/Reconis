<script setup lang="ts">
import { ref, reactive, watch, onBeforeUnmount, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { ArrowLeft, User, Timer, Flag } from "@element-plus/icons-vue";
import {
  api,
  post,
  patch,
  time,
  money,
  action,
  codeName,
  type Row,
} from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import CaseDossier from "../components/CaseDossier.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  row = ref<Row | null>(null),
  recommendations = ref<Row[]>([]),
  recommendationError = ref(false),
  members = ref<Row[]>([]),
  busy = ref(false),
  mutating = ref(false),
  assignDialog = ref(false),
  reviewDialog = ref(false),
  assignee = ref(""),
  evidence = ref<File>();
const form = reactive({ analysisText: "", resolutionText: "", version: "" }),
  review = reactive({
    approve: true,
    resolutionCode: "KNOWN_EXCEPTION",
    reason: "",
    evidenceResultId: "",
    attributionBatchId: "",
  });
const mine = computed(() => row.value?.assigneeId === session.user?.id);
const dirty = computed(
  () =>
    !!row.value &&
    (form.analysisText !== (row.value.analysisText || "") ||
      form.resolutionText !== (row.value.resolutionText || "")),
);
async function mutate(operation: () => Promise<void>) {
  if (mutating.value) return;
  mutating.value = true;
  try { await action(operation); } finally { mutating.value = false; }
}
function snapshot(value: unknown): Row {
  if (typeof value !== "string") return {};
  try {
    return JSON.parse(value) || {};
  } catch {
    return {};
  }
}
function evidenceUrl(uri: string) {
  return "/api/v1/files/" + uri.split("/").pop();
}
let viewRevision = 0, loadRevision = 0, recommendationRevision = 0;
function sameView(id: string, revision: number) {
  return String(route.params.id) === id && viewRevision === revision;
}
async function load() {
  const id = String(route.params.id), view = viewRevision, request = ++loadRevision;
  try {
    const next = await api("/cases/" + id, { quiet: true });
    if (!sameView(id, view) || request !== loadRevision) return false;
    row.value = next;
    Object.assign(form, {
      analysisText: next.analysisText || "",
      resolutionText: next.resolutionText || "",
      version: next.version,
    });
    void loadRecommendations();
    return true;
  } catch (error) {
    if (sameView(id, view) && request === loadRevision) throw error;
    return false;
  }
}
async function loadRecommendations() {
  const id = String(route.params.id), view = viewRevision, request = ++recommendationRevision;
  recommendationError.value = false;
  try {
    const next = await api(`/cases/${id}/recommendations`, { quiet: true });
    if (sameView(id, view) && request === recommendationRevision) recommendations.value = next;
  } catch {
    if (!sameView(id, view) || request !== recommendationRevision) return;
    recommendations.value = [];
    recommendationError.value = true;
  }
}
watch(() => route.params.id, () => {
  const id = String(route.params.id), view = ++viewRevision;
  row.value = null;
  recommendations.value = [];
  recommendationError.value = false;
  members.value = [];
  evidence.value = undefined;
  assignDialog.value = reviewDialog.value = false;
  assignee.value = "";
  Object.assign(form, { analysisText: "", resolutionText: "", version: "" });
  Object.assign(review, { approve: true, resolutionCode: "KNOWN_EXCEPTION", reason: "", evidenceResultId: "", attributionBatchId: "" });
  void action(async () => {
    busy.value = true;
    try {
      if (!await load() || !sameView(id, view)) return;
      if (session.can(row.value!.merchantId, ["OWNER", "FINANCE"])) {
        const next = await api(`/cases/${id}/assignees`, { quiet: true });
        if (sameView(id, view)) members.value = next;
      }
    } catch (error) {
      if (sameView(id, view)) throw error;
    } finally {
      if (sameView(id, view)) busy.value = false;
    }
  });
}, { immediate: true });
onBeforeUnmount(() => { viewRevision++; loadRevision++; recommendationRevision++; });
function currentId() {
  const id = String(route.params.id);
  if (!row.value || String(row.value.id) !== id) throw new Error("请等待当前工单加载完成");
  return id;
}
async function assign() {
  const id = currentId(), view = viewRevision;
  await post(`/cases/${id}/assign`, {
    assigneeId: assignee.value,
    version: row.value!.version,
  });
  if (!sameView(id, view)) return;
  assignDialog.value = false;
  await load();
}
async function save() {
  const id = currentId(), view = viewRevision;
  await patch(`/cases/${id}`, { ...form });
  if (!sameView(id, view)) return;
  await load();
  ElMessage.success("处理分析与结论已保存");
}
async function submit() {
  if (dirty.value) {
    ElMessage.warning("请先保存当前分析和结论，再提交复核");
    return;
  }
  const id = currentId(), view = viewRevision;
  await post(`/cases/${id}/submit`, {
    version: row.value!.version,
  });
  if (!sameView(id, view)) return;
  await load();
  ElMessage.success("工单已提交其他人员复核");
}
async function upload() {
  if (!evidence.value) return;
  if (dirty.value) {
    ElMessage.warning("请先保存当前分析和结论，再上传证据");
    return;
  }
  const b = new FormData();
  const id = currentId(), view = viewRevision;
  b.append("file", evidence.value);
  b.append("version", row.value!.version);
  await post(`/cases/${id}/evidence`, b);
  if (!sameView(id, view)) return;
  await load();
  ElMessage.success("证据文件已保存");
}
async function finish() {
  const id = currentId(), view = viewRevision;
  await post(`/cases/${id}/review`, {
    ...review,
    version: row.value!.version,
    resolutionCode: review.approve ? review.resolutionCode : "",
    evidenceResultId:
      review.approve && review.resolutionCode === "RECONCILED"
        ? review.evidenceResultId
        : "",
    attributionBatchId:
      review.approve && review.resolutionCode === "OWNERSHIP_CONFIRMED"
        ? review.attributionBatchId
        : "",
  });
  if (!sameView(id, view)) return;
  reviewDialog.value = false;
  await load();
  ElMessage.success("复核结果已记录");
}
</script>
<template>
  <div v-loading="busy" class="case-detail-page">
    <div class="page-heading detail-heading">
      <div class="detail-title">
        <el-button link :icon="ArrowLeft" @click="router.push('/cases')"
          >返回差错工作台</el-button
        >
        <h1>{{ row?.title || "工单详情" }}</h1>
        <p class="mono">{{ row?.caseNo }}</p>
      </div>
      <div v-if="row" class="heading-actions">
        <el-button
          v-if="
            session.can(row.merchantId, ['OWNER', 'FINANCE']) &&
            !['CLOSED', 'PENDING_REVIEW'].includes(row.status)
          "
          @click="
            assignDialog = true;
            assignee = row.assigneeId || '';
          "
          >分派任务</el-button
        ><el-button
          v-if="
            session.can(row.merchantId, ['OWNER', 'REVIEWER']) &&
            row.status === 'PENDING_REVIEW' &&
            row.submittedBy !== session.user?.id
          "
          type="primary"
          @click="reviewDialog = true"
          >独立复核</el-button
        >
      </div>
    </div>
    <section v-if="row" class="case-summary" aria-label="工单摘要">
      <div class="case-summary-status"><span><el-icon><Flag /></el-icon>处理状态</span><StatusTag :value="row.status" /></div>
      <div><span><el-icon><User /></el-icon>当前处理人</span><b>{{ row.assigneeName || "尚未分派" }}</b></div>
      <div><span><el-icon><Timer /></el-icon>处理期限</span><b>{{ time(row.dueAt) }}</b></div>
      <div><span>已超期天数</span><b :class="{ 'overdue-value': Number(row.overdueDays) > 0 }">{{ row.overdueDays }}<small>天</small></b></div>
    </section>
    <div v-if="row" class="detail-grid case-detail-grid">
      <div class="detail-stack">
        <section class="panel">
          <div class="panel-heading">
            <h2>差错与交易</h2>
            <StatusTag :value="row.classification" />
          </div>
          <p class="case-description">{{ row.description }}</p>
          <div class="case-amount-comparison">
            <div><span>本地金额</span><strong>{{ row.source.localAmountCent ? "¥ " + money(row.source.localAmountCent) : "—" }}</strong></div>
            <div><span>渠道金额</span><strong>{{ row.source.channelAmountCent ? "¥ " + money(row.source.channelAmountCent) : "—" }}</strong></div>
          </div>
          <div class="description-grid case-source-facts">
            <div>
              <label>商户 / 门店</label
              ><b
                >{{ row.merchantName || "平台待认领" }} /
                {{ row.source.storeName || "渠道待定位" }}</b
              >
            </div>
            <div>
              <label>关联订单</label
              ><el-button
                v-if="row.source.orderId"
                link
                type="primary"
                @click="router.push('/orders/' + row.source.orderId)"
                >{{ row.source.orderNo }} ↗</el-button
              ><span v-else class="muted">等待渠道查询定位</span>
            </div>
            <div>
              <label>支付 / 退款标识</label
              ><b class="mono">{{
                row.source.outTradeNo ||
                row.source.outRequestNo ||
                row.sourceEventKey
              }}</b>
            </div>
            <div>
              <label>结果批次</label><b class="mono">{{ row.batchNo }}</b>
            </div>
          </div>
          <div class="help-note">
            重新核对后，可选用新批次结果作为结案依据。
          </div>
        </section>
        <CaseDossier :case-id="String(route.params.id)" :version="String(row.version)" />
        <section class="panel case-analysis">
          <div class="panel-heading">
            <h2>处理分析与证据</h2>
            <span class="muted">{{
              mine ? "你是当前处理人" : "由已分派的处理人填写"
            }}</span>
          </div>
          <el-form label-position="top"
            ><el-form-item label="原因分析"
              ><el-input
                v-model="form.analysisText"
                type="textarea"
                :rows="4"
                placeholder="填写原因和核查结果"
                :disabled="
                  mutating || !session.writable ||
                  !mine ||
                  !['ASSIGNED', 'PROCESSING'].includes(row.status)
                " /></el-form-item
            ><el-form-item label="处理步骤与结论"
              ><el-input
                v-model="form.resolutionText"
                type="textarea"
                :rows="4"
                placeholder="填写处理方法与结论"
                :disabled="
                  mutating || !session.writable ||
                  !mine ||
                  !['ASSIGNED', 'PROCESSING'].includes(row.status)
                " /></el-form-item
            ><el-form-item label="证据附件"
              ><span v-if="!row.evidenceFileUri" class="muted evidence-empty">尚未上传证据</span><a
                v-if="row.evidenceFileUri"
                :href="evidenceUrl(row.evidenceFileUri)"
                ><el-button link type="primary">下载已保存证据 ↓</el-button></a
              ><template
                v-if="
                  session.writable &&
                  mine &&
                  ['ASSIGNED', 'PROCESSING'].includes(row.status)
                "
                ><input
                  type="file"
                  :disabled="mutating"
                  accept=".pdf,.png,.jpg,.jpeg,.txt,.xlsx,.xml"
                  @change="
                    (e: Event) =>
                      (evidence = (e.target as HTMLInputElement).files?.[0])
                  "
                /><el-button style="margin-top: 8px" :disabled="mutating || dirty || !evidence" @click="mutate(upload)"
                  >上传证据</el-button
                ></template
              ></el-form-item
            >
            <div
              v-if="
                session.writable &&
                mine &&
                ['ASSIGNED', 'PROCESSING'].includes(row.status)
              "
              class="row-actions"
              style="justify-content: flex-end"
            >
              <el-button :loading="mutating" @click="mutate(save)">保存处理记录</el-button
              ><el-button
                type="primary"
                :disabled="mutating || row.status !== 'PROCESSING' || dirty"
                @click="mutate(submit)"
                >提交独立复核</el-button
              >
            </div></el-form
          >
        </section>
        <details class="panel case-history">
          <summary>协作与审核历史<span>{{ row.history.length }} 条记录</span></summary>
          <el-timeline
            ><el-timeline-item
              v-for="h in row.history"
              :key="h.id"
              :timestamp="time(h.occurredAt)"
              placement="top"
              color="#325ddb"
              ><b>{{ h.actorName || "系统" }} · {{ h.action === 'CASE_WORKFLOW' ? '工单状态更新' : codeName(h.action) }}</b>
              <p>{{ h.summary }}</p>
              <el-button
                v-if="snapshot(h.afterJson).target_batch_id"
                link
                type="primary"
                @click="
                  router.push(
                    '/reconciliation?batchId=' +
                      snapshot(h.afterJson).target_batch_id,
                  )
                "
              >
                查看归属确认批次 #{{ snapshot(h.afterJson).target_batch_id }} ↗
              </el-button>
              <details v-if="h.afterJson" class="history-snapshot">
                <summary>查看当时的处理记录</summary>
                <div
                  v-for="[label, key] in [
                    ['变更前', 'beforeJson'],
                    ['变更后', 'afterJson'],
                  ]"
                  :key="key"
                  style="margin-top: 12px; white-space: pre-wrap"
                >
                  <b>{{ label }}</b>
                  <p>原因分析：{{ snapshot(h[key]).analysis_text || "—" }}</p>
                  <p>
                    处理与复核：{{ snapshot(h[key]).resolution_text || "—" }}
                  </p>
                  <a
                    v-if="snapshot(h[key]).evidence_file_uri"
                    :href="evidenceUrl(snapshot(h[key]).evidence_file_uri)"
                    >下载当时的证据</a
                  >
                </div>
              </details>
              <small class="muted"
                >请求 {{ h.requestId || "系统任务" }}</small
              ></el-timeline-item
            ></el-timeline
          >
        </details>
      </div>
      <aside class="detail-stack case-side">
        <section class="panel case-progress">
          <div class="panel-heading"><h2>复核与结案</h2></div>
          <dl class="progress-list">
            <div><dt>复核人</dt><dd>{{ row.reviewerName || "待复核" }}</dd></div>
            <div><dt>结案类型</dt><dd>{{ codeName(row.resolutionCode) || "—" }}</dd></div>
            <div><dt>结案时间</dt><dd>{{ time(row.closedAt) }}</dd></div>
          </dl>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>相似解决案例</h2>
            </div>
          </div>
          <div class="compact-list">
            <router-link
              v-for="c in recommendations"
              :key="c.id"
              :to="'/cases/' + c.id"
              class="case-card"
              ><div class="card-caption">
                <span>{{ c.recommendationReason }}</span
                ><span>↗</span>
              </div>
              <h3 style="margin-top: 10px">{{ c.title }}</h3>
              <StatusTag :value="c.classification" />
              <p>{{ c.resolutionText }}</p>
              <small class="muted"
                >结案 {{ time(c.closedAt) }}</small
              ></router-link
            >
          </div>
          <el-empty
            v-if="!recommendations.length && !recommendationError"
            description="暂无可见的已解决相似案例"
            :image-size="65"
          />
          <div v-if="recommendationError" class="help-note">
            案例推荐暂时不可用，可稍后重试。
            <el-button link type="primary" @click="loadRecommendations">重试</el-button>
          </div>
        </section>
      </aside>
    </div>
    <el-dialog v-model="assignDialog" title="分派差错任务" width="470"
      ><el-form label-position="top"
        ><el-form-item label="处理人"
          ><el-select v-model="assignee" filterable
            ><el-option
              v-for="m in members"
              :key="m.id"
              :label="m.displayName + ' · ' + m.roleCode"
              :value="m.userId" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button type="primary" :loading="mutating" @click="mutate(assign)"
          >确认分派</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="reviewDialog" title="独立复核" width="570"
      ><el-form label-position="top"
        ><el-form-item label="复核决定"
          ><el-radio-group v-model="review.approve"
            ><el-radio :value="true">通过并关闭</el-radio
            ><el-radio :value="false">退回继续处理</el-radio></el-radio-group
          ></el-form-item
        ><el-form-item v-if="review.approve" label="结论类别"
          ><el-select v-model="review.resolutionCode"
            ><el-option
              label="重新对账已匹配（需新结果证据）"
              value="RECONCILED" /><el-option
              label="输入账单无效"
              value="INVALID_INPUT" /><el-option
              label="核实为误报"
              value="FALSE_POSITIVE" /><el-option
              label="保留说明的已知例外"
              value="KNOWN_EXCEPTION" /><el-option
              v-if="!row?.merchantId"
              label="已确认商户归属（保留平台原记录）"
              value="OWNERSHIP_CONFIRMED" /></el-select></el-form-item
        ><el-form-item
          v-if="review.approve && review.resolutionCode === 'RECONCILED'"
          label="同一事件的新匹配结果"
          ><el-select
            v-model="review.evidenceResultId"
            placeholder="选择新批次中已匹配的结果"
            ><el-option
              v-for="r in row?.matchedEvidence || []"
              :key="r.id"
              :label="r.batchNo + ' / 结果 #' + r.id"
              :value="r.id" /></el-select></el-form-item
        ><el-form-item
          v-if="
            review.approve && review.resolutionCode === 'OWNERSHIP_CONFIRMED'
          "
          label="相同原账单行的商户对账批次"
        >
          <el-select
            v-model="review.attributionBatchId"
            placeholder="先重新识别归属并完成目标批次对账"
          >
            <el-option
              v-for="r in row?.attributionEvidence || []"
              :key="r.resultId"
              :label="
                r.merchantName +
                ' · ' +
                r.batchNo +
                ' · ' +
                codeName(r.classification)
              "
              :value="r.batchId"
            />
          </el-select>
          <p class="muted">
            确认归属后保留平台工单；商户侧差错继续在对应商户范围处理。
          </p> </el-form-item
        ><el-form-item label="复核意见与依据"
          ><el-input
            v-model="review.reason"
            type="textarea"
            :rows="4" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="reviewDialog = false">取消</el-button
        ><el-button type="primary" :loading="mutating" @click="mutate(finish)"
          >提交复核结论</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>

<style scoped>
.detail-title { min-width: 0; }
.detail-title h1 { margin-top: 16px; overflow-wrap: anywhere; line-height: 1.4; font-size: clamp(24px, 2.2vw, 32px); }
.detail-title > p { overflow-wrap: anywhere; }
.detail-heading { align-items: flex-start; }
.detail-heading .heading-actions { padding-top: 34px; flex-wrap: wrap; justify-content: flex-end; flex-shrink: 0; }
.case-summary { display: grid; grid-template-columns: 1fr 1fr 1.4fr .85fr; margin-bottom: 24px; border: 1px solid var(--line, #e5eaf2); border-radius: 12px; background: var(--surface, #fff); overflow: hidden; }
.case-summary > div { padding: 23px 24px; min-width: 0; border-right: 1px solid var(--line, #e5eaf2); }
.case-summary > div:last-child { border-right: 0; }
.case-summary-status { background: #11243c; }
.case-summary > div > span:first-child { display: flex; align-items: center; gap: 7px; color: var(--muted, #64748b); font-size: 12px; margin-bottom: 12px; }
.case-summary-status > span:first-child { color: #b7c9e4 !important; }
.case-summary > div > b { display: block; overflow-wrap: anywhere; font-size: 14px; font-weight: 600; line-height: 1.7; }
.case-summary small { margin-left: 6px; color: var(--muted, #64748b); font-size: 12px; font-weight: 400; }
.case-summary .overdue-value { color: #b16b14; }
.case-detail-page .panel-heading h2 { display: flex; align-items: center; gap: 9px; }
.case-detail-page .panel-heading h2 .el-icon { color: var(--brand, #325ddb); font-size: 19px; flex: none; }
.case-description { margin: 0; color: var(--muted, #64748b); font-size: 13px; line-height: 1.9; overflow-wrap: anywhere; white-space: pre-wrap; }
.case-amount-comparison { display: grid; grid-template-columns: 1fr 1fr; margin: 22px 0; border: 1px solid var(--line, #e5eaf2); border-radius: 9px; background: #f8faff; }
.case-amount-comparison > div { min-width: 0; padding: 19px 21px; }
.case-amount-comparison > div + div { border-left: 1px solid var(--line, #e5eaf2); background: #fcfbf8; border-radius: 0 8px 8px 0; }
.case-amount-comparison span { display: block; color: var(--muted, #64748b); font-size: 12px; }
.case-amount-comparison strong { display: block; margin-top: 9px; font-size: clamp(19px, 2vw, 27px); line-height: 1.4; font-variant-numeric: tabular-nums; font-weight: 600; overflow-wrap: anywhere; }
.case-source-facts { margin-top: 22px; }
.case-source-facts > div { min-width: 0; }
.case-source-facts :deep(.el-button > span) { white-space: normal; overflow-wrap: anywhere; text-align: left; }
.case-analysis :deep(.el-textarea__inner) { line-height: 1.8; resize: vertical; }
.evidence-empty { display: block; width: 100%; margin-bottom: 10px; font-size: 13px; }
.case-analysis input[type="file"] { display: block; width: 100%; max-width: 100%; padding: 12px; border: 1px dashed #bdcae0; border-radius: 8px; background: #f8faff; font-size: 12px; color: var(--muted, #64748b); }
.case-analysis input[type="file"]::file-selector-button { padding: 7px 10px; margin-right: 10px; border: 1px solid #d5dfef; border-radius: 5px; background: white; color: var(--ink, #172b4d); cursor: pointer; }
.case-analysis :deep(.el-form-item:last-of-type) { margin-bottom: 0; }
.case-analysis .row-actions { border-top: 1px solid var(--line, #e5eaf2); margin-top: 22px; padding-top: 20px; }
.case-history :deep(.el-timeline-item__content) { overflow-wrap: anywhere; }
.case-history :deep(.el-timeline-item__content > p) { margin-top: 7px; color: var(--muted, #64748b); }
.history-snapshot { margin: 12px 0; padding: 11px 13px; background: #f7f9fc; border: 1px solid var(--line, #e5eaf2); border-radius: 7px; font-size: 12px; }
.history-snapshot summary { cursor: pointer; font-size: 13px; color: #4b6084; line-height: 1.7; }
.history-snapshot summary:focus-visible { outline: 2px solid var(--brand, #325ddb); outline-offset: 3px; border-radius: 2px; }
.history-snapshot a { color: var(--brand, #325ddb); }
.progress-list { margin: 0; }
.progress-list > div { display: grid; grid-template-columns: 70px minmax(0, 1fr); gap: 14px; padding: 15px 0; border-bottom: 1px solid var(--line, #e5eaf2); font-size: 13px; line-height: 1.8; }
.progress-list > div:first-child { padding-top: 0; }
.progress-list > div:last-child { padding-bottom: 0; border-bottom: 0; }
.progress-list dt { color: var(--muted, #64748b); }
.progress-list dd { margin: 0; font-weight: 500; overflow-wrap: anywhere; }
.case-side .case-card { min-width: 0; }
.case-side .case-card h3 { overflow-wrap: anywhere; }
.case-side .card-caption { align-items: flex-start; line-height: 1.7; }
.case-side .card-caption span:first-child { min-width: 0; }
.case-detail-page :deep(.dossier .panel-heading) { flex-wrap: wrap; }
.case-detail-grid > .detail-stack:first-child { gap: 0; align-self: start; background: var(--surface, #fff); border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.case-detail-grid > .detail-stack:first-child > .panel { border: 0; border-radius: 0; border-bottom: 1px solid var(--line); box-shadow: none; padding: 30px; }
.case-detail-grid > .detail-stack:first-child > .panel:last-child { border-bottom: 0; }
.case-amount-comparison { background: transparent; border-radius: 0; border-width: 1px 0; margin: 25px 0; }
.case-amount-comparison > div { padding: 23px 0; }
.case-amount-comparison > div + div { background: none; border-radius: 0; padding-left: 26px; }
.case-amount-comparison strong { font-size: clamp(24px, 2.2vw, 32px); font-weight: 600; letter-spacing: -.5px; }
.case-side > .panel { background: transparent; border-color: transparent; box-shadow: none; padding: 22px 10px; }
.case-side .case-card { border-width: 0 0 1px; border-radius: 0; background: transparent; padding: 20px 0; }
.case-side .case-card:first-child { padding-top: 0; }
.case-side .case-card:hover { transform: none; background: transparent; box-shadow: none; }
.case-side .case-card h3 { color: var(--ink); font-size: 14px; }
.case-side .case-card:hover h3 { color: var(--brand); }
.case-history summary { cursor: pointer; font-size: 16px; font-weight: 600; }
.case-history summary span { margin-left: 12px; color: var(--muted); font-size: 12px; font-weight: 400; }
.case-history[open] summary { margin-bottom: 28px; }
.case-history summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 6px; border-radius: 3px; }
@media (max-width: 1200px) {
  .detail-heading { flex-wrap: wrap; }
  .detail-heading .heading-actions { padding-top: 0; }
  .case-summary > div { padding: 20px; }
}
@media (max-width: 900px) {
  .case-summary { grid-template-columns: 1fr 1fr; }
  .case-summary > div:nth-child(2) { border-right: 0; }
  .case-summary > div:nth-child(-n+2) { border-bottom: 1px solid var(--line, #e5eaf2); }
}
@media (max-width: 650px) {
  .detail-heading .heading-actions { width: 100%; justify-content: flex-start; }
  .detail-title h1 { font-size: 24px; }
  .case-summary { margin-bottom: 18px; }
  .case-summary > div { padding: 18px 16px; }
  .case-summary > div > b { font-size: 13px; }
  .case-amount-comparison > div { padding: 16px 13px; }
  .case-amount-comparison strong { font-size: 21px; }
  .case-detail-page .panel-heading { align-items: flex-start; flex-wrap: wrap; }
  .case-analysis .panel-heading > .muted { font-size: 12px; }
  .case-source-facts { grid-template-columns: 1fr; }
  .case-detail-grid > .detail-stack:first-child > .panel { padding: 22px 18px; }
  .case-side > .panel { padding: 18px; }
}
</style>
