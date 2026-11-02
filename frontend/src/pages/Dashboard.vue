<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ArrowRight, Plus, Refresh } from "@element-plus/icons-vue";
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
  days = ref(30),
  merchantId = ref(""),
  merchants = ref<Row[]>([]);
async function load() {
  loading.value = true;
  try {
    data.value = await api("/dashboard?"+query({start:dateDays(1-days.value),end:dateDays(),merchantId:merchantId.value}));
  } finally {
    loading.value = false;
  }
}
onMounted(() => action(async()=>{
  await load();
  if (!session.admin && !session.consumer) merchants.value=(await api('/merchants?status=ACTIVE&pageSize=100')).items;
}));
const option = computed(() => ({
  color: ["#268577", "#b7c8bc"],
  tooltip: { trigger: "axis" },
  legend: { data: ["收款", "退款"], right: 12, top: 8 },
  grid: { left: 58, right: 22, top: 55, bottom: 32 },
  xAxis: {
    type: "category",
    data: data.value.trend?.map((r: Row) => r.day.slice(5)),
    axisLine: { lineStyle: { color: "#e8ece9" } },
    axisTick: { show: false },
  },
  yAxis: {
    type: "value",
    name: "元",
    splitLine: { lineStyle: { color: "#edf0ed" } },
    axisLabel: { color: "#81908a" },
  },
  series: [
    {
      name: "收款",
      type: "line",
      smooth: true,
      symbolSize: 5,
      data: data.value.trend?.map((r: Row) => Number(r.receiptCent) / 100),
      areaStyle: { color: "#268577", opacity: 0.06 },
    },
    {
      name: "退款",
      type: "line",
      smooth: true,
      symbolSize: 4,
      data: data.value.trend?.map((r: Row) => Number(r.refundCent) / 100),
    },
  ],
}));
</script>
<template>
  <div v-loading="loading">
    <div class="page-heading">
      <div>
        <div class="eyebrow">{{ session.consumer ? 'MY CAMPUS' : 'WORKSPACE OVERVIEW' }}</div>
        <h1>{{ session.consumer ? '我的校园消费' : '经营概览' }}</h1>
        <p>{{session.consumer ? '订单与退款，随时查看。' : '掌握收退款，及时处理差错。'}}</p>
      </div>
      <div class="heading-actions">
        <template v-if="!session.admin && !session.consumer"><el-select v-model="merchantId" placeholder="全部商户" clearable class="overview-merchant" @change="action(load)"><el-option v-for="m in merchants" :key="m.id" :label="m.name" :value="m.id"/></el-select><el-select v-model="days" class="overview-period" @change="action(load)"><el-option v-for="n in [7,30,90]" :key="n" :label="'近'+n+'日'" :value="n"/></el-select></template>
        <el-button
          :icon="Refresh"
          aria-label="刷新工作台"
          @click="action(load)"
        /><el-button
          v-if="session.canAny(['OWNER', 'CASHIER', 'FINANCE'])"
          type="primary"
          :icon="Plus"
          @click="router.push('/orders?create=true')"
          >新建收款</el-button
        >
      </div>
    </div>
    <template v-if="data.admin"
      ><div class="kpi-grid two">
        <router-link to="/users" class="kpi-card"
          ><span>待审核账号</span><strong>{{ data.pendingUsers || "0" }}</strong
          ><small>查看注册申请 →</small></router-link
        ><router-link to="/merchants" class="kpi-card"
          ><span>待审核商户资料</span
          ><strong>{{ data.pendingMerchants || "0" }}</strong
          ><small>新商户与资料变更 →</small></router-link
        >
      </div>
      <div class="panel admin-intro">
        <div class="eyebrow">ADMINISTRATION</div>
        <h2>管理账号与商户</h2>
        <p>审核新申请，管理成员权限，查看操作记录。</p>
      </div></template
    >
    <template v-else-if="data.consumer">
      <div class="kpi-grid consumer-metrics">
        <router-link to="/orders" class="kpi-card"><span class="kpi-label">全部订单</span><strong>{{data.summary?.orderCount||0}}</strong><small>查看消费明细 →</small></router-link>
        <router-link to="/orders?status=WAITING_PAYMENT" class="kpi-card"><span class="kpi-label">待付款</span><strong>{{data.summary?.waitingCount||0}}</strong><small>继续付款 →</small></router-link>
        <router-link to="/orders?status=PAID" class="kpi-card"><span class="kpi-label">已支付</span><strong>{{data.summary?.paidCount||0}}</strong><small>查看订单 →</small></router-link>
        <router-link to="/refunds" class="kpi-card task-kpi"><span class="kpi-label">退款处理中</span><strong>{{data.pendingRefunds||0}}</strong><small>查看进度 →</small></router-link>
      </div>
      <section class="panel"><div class="panel-heading"><div><h2>最近订单</h2><p>你的校园消费记录</p></div><el-button link @click="router.push('/orders')">全部订单 →</el-button></div>
        <el-table :data="data.recentOrders" class="clickable" @row-click="(r:Row)=>router.push('/orders/'+r.id)"><el-table-column label="订单" prop="subject" min-width="190"/><el-table-column label="商户" prop="merchantName" min-width="130"/><el-table-column label="金额" align="right" width="125"><template #default="{row}">¥ {{money(row.amountCent)}}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{row}"><StatusTag :value="row.status"/></template></el-table-column><el-table-column label="下单时间" width="175"><template #default="{row}">{{time(row.createdAt)}}</template></el-table-column></el-table>
      </section>
    </template>
    <template v-else
      ><div class="kpi-grid">
        <router-link to="/analytics" class="kpi-card"
          ><div class="kpi-label">成功收款 <span>{{days}}日</span></div>
          <strong><em>¥</em>{{ money(data.summary?.receiptCent) }}</strong
          ><small>{{ dateDays(1-days) }} — {{ dateDays() }}</small
          ><span class="kpi-accent"></span></router-link
        ><router-link to="/refunds" class="kpi-card"
          ><div class="kpi-label">成功退款 <span>{{days}}日</span></div>
          <strong><em>¥</em>{{ money(data.summary?.refundCent) }}</strong
          ><small>按退款发生日期统计</small></router-link
        ><router-link to="/analytics" class="kpi-card"
          ><div class="kpi-label">净收款 <span>{{days}}日</span></div>
          <strong><em>¥</em>{{ money(data.summary?.netCent) }}</strong
          ><small>收款 − 退款</small></router-link
        ><router-link to="/cases" class="kpi-card task-kpi"
          ><div class="kpi-label">
            待处理差错 <span class="status-dot"></span>
          </div>
          <strong>{{ data.openCases || "0" }}<em class="unit">件</em></strong
          ><small>进入差错工作台 <span>↗</span></small></router-link
        >
      </div>
      <div class="overview-main">
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>收退款趋势</h2>
              <p>近{{days}}日 · 按交易日期</p>
            </div>
            <el-button link type="primary" @click="router.push('/analytics')"
              >查看分析 <el-icon><ArrowRight /></el-icon
            ></el-button>
          </div>
          <Chart
            v-if="data.trend?.length"
            :option="option"
            :height="310"
          /><el-empty
            v-else
            description="所选期间暂无已确认交易"
            :image-size="85"
          />
        </section>
        <section class="panel workflow-panel">
          <span class="eyebrow">ACTION CENTER</span>
          <h2>待办事项</h2>
          <p>当前工作空间的处理进度</p>
          <router-link to="/refunds?status=PENDING_REVIEW" class="work-item"><span><b>退款待审批</b><small>查看申请与可退余额</small></span><strong>{{data.pendingRefunds||0}}</strong><span>↗</span></router-link>
          <router-link to="/cases?status=PENDING_REVIEW" class="work-item"><span><b>工单待复核</b><small>查看处理结论</small></span><strong>{{data.pendingReviews||0}}</strong><span>↗</span></router-link>
          <router-link to="/orders?status=WAITING_PAYMENT" class="work-item"><span><b>支付待确认</b><small>跟进付款与查询</small></span><strong>{{data.pendingPayments||0}}</strong><span>↗</span></router-link>
          <div class="overdue-strip"><span>逾期未结工单</span><strong>{{data.overdueCases||0}} <small>件</small></strong></div>
          <el-button @click="router.push('/reconciliation')"
            >导入账单并核对 <span>→</span></el-button
          >
        </section>
      </div>
      <div class="overview-bottom">
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>需要关注的差错</h2>
              <p>优先处理未关闭事项</p>
            </div>
            <el-button link @click="router.push('/cases')"
              >全部工单 →</el-button
            >
          </div>
          <el-table
            :data="data.recentCases"
            @row-click="(r: Row) => router.push('/cases/' + r.id)"
            class="clickable"
            ><el-table-column
              label="问题描述"
              prop="title"
              min-width="180"
              show-overflow-tooltip /><el-table-column
              label="所属商户"
              prop="merchantName"
              min-width="110" /><el-table-column label="处理状态" width="110"
              ><template #default="{ row }"
                ><StatusTag :value="row.status" /></template></el-table-column
          ></el-table>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>最近对账批次</h2>
              <p>最近运行记录</p>
            </div>
            <el-button link @click="router.push('/reconciliation')"
              >查看全部 →</el-button
            >
          </div>
          <div
            v-for="b in data.recentBatches"
            :key="b.id"
            class="batch-mini"
            @click="router.push('/reconciliation?batchId=' + b.id)"
          >
            <span class="batch-icon">≋</span>
            <div>
              <b>{{ b.merchantName }}</b
              ><small>{{ time(b.createdAt) }}</small>
            </div>
            <StatusTag :value="b.status" />
          </div>
          <el-empty
            v-if="!data.recentBatches?.length"
            description="尚无对账批次"
            :image-size="60"
          />
        </section></div
    ></template>
  </div>
</template>
