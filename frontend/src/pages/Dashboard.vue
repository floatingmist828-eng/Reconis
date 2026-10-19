<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ArrowRight, Plus, Refresh } from "@element-plus/icons-vue";
import { api, money, time, dateDays, action, type Row } from "../api";
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
  loading = ref(false);
async function load() {
  loading.value = true;
  try {
    data.value = await api("/dashboard");
  } finally {
    loading.value = false;
  }
}
onMounted(() => action(load));
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
        <div class="eyebrow">WORKSPACE OVERVIEW</div>
        <h1>{{ session.user?.displayName }}，你好</h1>
        <p>查看收退款，继续处理待办。</p>
      </div>
      <div class="heading-actions">
        <span class="date-chip">{{ dateDays(-29) }} — {{ dateDays() }}</span
        ><el-button
          :icon="Refresh"
          aria-label="刷新工作台"
          @click="action(load)"
        /><el-button
          v-if="session.writable"
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
    <template v-else
      ><div class="kpi-grid">
        <router-link to="/analytics" class="kpi-card"
          ><div class="kpi-label">成功收款 <span>近30日</span></div>
          <strong><em>¥</em>{{ money(data.summary?.receiptCent) }}</strong
          ><small>近30日收款</small
          ><span class="kpi-accent"></span></router-link
        ><router-link to="/refunds" class="kpi-card"
          ><div class="kpi-label">成功退款 <span>近30日</span></div>
          <strong><em>¥</em>{{ money(data.summary?.refundCent) }}</strong
          ><small>近30日退款</small></router-link
        ><router-link to="/analytics" class="kpi-card"
          ><div class="kpi-label">净收款 <span>近30日</span></div>
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
              <p>近30日 · 按交易日期</p>
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
          <span class="eyebrow">YOUR NEXT STEP</span>
          <h2>开始一轮对账</h2>
          <p>导入账单，核对差异，再交给负责的同事处理。</p>
          <div class="workflow-step">
            <b>01</b>
            <div>
              <strong>导入与核对</strong><span>双格式账单 · 逐笔差异</span>
            </div>
          </div>
          <div class="workflow-step">
            <b>02</b>
            <div>
              <strong>派单与处理</strong><span>来源追踪 · 历史案例参考</span>
            </div>
          </div>
          <div class="workflow-step">
            <b>03</b>
            <div>
              <strong>复核与闭环</strong><span>独立人员复核 · 完整留痕</span>
            </div>
          </div>
          <el-button @click="router.push('/reconciliation')"
            >进入对账工作台 <span>→</span></el-button
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
