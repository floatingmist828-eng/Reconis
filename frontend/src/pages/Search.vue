<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";
import { Search } from "@element-plus/icons-vue";
import { api, query, time, action, type Row } from "../api";
import StatusTag from "../components/StatusTag.vue";
const router = useRouter(),
  q = ref(""),
  status = ref("CLOSED"),
  busy = ref(false),
  result = ref<Row | null>(null);
const readiness = ref<Row>({ ready: false, message: "全文检索正在准备" });
let timer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
async function checkReadiness() {
  try { readiness.value = await api("/cases/search/status"); }
  catch { readiness.value = { ready: false, message: "暂时无法连接检索服务" }; }
  if (!disposed && !readiness.value.ready) timer = setTimeout(checkReadiness, 3000);
}
onMounted(checkReadiness);
onUnmounted(() => { disposed = true; if (timer) clearTimeout(timer); });
async function search() {
  if (!q.value.trim()) return;
  busy.value = true;
  result.value = null;
  try {
    result.value = await api(
      "/cases/search?" + query({ q: q.value, status: status.value }),
    );
  } catch (error) {
    await checkReadiness();
    throw error;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">CASE KNOWLEDGE LIBRARY</div>
        <h1>案例知识库</h1>
        <p>描述遇到的问题，查找历史处理经验。</p>
      </div>
      <span class="environment-badge">全文检索</span>
    </div>
    <section class="panel" v-loading="busy">
      <div class="table-toolbar">
        <el-input
          v-model="q"
          maxlength="400"
          :prefix-icon="Search"
          placeholder="描述遇到的问题，例如：支付成功但通知未到达"
          style="width: min(100%, 600px)"
          clearable
          @keyup.enter="action(search)"
        /><el-select v-model="status" placeholder="全部状态" clearable
          ><el-option label="已解决案例" value="CLOSED" /><el-option
            label="处理中"
            value="PROCESSING" /></el-select
        ><el-button type="primary" :disabled="!readiness.ready || busy" @click="action(search)">全文检索</el-button>
      </div>
      <el-alert v-if="!readiness.ready" :title="readiness.message" type="info" :closable="false" show-icon />
      <div v-if="result" class="help-note">
        找到
        {{ result.items?.length || 0 }} 条结果。{{
          Number(result.indexPopulationStatus?.status) > 0
            ? "搜索索引更新中。"
            : ""
        }}
      </div>
      <div v-if="result?.items?.length" class="compact-list">
        <div
          v-for="c in result.items"
          :key="c.id"
          class="case-card"
          @click="router.push('/cases/' + c.id)"
        >
          <div class="card-caption">
            <span>{{ c.merchantName }} · {{ c.caseNo }}</span
            ><span>相关度 {{ c.relevance }}</span>
          </div>
          <h3 style="margin-top: 12px">{{ c.title }}</h3>
          <div class="row-actions">
            <StatusTag :value="c.classification" /><StatusTag
              :value="c.status"
            /><small class="muted">{{ time(c.closedAt || c.createdAt) }}</small>
          </div>
          <p>{{ c.description }}</p>
          <p v-if="c.resolutionText"><b>处理结论：</b>{{ c.resolutionText }}</p>
        </div>
      </div>
      <el-empty
        v-else
        :description="
          result ? '暂无命中案例，可调整问题描述或状态' : '输入问题描述开始搜索'
        "
        :image-size="110"
      />
    </section>
  </div>
</template>
