<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ArrowRight, Plus, Refresh, Wallet, Tickets, RefreshLeft, DocumentChecked, User, Shop, Calendar, Lock, Connection } from "@element-plus/icons-vue";
import { api, query, money, time, dateDays, action, type Row } from "../api";
import { useSession } from "../stores/session";
import Chart from "../components/Chart.vue";
import StatusTag from "../components/StatusTag.vue";
const router = useRouter(),
  session = useSession(),
  data = ref<Row>({
    summary: {},
    trend: [],
    recentBatches: [],
    recentCases: [],
  }),
  loading = ref(false),
  loaded = ref(false),
  loadError = ref(false),
  days = ref(30),
  merchantId = ref(""),
  merchants = ref<Row[]>([]),
  lastUpdated = ref("");
const todayLabel = new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "long", day: "numeric", weekday: "long" }).format(new Date());
let loadVersion = 0;
async function load() {
  const version = ++loadVersion;
  loading.value = true;
  loadError.value = false;
  try {
    const next = await api("/dashboard?"+query({start:dateDays(1-days.value),end:dateDays(),merchantId:merchantId.value}));
    if (version !== loadVersion) return;
    data.value = next;
    loaded.value = true;
    lastUpdated.value = new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit" }).format(new Date());
  } catch (error) {
    if (version === loadVersion) loadError.value = true;
    throw error;
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}
onMounted(() => action(async()=>{
  await load();
  if (!session.admin && !session.consumer) merchants.value=(await api('/merchants?status=ACTIVE&pageSize=100')).items;
}));
const option = computed(() => ({
  color: ["#325ddb", "#d89536"],
  tooltip: { trigger: "axis" },
  legend: { data: ["收款", "退款"], right: 2, top: 6 },
  grid: { left: 14, right: 16, top: 52, bottom: 12, containLabel: true },
  xAxis: {
    type: "category",
    data: data.value.trend?.map((r: Row) => r.day.slice(5)),
    boundaryGap: false,
    axisLine: { lineStyle: { color: "#e5eaf2" } },
    axisTick: { show: false },
  },
  yAxis: {
    type: "value",
    name: "元",
    splitLine: { lineStyle: { color: "#edf0f5", type: "dashed" } },
    axisLabel: { color: "#64748b", fontSize: 12 },
  },
  series: [
    {
      name: "收款",
      type: "line",
      smooth: true,
      showSymbol: false,
      symbolSize: 5,
      data: data.value.trend?.map((r: Row) => Number(r.receiptCent) / 100),
      areaStyle: { color: "#325ddb", opacity: 0.045 },
    },
    {
      name: "退款",
      type: "line",
      smooth: true,
      showSymbol: false,
      symbolSize: 4,
      data: data.value.trend?.map((r: Row) => Number(r.refundCent) / 100),
    },
  ],
}));
</script>
<template>
  <div class="dashboard" v-loading="loading && !loaded" :aria-busy="loading">
    <div class="page-heading dashboard-heading">
      <div>
        <div class="workspace-date"><el-icon><Calendar /></el-icon>{{ todayLabel }}</div>
        <h1>{{ session.consumer ? '我的校园消费' : session.admin ? '管理工作台' : '经营概览' }}</h1>
      </div>
      <div class="heading-actions">
        <el-button :icon="Refresh" :loading="loading" aria-label="刷新工作台" @click="action(load)" />
        <el-button v-if="session.canAny(['OWNER', 'CASHIER', 'FINANCE'])" type="primary" :icon="Plus" @click="router.push('/orders?create=true')">新建收款</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" title="数据未能更新" type="error" :closable="false" show-icon class="dashboard-error"><span>请检查连接后</span><el-button link type="primary" @click="action(load)">重新加载</el-button></el-alert>
    <template v-if="data.admin">
      <div class="approval-grid">
        <router-link to="/users" class="approval-card">
          <span class="approval-icon"><el-icon><User /></el-icon></span>
          <div><span>待审核账号</span><strong>{{ data.pendingUsers || '0' }}</strong><small>查看注册申请</small></div>
          <el-icon class="approval-arrow"><ArrowRight /></el-icon>
        </router-link>
        <router-link to="/merchants" class="approval-card">
          <span class="approval-icon"><el-icon><Shop /></el-icon></span>
          <div><span>待审核商户资料</span><strong>{{ data.pendingMerchants || '0' }}</strong><small>新商户与资料变更</small></div>
          <el-icon class="approval-arrow"><ArrowRight /></el-icon>
        </router-link>
      </div>
      <section class="panel admin-navigation">
        <div class="panel-heading"><h2>工作空间管理</h2></div>
        <div class="admin-links">
          <router-link to="/merchants"><span class="admin-link-icon"><el-icon><Shop /></el-icon></span><div><b>商户与门店</b><small>查看入驻商户与经营资料</small></div><el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/audit"><span class="admin-link-icon"><el-icon><Lock /></el-icon></span><div><b>审计中心</b><small>追溯访问与关键操作</small></div><el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/integration"><span class="admin-link-icon"><el-icon><Connection /></el-icon></span><div><b>支付接入</b><small>查看渠道配置与连接状态</small></div><el-icon><ArrowRight /></el-icon></router-link>
        </div>
      </section>
    </template>

    <template v-else-if="data.consumer">
      <div class="consumer-overview">
        <router-link to="/orders" class="net-summary">
          <span class="summary-label">全部订单<el-icon><Tickets /></el-icon></span>
          <strong>{{ data.summary?.orderCount || 0 }}<em>笔</em></strong>
          <small>查看消费明细<el-icon><ArrowRight /></el-icon></small>
        </router-link>
        <div class="consumer-states">
          <router-link to="/orders?status=WAITING_PAYMENT"><span class="state-icon waiting"><el-icon><Wallet /></el-icon></span><div><span>待付款</span><small>继续付款</small></div><strong>{{ data.summary?.waitingCount || 0 }}</strong><el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/orders?status=PAID"><span class="state-icon paid"><el-icon><DocumentChecked /></el-icon></span><div><span>已支付</span><small>查看订单</small></div><strong>{{ data.summary?.paidCount || 0 }}</strong><el-icon><ArrowRight /></el-icon></router-link>
          <router-link to="/refunds"><span class="state-icon refund"><el-icon><RefreshLeft /></el-icon></span><div><span>退款处理中</span><small>查看退款进度</small></div><strong>{{ data.pendingRefunds || 0 }}</strong><el-icon><ArrowRight /></el-icon></router-link>
        </div>
      </div>
      <section class="panel">
        <div class="panel-heading"><h2>最近订单</h2><el-button link @click="router.push('/orders')">全部订单<el-icon><ArrowRight /></el-icon></el-button></div>
        <el-table :data="data.recentOrders" class="clickable dashboard-desktop-table" @row-click="(r:Row)=>router.push('/orders/'+r.id)">
          <el-table-column label="订单" prop="subject" min-width="190" show-overflow-tooltip />
          <el-table-column label="商户" prop="merchantName" min-width="130" show-overflow-tooltip />
          <el-table-column label="金额" align="right" width="125"><template #default="{row}">¥ {{money(row.amountCent)}}</template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{row}"><StatusTag :value="row.status"/></template></el-table-column>
          <el-table-column label="下单时间" width="175"><template #default="{row}">{{time(row.createdAt)}}</template></el-table-column>
        </el-table>
        <div class="dashboard-mobile-list">
          <router-link v-for="order in data.recentOrders" :key="order.id" :to="'/orders/'+order.id" class="recent-order">
            <div><b>{{order.subject}}</b><StatusTag :value="order.status"/></div>
            <p>{{order.merchantName}}</p>
            <footer><small>{{time(order.createdAt)}}</small><strong>¥ {{money(order.amountCent)}}</strong></footer>
          </router-link>
          <el-empty v-if="!data.recentOrders?.length" description="暂无订单" :image-size="70"/>
        </div>
      </section>
    </template>

    <template v-else>
      <div class="overview-scope">
        <span class="scope-period">{{ dateDays(1-days) }}<span>至</span>{{ dateDays() }}</span>
        <div class="scope-controls">
          <el-select v-model="merchantId" placeholder="全部商户" clearable class="overview-merchant" aria-label="选择统计商户" @change="action(load)"><el-option v-for="m in merchants" :key="m.id" :label="m.name" :value="m.id"/></el-select>
          <el-radio-group v-model="days" class="overview-period" aria-label="统计周期" @change="action(load)"><el-radio-button v-for="n in [7,30,90]" :key="n" :value="n">近{{n}}日</el-radio-button></el-radio-group>
        </div>
      </div>
      <section class="funds-ledger" aria-label="资金摘要" :class="{ updating: loading }">
        <router-link to="/analytics" class="ledger-net"><span>净收款<el-icon><ArrowRight /></el-icon></span><strong><em>¥</em>{{ money(data.summary?.netCent) }}</strong><small>成功收款 − 成功退款</small></router-link>
        <div class="ledger-breakdown">
          <router-link to="/analytics"><span><i class="receipt-dot"></i>成功收款</span><strong><em>¥</em>{{ money(data.summary?.receiptCent) }}</strong></router-link>
          <router-link to="/refunds"><span><i class="refund-dot"></i>成功退款</span><strong><em>¥</em>{{ money(data.summary?.refundCent) }}</strong></router-link>
          <p>按资金发生日期统计，金额单位：元</p>
        </div>
        <span class="ledger-stamp" role="status">{{ loading ? '正在更新…' : lastUpdated ? lastUpdated + ' 已更新' : '等待数据' }}</span>
      </section>
      <div class="finance-workspace">
        <section class="panel financial-overview">
          <div class="trend-heading"><h2>收退款趋势</h2><el-button link type="primary" @click="router.push('/analytics')">查看分析<el-icon><ArrowRight /></el-icon></el-button></div>
          <Chart v-if="data.trend?.length" :option="option" :height="375" />
          <el-empty v-else description="所选期间暂无已确认交易" :image-size="85" />
        </section>
        <section class="dashboard-tasks">
          <div class="panel-heading"><h2>待办事项</h2><span class="task-heading-icon"><el-icon><DocumentChecked /></el-icon></span></div>
          <router-link to="/cases" class="case-summary">
            <div><span>待处理差错</span><strong>{{ data.openCases || '0' }}<small>件</small></strong></div>
            <span class="case-entry">进入工作台<el-icon><ArrowRight /></el-icon></span>
          </router-link>
          <div class="task-list">
            <router-link to="/refunds?status=PENDING_REVIEW" class="task-row"><span class="task-icon refund"><el-icon><RefreshLeft /></el-icon></span><span class="task-copy"><b>退款待审批</b></span><strong>{{data.pendingRefunds||0}}</strong><el-icon class="task-arrow"><ArrowRight /></el-icon></router-link>
            <router-link to="/cases?status=PENDING_REVIEW" class="task-row"><span class="task-icon review"><el-icon><DocumentChecked /></el-icon></span><span class="task-copy"><b>工单待复核</b></span><strong>{{data.pendingReviews||0}}</strong><el-icon class="task-arrow"><ArrowRight /></el-icon></router-link>
            <router-link to="/orders?status=WAITING_PAYMENT" class="task-row"><span class="task-icon payment"><el-icon><Wallet /></el-icon></span><span class="task-copy"><b>支付待确认</b></span><strong>{{data.pendingPayments||0}}</strong><el-icon class="task-arrow"><ArrowRight /></el-icon></router-link>
          </div>
          <div class="queue-footer"><div class="overdue-strip"><span>逾期未结工单</span><strong>{{data.overdueCases||0}} <small>件</small></strong></div><el-button class="task-import" :icon="DocumentChecked" @click="router.push('/reconciliation')">导入账单并核对</el-button></div>
        </section>
      </div>
      <div class="overview-bottom">
        <section class="panel">
          <div class="panel-heading"><h2>待处理差错</h2><el-button link @click="router.push('/cases')">全部工单<el-icon><ArrowRight /></el-icon></el-button></div>
          <el-table :data="data.recentCases" @row-click="(r: Row) => router.push('/cases/' + r.id)" class="clickable dashboard-desktop-table">
            <el-table-column label="问题描述" prop="title" min-width="180" show-overflow-tooltip />
            <el-table-column label="所属商户" prop="merchantName" min-width="110" show-overflow-tooltip />
            <el-table-column label="处理状态" width="110"><template #default="{ row }"><StatusTag :value="row.status" /></template></el-table-column>
          </el-table>
          <div class="dashboard-mobile-list">
            <router-link v-for="item in data.recentCases" :key="item.id" :to="'/cases/'+item.id" class="recent-case"><div><b>{{item.title}}</b><small>{{item.merchantName}}</small></div><StatusTag :value="item.status"/></router-link>
            <el-empty v-if="!data.recentCases?.length" description="暂无待关注的差错" :image-size="70"/>
          </div>
        </section>
        <section class="panel">
          <div class="panel-heading"><h2>最近对账</h2><el-button link @click="router.push('/reconciliation')">查看全部<el-icon><ArrowRight /></el-icon></el-button></div>
          <router-link v-for="b in data.recentBatches" :key="b.id" class="batch-mini" :to="'/reconciliation?batchId=' + b.id">
            <span class="batch-icon"><el-icon><DocumentChecked /></el-icon></span><div><b>{{ b.merchantName }}</b><small>{{ time(b.createdAt) }}</small></div><StatusTag :value="b.status" />
          </router-link>
          <el-empty v-if="!data.recentBatches?.length" description="尚无对账批次" :image-size="60" />
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.dashboard { min-width: 0; }
.dashboard-heading { margin-bottom: 26px; align-items: center; }
.workspace-date { display: flex; align-items: center; gap: 7px; font-size: 12px; color: #64748b; margin-bottom: 10px; }
.dashboard-heading h1 { font-size: 30px; letter-spacing: -.8px; }
.overview-scope { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin: 0 0 20px; padding-top: 2px; }
.scope-period { display: flex; align-items: center; gap: 10px; color: #52647e; font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.scope-period > span { color: #64748b; }
.scope-controls { display: flex; align-items: center; gap: 12px; min-width: 0; }
.overview-merchant { width: 166px; }
.overview-period { width: auto; flex: none; padding: 4px; border: 1px solid #e0e6f0; border-radius: 10px; background: #eaf0f8; }
.overview-period :deep(.el-radio-button__inner) { border: 0; background: transparent; box-shadow: none; color: #5c6d86; font-size: 12px; padding: 8px 13px; border-radius: 6px !important; transition: background .18s, color .18s, box-shadow .18s; }
.overview-period :deep(.el-radio-button.is-active .el-radio-button__inner) { color: #325ddb; background: #fff; box-shadow: 0 2px 5px #233e6a12; }
.overview-period :deep(.el-radio-button__original-radio:focus-visible + .el-radio-button__inner) { outline: 2px solid #325ddb; outline-offset: 2px; }
.finance-workspace { display: grid; grid-template-columns: minmax(0, 1.9fr) minmax(300px, 1fr); gap: 24px; margin-bottom: 24px; }
.financial-overview { padding: 26px; }
.dashboard .panel-heading { margin-bottom: 22px; }
.dashboard .panel-heading h2 { font-size: 17px; }
.dashboard .panel-heading p { font-size: 12px; margin-top: 6px; }
.updated-time { color: #64748b; font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.financial-summary { display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr); gap: 23px; align-items: center; }
.net-summary { display: flex; flex-direction: column; justify-content: space-between; min-width: 0; min-height: 172px; padding: 22px 23px; border-radius: 12px; background: #325ddb; color: #fff; transition: background .2s, box-shadow .2s; }
.net-summary:hover { background: #294fc2; box-shadow: 0 5px 16px #325ddb25; }
.net-summary:active { background: #2447ad; }
.summary-label { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #edf1ff; font-size: 13px; font-weight: 500; line-height: 1.5; }
.summary-label .el-icon { font-size: 20px; flex: none; }
.net-summary strong { display: block; font-size: clamp(27px, 2.4vw, 36px); line-height: 1.3; font-weight: 600; letter-spacing: -.9px; font-variant-numeric: tabular-nums; margin: 17px 0 14px; overflow-wrap: anywhere; }
.net-summary em { font-style: normal; font-size: .45em; font-weight: 400; margin-inline: 4px; letter-spacing: 0; }
.net-summary small { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; color: #e5ebff; line-height: 1.7; }
.net-summary small .el-icon { transition: transform .18s; }
.net-summary:hover small .el-icon { transform: translateX(3px); }
.receipt-breakdown { min-width: 0; }
.receipt-stat { position: relative; display: flex; flex-direction: column; align-items: flex-start; padding: 10px 5px 12px; border-bottom: 1px solid #edf0f5; border-radius: 5px 5px 0 0; transition: background .18s; min-width: 0; }
.receipt-stat > span { display: flex; gap: 8px; align-items: center; color: #64748b; font-size: 12px; }
.receipt-stat i { height: 6px; width: 6px; border-radius: 50%; flex: none; }
.receipt-dot { background: #325ddb; }
.refund-dot { background: #d89536; }
.receipt-stat strong { font-size: clamp(23px, 1.8vw, 29px); font-weight: 600; line-height: 1.3; margin-top: 7px; letter-spacing: -.5px; color: #172b4d; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; max-width: calc(100% - 20px); }
.receipt-stat em { font-size: 13px; margin-right: 4px; font-style: normal; font-weight: 400; }
.receipt-stat > .el-icon { position: absolute; right: 6px; top: 28px; color: #8b9ab0; font-size: 12px; transition: transform .18s, color .18s; }
.receipt-stat:hover { background: #f7f9fd; }
.receipt-stat:hover > .el-icon { transform: translateX(2px); color: #325ddb; }
.receipt-breakdown > small { display: block; color: #64748b; font-size: 12px; line-height: 1.6; margin-top: 10px; }
.trend-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 18px; padding-top: 0; }
.trend-heading h2 { font-weight: 600; font-size: 17px; }
.trend-heading .el-button { font-size: 12px; }
.dashboard-tasks { display: flex; flex-direction: column; padding: 24px 0 10px 24px; border-left: 1px solid #dce3ef; }
.task-heading-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 9px; color: #6e7d98; background: #f4f6fa; font-size: 19px; flex: none; }
.case-summary { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; background: #f3f6fc; padding: 15px 18px; border: 1px solid #e1e8f4; border-radius: 10px; transition: background .18s, border-color .18s; }
.case-summary > div > span { display: block; font-size: 12px; color: #5a6a83; }
.case-summary strong { display: block; margin-top: 10px; color: #172b4d; font-size: 34px; font-variant-numeric: tabular-nums; line-height: 1; font-weight: 600; }
.case-summary strong small { font-size: 12px; color: #64748b; font-weight: 400; margin-left: 7px; }
.case-entry { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #325ddb; flex: none; padding-bottom: 3px; }
.case-entry .el-icon { transition: transform .18s; }
.case-summary:hover { background: #edf2ff; border-color: #c7d5f7; }
.case-summary:hover .case-entry .el-icon { transform: translateX(3px); }
.task-list { margin-top: 12px; }
.task-row { display: flex; align-items: center; gap: 11px; min-height: 61px; margin-inline: -8px; padding: 16px 8px; border-bottom: 1px solid #edf0f5; border-radius: 6px 6px 0 0; transition: background .18s; }
.task-icon { width: 34px; height: 34px; border-radius: 9px; display: grid; place-items: center; font-size: 17px; flex: none; }
.task-icon.refund { background: #fff5e7; color: #ad7021; }
.task-icon.review { background: #eef2fe; color: #4866b4; }
.task-icon.payment { background: #eaf5f3; color: #26796c; }
.task-copy { min-width: 0; flex: 1; }
.task-copy b { display: block; color: #263753; font-size: 13px; font-weight: 500; }
.task-copy small { display: block; margin-top: 4px; color: #64748b; font-size: 12px; line-height: 1.5; }
.task-row strong { color: #263753; font-size: 22px; font-weight: 600; font-variant-numeric: tabular-nums; }
.task-arrow { color: #8d9ab0; font-size: 12px; transition: transform .18s; }
.task-row:hover { background: #f8faff; }
.task-row:hover .task-copy b { color: #325ddb; }
.task-row:hover .task-arrow { color: #325ddb; transform: translateX(2px); }
.queue-footer { margin-top: auto; padding-top: 12px; }
.dashboard .overdue-strip { padding: 15px 0 18px; color: #64748b; }
.dashboard .overdue-strip strong { font-size: 16px; color: #a96716; }
.task-import { width: 100%; min-height: 40px; color: #325ddb; border-color: #d7e0f8; background: #f7f9ff; }
.dashboard .overview-bottom { grid-template-columns: minmax(0, 1.35fr) minmax(300px, 1fr); gap: 24px; }
.dashboard .batch-mini { gap: 12px; min-height: 74px; padding: 16px 7px; margin-inline: -7px; border-top-color: #edf0f5; border-radius: 6px; transition: background .18s; }
.dashboard .batch-mini:hover { background: #f7f9fe; }
.dashboard .batch-mini > div { min-width: 0; }
.dashboard .batch-mini b { color: #263753; overflow-wrap: anywhere; }
.dashboard .batch-mini small { color: #64748b; font-size: 12px; overflow-wrap: anywhere; }
.dashboard .batch-icon { width: 36px; height: 40px; border-radius: 8px; color: #5a73aa; background: #f0f4fc; font-size: 20px; flex: none; }
.dashboard .batch-mini :deep(.el-tag) { flex: none; }
.approval-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; margin-bottom: 24px; }
.approval-card { display: flex; align-items: center; gap: 22px; padding: 32px; background: #fff; border: 1px solid #e5eaf2; border-radius: 14px; transition: border-color .18s, box-shadow .18s; }
.approval-icon { display: grid; place-items: center; width: 58px; height: 58px; border-radius: 14px; color: #325ddb; background: #eef2ff; font-size: 26px; flex: none; }
.approval-card > div { flex: 1; min-width: 0; }
.approval-card span:not(.approval-icon) { font-size: 14px; color: #64748b; }
.approval-card strong { display: block; font-size: 38px; margin: 10px 0 8px; color: #172b4d; font-weight: 600; }
.approval-card small { color: #64748b; font-size: 12px; }
.approval-arrow { color: #64748b; transition: transform .18s; }
.approval-card:hover { border-color: #b4c6ed; box-shadow: 0 4px 16px #263d6c08; }
.approval-card:hover .approval-arrow { transform: translateX(3px); color: #325ddb; }
.admin-navigation { padding: 28px; }
.admin-links { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.admin-links > a { display: flex; align-items: center; gap: 12px; padding: 20px 16px; border-left: 1px solid #edf0f5; transition: background .18s; }
.admin-links > a:first-child { border-left: 0; }
.admin-links > a:hover { background: #f7f9fe; }
.admin-link-icon { color: #5774b7; font-size: 22px; flex: none; }
.admin-links > a > div { flex: 1; min-width: 0; }
.admin-links b { display: block; font-size: 14px; font-weight: 500; color: #172b4d; }
.admin-links small { display: block; font-size: 12px; color: #64748b; margin-top: 6px; line-height: 1.7; }
.admin-links > a > .el-icon { font-size: 12px; color: #8a9ab3; }
.consumer-overview { display: grid; grid-template-columns: minmax(250px, 1fr) minmax(0, 2fr); gap: 24px; margin-bottom: 24px; }
.consumer-overview .net-summary { padding: 27px; }
.consumer-overview .net-summary strong { font-size: 44px; }
.consumer-states { border: 1px solid #e5eaf2; border-radius: 12px; background: white; padding: 7px 24px; }
.consumer-states > a { display: flex; align-items: center; gap: 15px; padding: 16px 7px; margin-inline: -7px; border-bottom: 1px solid #edf0f5; border-radius: 6px; transition: background .18s; }
.consumer-states > a:last-child { border-bottom: 0; }
.consumer-states > a:hover { background: #f7f9fe; }
.state-icon { width: 35px; height: 35px; display: grid; place-items: center; background: #f0f4fc; color: #5a73aa; font-size: 18px; border-radius: 10px; flex: none; }
.state-icon.paid { background: #eaf5f1; color: #37806b; }
.state-icon.refund { background: #fff5e9; color: #aa7227; }
.consumer-states > a > div { flex: 1; min-width: 0; }
.consumer-states > a > div > span { color: #263753; font-size: 13px; }
.consumer-states small { display: block; font-size: 12px; color: #64748b; margin-top: 5px; }
.consumer-states strong { font-size: 25px; color: #172b4d; font-weight: 600; font-variant-numeric: tabular-nums; }
.consumer-states > a > .el-icon { color: #8b9ab0; font-size: 12px; }
.dashboard-mobile-list { display: none; }
@media (max-width: 1200px) {
  .finance-workspace { grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr); gap: 18px; }
  .financial-overview { padding: 22px; }
  .financial-summary { grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); gap: 17px; }
  .net-summary { padding: 21px 18px; }
  .net-summary strong { font-size: 29px; }
  .receipt-stat strong { font-size: 23px; }
  .dashboard-tasks { padding: 24px 0 10px 20px; }
  .dashboard .overview-bottom { gap: 18px; }
  .admin-links { grid-template-columns: 1fr; }
  .admin-links > a { border-left: 0; border-top: 1px solid #edf0f5; }
}
@media (max-width: 1050px) {
  .finance-workspace, .dashboard .overview-bottom { grid-template-columns: minmax(0, 1fr); }
  .dashboard-tasks { padding: 8px 0 0; border: 0; }
  .financial-summary { grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr); gap: 24px; }
  .net-summary strong { font-size: 34px; }
  .receipt-stat strong { font-size: 28px; }
  .overview-scope { flex-wrap: wrap; }
  .consumer-overview { grid-template-columns: minmax(230px, 1fr) minmax(0, 1.4fr); gap: 18px; }
}
@media (max-width: 650px) {
  .dashboard-heading { flex-direction: row; flex-wrap: wrap; align-items: flex-start; gap: 18px; margin-bottom: 20px; }
  .dashboard-heading h1 { font-size: 27px; }
  .dashboard-heading .heading-actions { width: auto; }
  .workspace-date { font-size: 12px; margin-bottom: 8px; }
  .overview-scope { gap: 12px; }
  .scope-period { font-size: 12px; }
  .scope-controls { width: 100%; gap: 8px; flex-wrap: wrap; }
  .overview-merchant { width: 140px; flex: 1; min-width: 120px; }
  .overview-period { max-width: 100%; padding: 3px; }
  .overview-period :deep(.el-radio-button__inner) { padding: 8px 10px; }
  .financial-overview { padding: 22px 18px; }
  .dashboard-tasks { padding: 8px 0 0; border: 0; }
  .financial-summary { grid-template-columns: 1fr; gap: 16px; }
  .net-summary { min-height: 154px; padding: 22px; }
  .net-summary strong { font-size: 34px; }
  .receipt-breakdown { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
  .receipt-stat { border-bottom: 0; padding: 3px 0 8px; }
  .receipt-stat strong { font-size: 23px; max-width: 100%; }
  .receipt-stat > .el-icon { display: none; }
  .receipt-breakdown > small { grid-column: 1 / -1; margin-top: 7px; }
  .trend-heading { margin-top: 0; padding-top: 0; }
  .dashboard .panel-heading { align-items: center; margin-bottom: 18px; gap: 12px; }
  .dashboard .panel-heading h2 { font-size: 16px; }
  .dashboard .panel-heading .el-button { flex: none; }
  .updated-time { font-size: 12px; }
  .finance-workspace, .dashboard .overview-bottom { gap: 18px; margin-bottom: 18px; }
  .dashboard-desktop-table { display: none; }
  .dashboard-mobile-list { display: block; min-width: 0; }
  .recent-order { display: block; border-top: 1px solid #edf0f5; padding: 18px 0; min-width: 0; }
  .recent-order:last-child { padding-bottom: 0; }
  .recent-order > div, .recent-order footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; }
  .recent-order b { color: #263753; font-size: 14px; font-weight: 500; line-height: 1.6; min-width: 0; flex: 1; overflow-wrap: anywhere; }
  .recent-order p { color: #64748b; font-size: 12px; margin: 6px 0 12px; overflow-wrap: anywhere; }
  .recent-order small { color: #64748b; font-size: 12px; min-width: 0; overflow-wrap: anywhere; }
  .recent-order strong { color: #172b4d; font-size: 19px; font-weight: 600; font-variant-numeric: tabular-nums; min-width: 0; max-width: 55%; overflow-wrap: anywhere; text-align: right; }
  .recent-order :deep(.el-tag), .recent-case :deep(.el-tag) { flex: none; }
  .recent-case { display: flex; align-items: center; gap: 12px; padding: 18px 0; border-top: 1px solid #edf0f5; min-width: 0; }
  .recent-case:last-child { padding-bottom: 0; }
  .recent-case > div { flex: 1; min-width: 0; }
  .recent-case b { display: block; font-size: 14px; line-height: 1.6; color: #263753; font-weight: 500; overflow-wrap: anywhere; }
  .recent-case small { display: block; color: #64748b; font-size: 12px; margin-top: 5px; overflow-wrap: anywhere; }
  .approval-grid { grid-template-columns: 1fr; gap: 14px; }
  .approval-card { padding: 24px; }
  .admin-navigation { padding: 22px 18px; }
  .admin-links > a { padding: 18px 4px; }
  .consumer-overview { grid-template-columns: 1fr; gap: 16px; }
  .consumer-overview .net-summary { padding: 22px; }
  .consumer-overview .net-summary strong { font-size: 36px; }
  .consumer-states { padding: 5px 20px; }
}

.dashboard-error { margin-bottom: 24px; }
.funds-ledger { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr); background: #fff; border: 1px solid #dfe6f0; border-radius: 14px; margin-bottom: 28px; overflow: hidden; box-shadow: 0 5px 20px #11243c04; transition: opacity .18s; }
.funds-ledger.updating { opacity: .65; }
.ledger-net { display: block; padding: 29px 34px 27px; color: #172b4d; background: #f0f4fc; border-right: 1px solid #e1e7f1; }
.ledger-net > span { display: flex; align-items: center; justify-content: space-between; color: #425575; font-size: 13px; }
.ledger-net > span .el-icon { color: #7290ba; transition: transform .18s; }
.ledger-net:hover > span .el-icon { transform: translateX(4px); }
.ledger-net strong { display: block; font-size: clamp(34px, 3.3vw, 52px); font-weight: 600; line-height: 1.3; margin: 14px 0 8px; letter-spacing: -1.8px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.ledger-net em, .ledger-breakdown em { font-style: normal; font-size: .45em; font-weight: 400; letter-spacing: 0; margin-right: 6px; }
.ledger-net small { font-size: 12px; color: #64748b; }
.ledger-breakdown { align-self: center; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 36px 32px 21px; gap: 0 26px; }
.ledger-breakdown > a { min-width: 0; }
.ledger-breakdown > a > span { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #64748b; }
.ledger-breakdown i { display: inline-block; width: 6px; height: 6px; border-radius: 50%; }
.ledger-breakdown strong { display: block; font-size: clamp(23px, 2.2vw, 33px); line-height: 1.4; font-weight: 550; letter-spacing: -.7px; color: #263753; margin-top: 14px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.ledger-breakdown > p { grid-column: 1/-1; margin: 24px 0 0; padding-top: 13px; border-top: 1px solid #edf0f6; font-size: 11px; color: #64748b; }
.ledger-stamp { position: absolute; right: 24px; top: 14px; font-size: 11px; color: #64748b; }
@media (max-width: 650px) {
  .funds-ledger { grid-template-columns: 1fr; margin-bottom: 20px; }
  .ledger-net { padding: 25px 24px; border-right: 0; border-bottom: 1px solid #e1e7f1; }
  .ledger-net > span { padding-right: 70px; }
  .ledger-net strong { font-size: 42px; }
  .ledger-stamp { top: 28px; right: 24px; }
  .ledger-breakdown { padding: 24px; gap: 0 20px; }
  .ledger-breakdown strong { font-size: 25px; }
  .ledger-breakdown > p { margin-top: 18px; }
}

@media (prefers-reduced-motion: reduce) {
  .dashboard *, .dashboard *::before, .dashboard *::after { transition: none !important; }
}
</style>
