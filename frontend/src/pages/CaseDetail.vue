<script setup lang="ts">
import { ref, reactive, watch, onBeforeUnmount, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
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
  <div v-loading="busy">
    <div class="page-heading">
      <div>
        <el-button link @click="router.push('/cases')"
          >← 返回差错工作台</el-button
        >
        <h1 style="margin-top: 12px">{{ row?.title || "工单详情" }}</h1>
        <p class="mono">{{ row?.caseNo }}</p>
      </div>
      <div v-if="row" class="heading-actions">
        <StatusTag :value="row.status" /><el-button
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
    <div v-if="row" class="detail-grid">
      <div class="detail-stack">
        <section class="panel">
          <div class="panel-heading">
            <h2>问题定位与关联来源</h2>
            <StatusTag :value="row.classification" />
          </div>
          <p style="font-size: 12px; color: #7c8d76">{{ row.description }}</p>
          <div class="filter-breadcrumb">
            差错 → 账单 / 流水 → 支付 / 退款 → 订单 → 门店 → 商户
          </div>
          <div class="description-grid" style="margin-top: 22px">
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
              <label>本地金额 / 渠道金额</label
              ><b
                >{{
                  row.source.localAmountCent
                    ? "¥ " + money(row.source.localAmountCent)
                    : "—"
                }}
                /
                {{
                  row.source.channelAmountCent
                    ? "¥ " + money(row.source.channelAmountCent)
                    : "—"
                }}</b
              >
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
            <div>
              <label>处理人与期限</label
              ><b
                >{{ row.assigneeName || "尚未分派" }} · {{ time(row.dueAt) }}</b
              >
            </div>
          </div>
          <div class="help-note">
            重新核对后，可选用新批次结果作为结案依据。
          </div>
        </section>
        <CaseDossier :case-id="String(route.params.id)" :version="String(row.version)" />
        <section class="panel">
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
              ><a
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
        <section class="panel">
          <div class="panel-heading"><h2>协作与审核历史</h2></div>
          <el-timeline
            ><el-timeline-item
              v-for="h in row.history"
              :key="h.id"
              :timestamp="time(h.occurredAt)"
              placement="top"
              color="#91ad83"
              ><b>{{ h.actorName || "系统" }} · {{ h.action }}</b>
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
              <details v-if="h.afterJson" style="margin: 10px 0">
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
        </section>
      </div>
      <aside class="detail-stack">
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>相似解决案例</h2>
              <p>与当前问题相关的已解决案例</p>
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
          <div class="help-note">查看相似问题的处理方法。</div>
        </section>
        <section class="panel">
          <h2>处理进度</h2>
          <div class="description-grid" style="margin-top: 22px">
            <div>
              <label>已超期天数</label><b>{{ row.overdueDays }}</b>
            </div>
            <div>
              <label>复核人</label><b>{{ row.reviewerName || "待复核" }}</b>
            </div>
            <div>
              <label>结案类型</label
              ><b>{{ codeName(row.resolutionCode) || "—" }}</b>
            </div>
            <div>
              <label>结案时间</label><b>{{ time(row.closedAt) }}</b>
            </div>
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
            placeholder="选择新批次 MATCHED 结果"
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
