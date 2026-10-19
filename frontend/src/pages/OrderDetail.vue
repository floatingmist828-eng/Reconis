<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
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
  refreshing = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;
async function load() {
  row.value = await api("/orders/" + route.params.id);
}
onMounted(() =>
  action(async () => {
    busy.value = true;
    try {
      await load();
      timer = setInterval(() => {
        if (
          row.value?.payments.some((p: Row) =>
            ["CREATED", "PENDING", "UNKNOWN"].includes(p.status),
          )
        )
          action(load);
      }, 10000);
    } finally {
      busy.value = false;
    }
  }),
);
onBeforeUnmount(() => clearInterval(timer));
async function command(path: string) {
  await post(`/orders/${route.params.id}/${path}`);
  await load();
}
async function createPayment() {
  pay.value = await post(`/orders/${route.params.id}/payments`, {
    clientRequestId: crypto.randomUUID(),
  });
  qr.value = pay.value!.qrCode
    ? await QRCode.toDataURL(pay.value!.qrCode, { width: 260, margin: 2 })
    : "";
  await load();
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
  refund.value = p;
  amount.value = money(p.refundableCent).replace(/,/g, "");
  reason.value = "";
}
async function submitRefund() {
  await post(`/payments/${refund.value!.id}/refunds`, {
    clientRequestId: crypto.randomUUID(),
    amountCent: toCent(amount.value),
    reason: reason.value,
  });
  refund.value = null;
  await load();
  ElMessage.success("退款申请已提交，待其他财务人员审批");
}
</script>
<template>
  <div v-loading="busy">
    <div class="page-heading">
      <div>
        <el-button link @click="router.push('/orders')"
          >← 返回订单列表</el-button
        >
        <h1 style="margin-top: 13px">{{ row?.subject || "订单详情" }}</h1>
        <p class="mono">{{ row?.orderNo }}</p>
      </div>
      <div v-if="row" class="heading-actions">
        <StatusTag :value="row.status" /><template v-if="session.writable"
          ><el-button v-if="row.status === 'DRAFT'" @click="edit = true"
            >编辑草稿</el-button
          ><el-button v-if="row.status === 'DRAFT'" @click="action(remove)"
            >删除</el-button
          ><el-button
            v-if="row.status === 'DRAFT'"
            type="primary"
            @click="action(() => command('submit'))"
            >提交收款</el-button
          ><el-button
            v-if="row.status === 'WAITING_PAYMENT'"
            type="primary"
            @click="action(createPayment)"
            >打开付款</el-button
          ><el-button
            v-if="['DRAFT', 'WAITING_PAYMENT'].includes(row.status)"
            @click="action(() => command('cancel'))"
            >取消订单</el-button
          ></template
        >
      </div>
    </div>
    <div v-if="row" class="detail-grid">
      <div class="detail-stack">
        <section class="panel">
          <div class="panel-heading">
            <h2>订单信息</h2>
            <span class="muted">收款项目</span>
          </div>
          <div class="description-grid">
            <div>
              <label>所属商户</label><b>{{ row.merchantName }}</b>
            </div>
            <div>
              <label>收款门店</label><b>{{ row.storeName }}</b>
            </div>
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
            <div>
              <label>备注</label><b>{{ row.remark || "—" }}</b>
            </div>
          </div>
          <div class="section-divider">收款明细</div>
          <el-table :data="row.items"
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
          <div class="balance-block">
            <small>应收合计</small
            ><strong>¥ {{ money(row.amountCent) }}</strong>
          </div>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <h2>支付与退款</h2>
            <span class="muted">支付记录</span>
          </div>
          <div
            v-for="p in row.payments"
            :key="p.id"
            class="case-card"
            style="cursor: default; margin-bottom: 13px"
          >
            <div class="card-caption">
              <b class="mono">{{ p.outTradeNo }}</b
              ><StatusTag :value="p.status" />
            </div>
            <p>
              渠道交易：{{ p.channelTradeNo || "待确认" }} ·
              {{ p.isPrimary ? "主支付" : "非主支付" }} · 可退 ¥
              {{ money(p.refundableCent) }}
            </p>
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
                v-if="p.status === 'SUCCEEDED'"
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
          /><el-table v-if="row.refunds.length" :data="row.refunds"
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
            <h2>对账追踪</h2>
            <el-button link @click="router.push('/reconciliation')"
              >进入对账 →</el-button
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
      <div class="detail-stack">
        <section class="panel">
          <div class="panel-heading"><h2>资金事实时间线</h2></div>
          <el-timeline v-if="row.entries.length"
            ><el-timeline-item
              v-for="e in row.entries"
              :key="e.id"
              :timestamp="time(e.channelOccurredAt)"
              placement="top"
              :color="e.entryType === 'RECEIPT' ? '#6e9d7d' : '#c4a36d'"
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
                >系统获知：{{ time(e.recordedAt) }}</small
              ></el-timeline-item
            ></el-timeline
          ><el-empty v-else description="暂无收退款记录" :image-size="60" />
          <div class="help-note">收退款按发生日期展示。</div>
        </section>
        <section class="panel">
          <div class="panel-heading"><h2>通知接收记录</h2></div>
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
        </section>
      </div>
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
          {{ pay?.message || "暂未获取二维码，可主动查询已有支付状态。" }}
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
        ><el-button type="primary" @click="action(submitRefund)"
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
