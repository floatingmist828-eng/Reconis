<script setup lang="ts">
import { ref, reactive, onMounted, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { api, post, patch, time, money, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  row = ref<Row | null>(null),
  recommendations = ref<Row[]>([]),
  members = ref<Row[]>([]),
  busy = ref(false),
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
  });
const mine = computed(() => row.value?.assigneeId === session.user?.id);
async function load() {
  row.value = await api("/cases/" + route.params.id);
  Object.assign(form, {
    analysisText: row.value!.analysisText || "",
    resolutionText: row.value!.resolutionText || "",
    version: row.value!.version,
  });
  recommendations.value = await api(
    `/cases/${route.params.id}/recommendations`,
  );
}
onMounted(() =>
  action(async () => {
    busy.value = true;
    try {
      await load();
      if (
        row.value!.merchantId &&
        session.can(row.value!.merchantId, ["OWNER", "FINANCE", "REVIEWER"])
      )
        members.value = await api(
          `/merchants/${row.value!.merchantId}/members`,
        );
    } finally {
      busy.value = false;
    }
  }),
);
async function assign() {
  await post(`/cases/${route.params.id}/assign`, {
    assigneeId: assignee.value,
  });
  assignDialog.value = false;
  await load();
}
async function save() {
  await patch(`/cases/${route.params.id}`, form);
  await load();
  ElMessage.success("处理分析与结论已保存");
}
async function submit() {
  await post(`/cases/${route.params.id}/submit`);
  await load();
  ElMessage.success("工单已提交其他人员复核");
}
async function upload() {
  if (!evidence.value) return;
  const b = new FormData();
  b.append("file", evidence.value);
  await post(`/cases/${route.params.id}/evidence`, b);
  await load();
  ElMessage.success("证据文件已保存");
}
async function finish() {
  await post(`/cases/${route.params.id}/review`, review);
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
                >{{ row.merchantName }} /
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
                  !session.writable ||
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
                  !session.writable ||
                  !mine ||
                  !['ASSIGNED', 'PROCESSING'].includes(row.status)
                " /></el-form-item
            ><el-form-item label="证据附件"
              ><a
                v-if="row.evidenceFileUri"
                :href="'/api/v1/files/' + row.evidenceFileUri.split('/').pop()"
                ><el-button link type="primary">下载已保存证据 ↓</el-button></a
              ><template
                v-if="
                  session.writable &&
                  mine &&
                  ['ASSIGNED', 'PROCESSING'].includes(row.status)
                "
                ><input
                  type="file"
                  accept=".pdf,.png,.jpg,.jpeg,.txt,.xlsx,.xml"
                  @change="
                    (e: Event) =>
                      (evidence = (e.target as HTMLInputElement).files?.[0])
                  "
                /><el-button style="margin-top: 8px" @click="action(upload)"
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
              <el-button @click="action(save)">保存处理记录</el-button
              ><el-button
                type="primary"
                :disabled="row.status !== 'PROCESSING'"
                @click="action(submit)"
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
            v-if="!recommendations.length"
            description="暂无可见的已解决相似案例"
            :image-size="65"
          />
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
              <label>结案代码</label><b>{{ row.resolutionCode || "—" }}</b>
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
              v-for="m in members.filter(
                (m) =>
                  m.status === 'ACTIVE' &&
                  ['OWNER', 'FINANCE', 'CASHIER'].includes(m.roleCode),
              )"
              :key="m.id"
              :label="m.displayName + ' · ' + m.roleCode"
              :value="m.userId" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button type="primary" @click="action(assign)"
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
        ><el-form-item label="结论类别"
          ><el-select v-model="review.resolutionCode"
            ><el-option
              label="重新对账已匹配（需新结果证据）"
              value="RECONCILED" /><el-option
              label="输入账单无效"
              value="INVALID_INPUT" /><el-option
              label="核实为误报"
              value="FALSE_POSITIVE" /><el-option
              label="保留说明的已知例外"
              value="KNOWN_EXCEPTION" /></el-select></el-form-item
        ><el-form-item
          v-if="review.resolutionCode === 'RECONCILED'"
          label="同一事件的新匹配结果"
          ><el-select
            v-model="review.evidenceResultId"
            placeholder="选择新批次 MATCHED 结果"
            ><el-option
              v-for="r in row?.matchedEvidence || []"
              :key="r.id"
              :label="r.batchNo + ' / 结果 #' + r.id"
              :value="r.id" /></el-select></el-form-item
        ><el-form-item label="复核意见与依据"
          ><el-input
            v-model="review.reason"
            type="textarea"
            :rows="4" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="reviewDialog = false">取消</el-button
        ><el-button type="primary" @click="action(finish)"
          >提交复核结论</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
