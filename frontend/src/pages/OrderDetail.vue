<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowLeft, Wallet, Shop } from "@element-plus/icons-vue";
import QRCode from "qrcode";
import { api, post, money, toCent, time, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import OrderForm from "../components/OrderForm.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  row = ref<Row | null>(null),
  busy = ref(false),
  pay = ref<Row | null>(null),
  qr = ref(""),
  edit = ref(false),
  refund = ref<Row | null>(null),
  amount = ref(""),
  reason = ref(""),
  refreshing = ref(false),
  paymentBusy = ref(false),
  refundBusy = ref(false),
  loadError = ref("");
let refundRequestId = "", loadingOrder = false, qrRevision = 0;
const canEditOrder = computed(() => row.value && session.can(row.value.merchantId, ["OWNER", "CASHIER", "FINANCE"]));
const canRequestRefund = computed(() => session.writable && row.value && (
  row.value.consumerId === session.user?.id || canEditOrder.value
));
const currentPayment = computed(() => pay.value && (row.value?.payments.find((p: Row) => p.id === pay.value?.id) || pay.value));
const refundSummary = computed(() => {
  const entries: Row[] = row.value?.entries || [];
  const received = entries.filter(e => e.entryType === "RECEIPT").reduce((n, e) => n + BigInt(e.amountCent), 0n);
  const refunded = entries.filter(e => e.entryType === "REFUND").reduce((n, e) => n + BigInt(e.amountCent), 0n);
  return { refunded, label: refunded > 0n ? (received > 0n && refunded >= received ? "已全额退款" : "已部分退款") : "" };
});
function paymentMessage(p: Row | null) {
  if (!p) return "";
  if (p.status === "SUCCEEDED") return "付款已确认，可关闭此窗口查看收款记录。";
  if (p.status === "CLOSED") return "该笔支付已关闭。";
  return p.lastResultMessage || p.message || (p.status === "FAILED"
    ? "本次付款未能发起，请确认后重新尝试。"
    : "暂未获取二维码，可查询这笔支付的最新状态。");
}
watch(currentPayment, async (p) => {
  const revision = ++qrRevision;
  qr.value = "";
  if (p?.qrCode && ["CREATED", "PENDING"].includes(p.status)) {
    const image = await QRCode.toDataURL(p.qrCode, { width: 260, margin: 2 });
    if (revision === qrRevision) qr.value = image;
  }
});
let timer: ReturnType<typeof setInterval> | undefined;
async function load(quiet = false) {
  if (loadingOrder) return;
  loadingOrder = true;
  try {
    row.value = await api("/orders/" + route.params.id, { quiet });
    loadError.value = "";
  } catch (error: any) {
    loadError.value = error.message || "订单加载失败，请重试";
    if (!quiet) throw error;
  } finally { loadingOrder = false; }
}
onMounted(() =>
  action(async () => {
    busy.value = true;
    try {
      await load();
      timer = setInterval(() => {
        if (
          !document.hidden && (row.value?.payments.some((p: Row) =>
            ["CREATED", "PENDING", "UNKNOWN"].includes(p.status),
          ) || row.value?.refunds.some((r: Row) => ["PENDING_REVIEW", "APPROVED", "PROCESSING", "UNKNOWN"].includes(r.status)))
        )
          void load(true);
      }, 10000);
    } finally {
      busy.value = false;
    }
  }),
);
onBeforeUnmount(() => { clearInterval(timer); qrRevision++; });
async function command(path: string) {
  await post(`/orders/${route.params.id}/${path}`);
  await load();
}
async function createPayment() {
  if (paymentBusy.value) return;
  paymentBusy.value = true;
  try {
    pay.value = await post(`/orders/${route.params.id}/payments`, { clientRequestId: crypto.randomUUID() });
    await load();
  } finally { paymentBusy.value = false; }
}
async function sync(p: Row) {
  refreshing.value = true;
  try {
    await post(`/payments/${p.id}/sync`);
    await load();
    ElMessage.success("查询已完成，状态以渠道确认结果为准");
  } finally {
    refreshing.value = false;
  }
}
async function simulate(mode: string) {
  await post(`/payments/${pay.value!.id}/demo`, { mode });
  await load();
  if (mode !== "NOTIFY")
    ElMessage.success("模拟渠道情形已保存，可主动查询或等待定时恢复");
}
async function remove() {
  await ElMessageBox.confirm("删除当前草稿及明细？", "删除订单");
  await api("/orders/" + route.params.id, { method: "DELETE" });
  router.push("/orders");
}
function openRefund(p: Row) {
  refundRequestId = crypto.randomUUID();
  refund.value = p;
  amount.value = money(p.refundableCent).replace(/,/g, "");
  reason.value = "";
}
async function submitRefund() {
  if (refundBusy.value) return;
  refundBusy.value = true;
  try {
  await post(`/payments/${refund.value!.id}/refunds`, {
    clientRequestId: refundRequestId,
    amountCent: toCent(amount.value),
    reason: reason.value,
  });
  refund.value = null;
  await load();
  ElMessage.success("退款申请已提交，待其他财务人员审批");
  } finally { refundBusy.value = false; }
}
</script>
<template>
  <div v-loading="busy" class="order-detail-page">
    <div class="page-heading detail-heading">
      <div class="detail-title">
        <el-button link :icon="ArrowLeft" @click="router.push('/orders')"
          >返回订单列表</el-button
        >
        <h1>{{ row?.subject || "订单详情" }}</h1>
        <p class="mono">{{ row?.orderNo }}</p>
      </div>
      <div v-if="row" class="heading-actions">
        <template v-if="session.writable"
          ><el-button v-if="canEditOrder && row.status === 'DRAFT'" @click="edit = true"
            >编辑草稿</el-button
          ><el-button v-if="canEditOrder && row.status === 'DRAFT'" @click="action(remove)"
            >删除</el-button
          ><el-button
            v-if="canEditOrder && row.status === 'DRAFT'"
            type="primary"
            @click="action(() => command('submit'))"
            >提交收款</el-button
          ><el-button
            v-if="row.status === 'WAITING_PAYMENT'"
            type="primary"
            :loading="paymentBusy"
            @click="action(createPayment)"
            >打开付款</el-button
          ><el-button
            v-if="canEditOrder && ['DRAFT', 'WAITING_PAYMENT'].includes(row.status)"
            @click="action(() => command('cancel'))"
            >取消订单</el-button
          ></template
        >
      </div>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" style="margin-bottom: 18px">
      <el-button link @click="action(() => load())">重新加载订单</el-button>
    </el-alert>
    <section v-if="row" class="order-summary" aria-label="订单摘要">
      <div class="order-total">
        <span class="summary-label"><el-icon><Wallet /></el-icon>应收合计</span>
        <strong><small>¥</small>{{ money(row.amountCent) }}</strong>
      </div>
      <div class="order-summary-facts">
        <div><span class="summary-label">订单状态</span><div class="summary-tags"><StatusTag :value="row.status" /><el-tag v-if="refundSummary.label" type="info">{{ refundSummary.label }}</el-tag></div></div>
        <div><span class="summary-label">所属商户</span><b>{{ row.merchantName }}</b></div>
        <div><span class="summary-label">收款门店</span><b><el-icon><Shop /></el-icon>{{ row.storeName }}</b></div>
      </div>
    </section>
    <div v-if="row" class="detail-grid order-detail-grid">
      <div class="detail-stack">
        <section class="panel">
          <div class="panel-heading">
            <h2>订单信息</h2>
            <span class="muted">{{ row.items.length }} 项收款明细</span>
          </div>
          <div class="description-grid">
            <div>
              <label>创建时间</label><b>{{ time(row.createdAt) }}</b>
            </div>
            <div>
              <label>消费者</label
              ><b>{{
                row.consumerId
                  ? "已绑定消费者 #" + row.consumerId
                  : "柜台匿名订单"
              }}</b>
            </div>
            <div class="detail-full">
              <label>备注</label><b>{{ row.remark || "—" }}</b>
            </div>
          </div>
          <div class="section-divider">收款明细</div>
          <el-table :data="row.items" class="order-items-desktop"
            ><el-table-column
              label="项目"
              prop="itemName"
              min-width="120"
            /><el-table-column
              label="数量"
              prop="quantity"
              width="80"
            /><el-table-column label="成交单价" width="110" align="right"
              ><template #default="{ row: item }"
                >¥ {{ money(item.unitPriceCent) }}</template
              ></el-table-column
            ><el-table-column label="小计" width="110" align="right"
              ><template #default="{ row: item }"
                >¥
                {{
                  money(
                    (
                      BigInt(item.quantity) * BigInt(item.unitPriceCent)
                    ).toString(),
                  )
                }}</template
              ></el-table-column
            ></el-table
          >
          <div class="order-items-mobile">
            <article v-for="(item, index) in row.items" :key="item.id || index" class="order-item-mobile">
              <div><b>{{ item.itemName }}</b><span>数量 {{ item.quantity }}</span></div>
              <footer><span>单价 ¥ {{ money(item.unitPriceCent) }}</span><strong>小计 ¥ {{ money((BigInt(item.quantity) * BigInt(item.unitPriceCent)).toString()) }}</strong></footer>
            </article>
          </div>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <h2>支付与退款</h2>
            <span class="muted">{{ row.payments.length }} 笔支付记录</span>
          </div>
          <div
            v-for="p in row.payments"
            :key="p.id"
            class="payment-record"
          >
            <div class="card-caption">
              <b class="mono">{{ p.outTradeNo }}</b
              ><StatusTag :value="p.status" />
            </div>
            <div class="payment-facts">
              <div class="payment-channel"><span>渠道交易</span><b class="mono">{{ p.channelTradeNo || "待确认" }}</b></div>
              <div><span>支付类型</span><b>{{ p.isPrimary ? "主支付" : "非主支付" }}</b></div>
              <div><span>可退金额</span><b class="amount">¥ {{ money(p.refundableCent) }}</b></div>
            </div>
            <el-alert v-if="p.lastResultMessage && ['FAILED', 'UNKNOWN', 'CREATED'].includes(p.status)"
              :title="paymentMessage(p)" :type="p.status === 'FAILED' ? 'error' : 'warning'" :closable="false" />
            <div
              v-if="session.writable"
              class="row-actions"
              style="margin-top: 13px"
            >
              <el-button
                size="small"
                :loading="refreshing"
                @click="action(() => sync(p))"
                >主动查询渠道</el-button
              ><el-button
                v-if="canRequestRefund && p.status === 'SUCCEEDED' && BigInt(p.refundableCent || '0') > 0n"
                size="small"
                type="primary"
                plain
                @click="openRefund(p)"
                >申请退款</el-button
              >
            </div>
          </div>
          <el-empty
            v-if="!row.payments.length"
            description="尚未发起支付"
            :image-size="60"
          /><div v-if="row.refunds.length" class="section-divider">退款记录</div><el-table v-if="row.refunds.length" :data="row.refunds"
            ><el-table-column
              label="退款请求"
              prop="outRequestNo"
              min-width="170" /><el-table-column label="金额" width="100"
              ><template #default="{ row: r }"
                >¥ {{ money(r.amountCent) }}</template
              ></el-table-column
            ><el-table-column label="状态" width="100"
              ><template #default="{ row: r }"
                ><StatusTag :value="r.status" /></template></el-table-column
          ></el-table>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <h2>对账记录</h2>
            <el-button link @click="router.push('/reconciliation')"
              >进入对账</el-button
            >
          </div>
          <el-table :data="row.results"
            ><el-table-column
              label="批次"
              prop="batchNo"
              min-width="180" /><el-table-column label="结果" width="120"
              ><template #default="{ row: r }"
                ><StatusTag
                  :value="r.classification" /></template></el-table-column
          ></el-table>
        </section>
      </div>
      <aside class="detail-stack order-side">
        <section class="panel">
          <div class="panel-heading"><h2>收退款时间线</h2></div>
          <el-timeline v-if="row.entries.length"
            ><el-timeline-item
              v-for="e in row.entries"
              :key="e.id"
              :timestamp="time(e.channelOccurredAt)"
              placement="top"
              :color="e.entryType === 'RECEIPT' ? '#325ddb' : '#8295b8'"
              ><b
                >{{ e.entryType === "RECEIPT" ? "确认收款" : "确认退款" }} ¥
                {{ money(e.amountCent) }}</b
              >
              <p class="muted">
                {{
                  e.entryType === "REFUND"
                    ? "关联原支付：" + e.outTradeNo
                    : e.eventKey
                }}
              </p>
              <small
                >记录时间：{{ time(e.recordedAt) }}</small
              ></el-timeline-item
            ></el-timeline
          ><el-empty v-else description="暂无收退款记录" :image-size="60" />
          <div class="help-note">收退款按发生日期展示。</div>
        </section>
        <details class="panel order-notifications">
          <summary>支付通知<span>{{ row.notifications.length }}</span></summary>
          <div v-for="n in row.notifications" :key="n.id" class="batch-mini">
            <div>
              <b>{{ n.handlingResult }}</b
              ><small>{{ time(n.receivedAt) }}</small>
            </div>
            <el-tag
              :type="n.signatureValid ? 'success' : 'danger'"
              size="small"
              >{{ n.signatureValid ? "可信" : "未通过" }}</el-tag
            >
          </div>
          <el-empty
            v-if="!row.notifications.length"
            description="暂无通知"
            :image-size="60"
          />
        </details>
      </aside>
    </div>
    <el-dialog
      :model-value="!!pay"
      title="收款与状态恢复"
      width="510"
      @close="pay = null"
      ><div class="qr-box">
        <h3>{{ row?.merchantName }} · {{ row?.storeName }}</h3>
        <strong>¥ {{ money(row?.amountCent) }}</strong
        ><img v-if="qr" :src="qr" alt="收款二维码" />
        <p v-else class="muted">
          {{ paymentMessage(currentPayment) }}
        </p>
        <p>
          <StatusTag
            :value="row?.payments.find((p: Row) => p.id === pay?.id)?.status"
          />
        </p>
      </div>
      <div v-if="session.integration.environment === 'DEMO'" class="help-note">
        DEMO 演示付款
      </div>
      <div
        v-if="session.integration.environment === 'DEMO'"
        class="row-actions"
      >
        <el-button @click="action(() => simulate('NOTIFY'))"
          >模拟支付通知</el-button
        ><el-button @click="action(() => simulate('LOST'))"
          >模拟通知丢失</el-button
        ><el-button @click="action(() => simulate('TIMEOUT'))"
          >模拟超时</el-button
        >
      </div>
      <template #footer
        ><el-button @click="pay = null">关闭</el-button
        ><el-button
          type="primary"
          :loading="refreshing"
          @click="action(() => sync(pay!))"
          >主动查询</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      :model-value="!!refund"
      title="申请部分 / 全额退款"
      width="480"
      @close="refund = null"
      ><div class="help-note">
        当前可退 ¥ {{ money(refund?.refundableCent) }}
      </div>
      <el-form label-position="top"
        ><el-form-item label="退款金额（元）"
          ><el-input v-model="amount" inputmode="decimal" /></el-form-item
        ><el-form-item label="申请原因"
          ><el-input
            v-model="reason"
            type="textarea"
            maxlength="1000" /></el-form-item></el-form
      ><template #footer
        ><el-button type="primary" :loading="refundBusy" @click="action(submitRefund)"
          >提交退款申请</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="edit" title="编辑订单草稿" width="740"
      ><OrderForm
        v-if="edit && row"
        :existing="row"
        @saved="
          () => {
            edit = false;
            action(load);
          }
        "
        @cancel="edit = false"
    /></el-dialog>
  </div>
</template>

<style scoped>
.detail-title { min-width: 0; }
.detail-title h1 { margin-top: 16px; overflow-wrap: anywhere; line-height: 1.4; font-size: clamp(24px, 2.2vw, 32px); }
.detail-title > p { overflow-wrap: anywhere; }
.detail-heading { align-items: flex-start; }
.detail-heading .heading-actions { padding-top: 34px; flex-wrap: wrap; justify-content: flex-end; flex-shrink: 0; }
.order-summary { display: grid; grid-template-columns: minmax(210px, .8fr) minmax(0, 2fr); margin-bottom: 24px; background: var(--surface, #fff); border: 1px solid var(--line, #e5eaf2); border-radius: 12px; overflow: hidden; }
.order-total { padding: 28px 30px; background: #11243c; border-right: 1px solid #11243c; min-width: 0; }
.summary-label { display: flex; align-items: center; gap: 7px; color: var(--muted, #64748b); font-size: 12px; }
.order-total .summary-label { color: #b7c9e4; }
.order-total strong { display: flex; align-items: baseline; flex-wrap: wrap; gap: 7px; margin-top: 14px; color: #fff; font-size: clamp(30px, 2.7vw, 40px); font-weight: 600; letter-spacing: -.8px; line-height: 1.2; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.order-total strong small { font-size: 18px; font-weight: 500; }
.order-summary-facts { display: grid; grid-template-columns: 1.1fr 1fr 1fr; align-items: center; gap: 22px; padding: 24px 28px; min-width: 0; }
.order-summary-facts > div { min-width: 0; }
.order-summary-facts b { display: flex; align-items: baseline; gap: 6px; margin-top: 10px; font-size: 14px; font-weight: 600; line-height: 1.6; overflow-wrap: anywhere; }
.order-summary-facts b .el-icon { flex: none; color: #7a8aa3; }
.summary-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.order-detail-page .panel-heading h2 { display: flex; align-items: center; gap: 9px; }
.order-detail-page .panel-heading h2 .el-icon { color: var(--brand, #325ddb); font-size: 19px; }
.detail-full { grid-column: 1/-1; }
.order-items-mobile { display: none; }
.payment-record { padding: 22px 0; border-bottom: 1px solid var(--line, #e5eaf2); }
.payment-record:first-of-type { padding-top: 0; }
.payment-record:last-of-type { padding-bottom: 0; border-bottom: 0; }
.payment-record .card-caption { align-items: flex-start; }
.payment-record .card-caption b { min-width: 0; overflow-wrap: anywhere; color: var(--ink, #172b4d); font-weight: 600; }
.payment-record .card-caption :deep(.el-tag) { flex: none; }
.payment-facts { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, .8fr) minmax(0, 1fr); gap: 14px; margin-top: 16px; }
.payment-facts > div { min-width: 0; }
.payment-facts span { display: block; color: var(--muted, #64748b); font-size: 12px; margin-bottom: 7px; }
.payment-facts b { display: block; font-size: 13px; font-weight: 500; overflow-wrap: anywhere; line-height: 1.6; }
.payment-facts .amount { text-align: left; font-weight: 600; }
.payment-record :deep(.el-alert) { margin-top: 14px; }
.order-side :deep(.el-timeline-item__content) { overflow-wrap: anywhere; }
.order-side :deep(.el-timeline-item__content > b) { color: var(--ink, #172b4d); font-size: 14px; }
.order-side :deep(.el-timeline-item__content > p) { margin-top: 8px; font-size: 12px; line-height: 1.7; }
.order-side :deep(.el-timeline-item__content > small) { display: block; margin-top: 7px; color: var(--muted, #64748b); }
.order-side .batch-mini { cursor: default; }
.order-side .batch-mini b { overflow-wrap: anywhere; }
.order-detail-page :deep(.el-table .cell) { overflow-wrap: anywhere; }
.order-detail-grid > .detail-stack:first-child { gap: 0; align-self: start; background: var(--surface, #fff); border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.order-detail-grid > .detail-stack:first-child > .panel { border: 0; border-radius: 0; border-bottom: 1px solid var(--line); box-shadow: none; padding: 30px; }
.order-detail-grid > .detail-stack:first-child > .panel:last-child { border-bottom: 0; }
.order-side > .panel { background: transparent; border-color: transparent; box-shadow: none; padding: 22px 10px; }
.order-side .help-note { padding: 0; border: 0; background: none; margin-top: 20px; }
.order-notifications { border-top: 1px solid var(--line) !important; }
.order-notifications summary { cursor: pointer; font-size: 15px; font-weight: 600; }
.order-notifications summary span { margin-left: 10px; padding: 2px 7px; border-radius: 4px; color: var(--muted); background: #e9edf5; font-size: 12px; font-weight: 500; }
.order-notifications[open] summary { margin-bottom: 20px; }
.order-notifications summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 6px; border-radius: 3px; }
@media (max-width: 1200px) {
  .order-summary-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px 22px; }
  .order-summary-facts > div:first-child { grid-column: 1/-1; }
  .detail-heading { flex-wrap: wrap; }
  .detail-heading .heading-actions { padding-top: 0; }
}
@media (max-width: 650px) {
  .order-items-desktop { display: none; }
  .order-items-mobile { display: block; }
  .order-item-mobile { padding: 16px 0; border-top: 1px solid var(--line, #e5eaf2); }
  .order-item-mobile > div, .order-item-mobile footer { display: flex; justify-content: space-between; align-items: baseline; gap: 14px; }
  .order-item-mobile b { min-width: 0; overflow-wrap: anywhere; font-size: 14px; font-weight: 600; }
  .order-item-mobile > div > span { flex: none; color: var(--muted, #64748b); font-size: 12px; }
  .order-item-mobile footer { margin-top: 12px; flex-wrap: wrap; font-size: 12px; color: var(--muted, #64748b); }
  .order-item-mobile footer strong { color: var(--ink, #172b4d); font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
  .order-summary { grid-template-columns: 1fr; margin-bottom: 18px; }
  .order-total { padding: 21px; border-right: 0; border-bottom: 1px solid #dfe7f8; }
  .order-total strong { font-size: 32px; }
  .order-summary-facts { padding: 20px; }
  .detail-heading .heading-actions { width: 100%; justify-content: flex-start; }
  .detail-title h1 { font-size: 24px; }
  .payment-record { padding: 20px 0; }
  .payment-facts { grid-template-columns: 1fr 1fr; }
  .payment-channel { grid-column: 1/-1; }
  .order-detail-page .panel-heading { align-items: flex-start; flex-wrap: wrap; }
  .order-detail-grid > .detail-stack:first-child > .panel { padding: 22px 18px; }
  .order-side > .panel { padding: 18px; }
}
</style>
