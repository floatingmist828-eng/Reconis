<script setup lang="ts">
import { ref, onMounted } from "vue";
import { codeName, api, time, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
const data = ref<Row>({ recentQueries: [] }),
  session = useSession(),
  busy = ref(false);
async function load() {
  busy.value = true;
  try {
    data.value = await api("/integration/status");
    session.integration = data.value;
  } finally {
    busy.value = false;
  }
}
onMounted(() => action(load));
</script>
<template>
  <div v-loading="busy">
    <div class="page-heading">
      <div>
        <div class="eyebrow">CHANNEL INTEGRATION</div>
        <h1>支付接入与自动获取</h1>
        <p>查看渠道连接和最近查询。</p>
      </div>
      <el-button @click="action(load)">刷新接入状态</el-button>
    </div>
    <div class="kpi-grid two">
      <section class="kpi-card">
        <span>当前环境</span
        ><strong style="font-size: 24px">{{ data.environment || "—" }}</strong
        ><small>{{
          data.environment === "DEMO"
            ? "隔离的教学模拟渠道"
            : "支付宝官方测试网关"
        }}</small>
      </section>
      <section class="kpi-card">
        <span>连接配置</span
        ><strong style="font-size: 24px">{{
          data.configured ? "已配置" : "未配置"
        }}</strong
        ><small
          >定时扫描间隔
          {{ data.automaticQueryIntervalSeconds || 15 }}
          秒，失败后延长重试间隔</small
        >
      </section>
    </div>
    <section class="panel" style="margin-bottom: 23px">
      <div class="panel-heading"><h2>接入说明</h2></div>
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
      </div>
      <div class="help-note">
        {{
          data.environment === "DEMO"
            ? "演示模式：在付款页体验通知丢失、重复通知和查询恢复。"
            : "支付宝沙箱：预下单、交易查询、退款及退款查询。"
        }}<br />
      </div>
    </section>
    <section class="panel">
      <div class="panel-heading">
        <div>
          <h2>最近自动 / 手动渠道查询</h2>
          <p>自动与手动查询记录</p>
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
</template>
