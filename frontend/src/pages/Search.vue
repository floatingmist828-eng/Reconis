<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { Search, Reading, ArrowRight, DocumentChecked, Refresh } from "@element-plus/icons-vue";
import { api, query, time, action, type Row } from "../api";
import StatusTag from "../components/StatusTag.vue";
const q = ref(""),
  status = ref("CLOSED"),
  busy = ref(false),
  result = ref<Row | null>(null),
  failed = ref(false),
  searchedQuery = ref(''),
  searchInput = ref<{ focus: () => void }>();
const readiness = ref<Row>({ ready: false, message: "全文检索正在准备" });
let timer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
async function checkReadiness() {
  if (timer) clearTimeout(timer);
  try { readiness.value = await api("/cases/search/status"); }
  catch { readiness.value = { ready: false, message: "暂时无法连接检索服务" }; }
  if (!disposed && !readiness.value.ready) timer = setTimeout(checkReadiness, 3000);
}
onMounted(checkReadiness);
onUnmounted(() => { disposed = true; if (timer) clearTimeout(timer); });
async function search() {
  if (!q.value.trim() || busy.value || !readiness.value.ready) return;
  busy.value = true;
  failed.value = false;
  try {
    result.value = await api(
      "/cases/search?" + query({ q: q.value, status: status.value }),
    );
    searchedQuery.value = q.value.trim();
  } catch (error) {
    failed.value = true;
    await checkReadiness();
    throw error;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="knowledge-page">
    <div class="page-heading"><div><h1>案例知识库</h1><p>查找差错原因与处理记录。</p></div></div>
    <section class="knowledge-search" aria-label="检索案例">
      <div class="search-intro"><el-icon><Reading /></el-icon><h2>搜索问题、分析或处理结论</h2></div>
      <div class="search-main">
        <el-input ref="searchInput" v-model="q" maxlength="400" :prefix-icon="Search" placeholder="描述遇到的问题，例如：支付成功但通知未到达" aria-label="案例关键词" class="knowledge-query" clearable @keyup.enter="action(search)" />
        <el-select v-model="status" placeholder="全部状态" aria-label="案例状态" clearable><el-option label="已解决案例" value="CLOSED" /><el-option label="处理中" value="PROCESSING" /></el-select>
        <el-button type="primary" :disabled="!readiness.ready || !q.trim()" :loading="busy" @click="action(search)">全文检索</el-button>
      </div>
      <div class="search-suggestions"><span>常见问题</span><button v-for="word in ['金额不符', '重复支付', '退款未到账']" :key="word" :disabled="!readiness.ready || busy" @click="q = word; action(search)">{{ word }}</button></div>
      <el-alert v-if="!readiness.ready" :title="readiness.message" type="info" :closable="false" show-icon />
      <el-alert v-if="failed" title="检索未完成，请稍后重试" type="error" :closable="false" show-icon><el-button link type="primary" :icon="Refresh" :disabled="!readiness.ready || busy" @click="action(search)">重新检索</el-button></el-alert>
    </section>
    <div class="search-results-note help-note" role="status">
      <template v-if="busy">正在检索…</template>
      <template v-else-if="result">“{{ searchedQuery }}” · {{ result.items?.length || 0 }} 条结果<span v-if="Number(result.indexPopulationStatus?.status) > 0"> · 搜索索引更新中</span><span v-if="failed"> · 上次检索结果</span></template>
      <template v-else>输入关键词或选择一个常见问题</template>
    </div>
    <div v-if="result?.items?.length" class="knowledge-results" :class="{ updating: busy }" :aria-busy="busy">
      <router-link v-for="c in result.items" :key="c.id" class="knowledge-result" :to="'/cases/' + c.id">
        <span class="result-symbol"><el-icon><DocumentChecked /></el-icon></span>
        <div class="result-main">
          <div class="result-heading"><h3>{{ c.title }}</h3><span class="result-arrow"><el-icon><ArrowRight /></el-icon></span></div>
          <div class="result-meta"><span>{{ c.merchantName }}</span><StatusTag :value="c.classification" /><StatusTag :value="c.status" /></div>
          <p class="result-description">{{ c.description }}</p>
          <p v-if="c.resolutionText" class="result-resolution"><b>处理结论</b>{{ c.resolutionText }}</p>
          <footer><time>{{ time(c.closedAt || c.createdAt) }}</time><span>查看完整案例</span></footer>
        </div>
      </router-link>
    </div>
    <section v-else-if="result" class="knowledge-empty">
      <span class="empty-symbol"><el-icon><Search /></el-icon></span><h2>未找到相关案例</h2><p>缩短问题描述，或将状态切换为“全部状态”。</p><el-button @click="searchInput?.focus()">修改关键词</el-button>
    </section>
    <section v-else class="knowledge-start">
      <div class="knowledge-guide-heading"><el-icon><DocumentChecked /></el-icon><div><h2>案例中可以查看什么</h2><p>从问题记录找到处理依据。</p></div></div>
      <div class="knowledge-guide"><div><b>问题与来源</b><p>原始账单、关联订单和差错类型。</p></div><div><b>核查与处理</b><p>原因分析、处理结论和证据附件。</p></div><div><b>独立复核</b><p>复核意见、结案状态和协作历史。</p></div></div>
    </section>
  </div>
</template>
<style scoped>
.knowledge-page { min-width: 0; }
.knowledge-search { padding: 35px 34px 27px; background: #fff; border: 1px solid #dfe6f1; border-top: 3px solid #2959d9; border-radius: 4px 4px 14px 14px; box-shadow: 0 8px 26px #11243c04; }
.search-intro { display: flex; align-items: center; gap: 11px; margin-bottom: 25px; }
.search-intro > .el-icon { color: #5276b6; font-size: 22px; }
.search-intro h2 { font-size: 17px; font-weight: 550; }
.search-main { display: flex; align-items: center; gap: 12px; }
.knowledge-query { flex: 1; min-width: 0; }
.search-main :deep(.el-input__wrapper), .search-main :deep(.el-select__wrapper) { min-height: 50px; }
.search-main > .el-select { width: 160px; }
.search-main > .el-button { min-height: 50px; padding: 0 26px; }
.search-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 20px; color: var(--muted); font-size: 12px; }
.search-suggestions > span { margin-right: 7px; }
.search-suggestions button { border: 1px solid #e7ecf4; border-radius: 5px; background: #fafbfe; color: #536784; padding: 5px 10px; font-size: 12px; transition: background .16s, border-color .16s; }
.search-suggestions button:hover:not(:disabled) { background: #edf3ff; border-color: #b7c9ef; }
.search-suggestions button:disabled { opacity: .5; }
.knowledge-search .el-alert { margin-top: 18px; }
.search-results-note { margin: 24px 0 18px; min-height: 20px; overflow-wrap: anywhere; }
.knowledge-results { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 0 30px; transition: opacity .18s; }
.knowledge-results.updating { opacity: .55; }
.knowledge-result { display: flex; gap: 24px; padding: 30px 0; border-bottom: 1px solid var(--line); min-width: 0; }
.knowledge-result:last-child { border-bottom: 0; }
.result-symbol { display: grid; place-items: center; width: 42px; height: 49px; background: #f2f5fc; color: #5f7eaf; border: 1px solid #e3eaf5; border-radius: 5px 11px 5px 5px; font-size: 23px; flex: none; }
.result-main { flex: 1; min-width: 0; }
.result-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.result-heading h3 { font-size: 19px; font-weight: 600; line-height: 1.5; overflow-wrap: anywhere; }
.result-arrow { color: #8394b0; flex: none; font-size: 16px; transition: transform .18s, color .18s; }
.knowledge-result:hover .result-heading h3 { color: var(--brand); }
.knowledge-result:hover .result-arrow { transform: translateX(3px); color: var(--brand); }
.result-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 12px; font-size: 12px; color: var(--muted); }
.result-meta > span:first-child { overflow-wrap: anywhere; }
.result-description { color: #536681; font-size: 13px; line-height: 1.85; margin: 20px 0 15px; overflow-wrap: anywhere; }
.result-resolution { border-left: 2px solid #cad7ed; padding-left: 16px; font-size: 13px; line-height: 1.85; color: #445b79; overflow-wrap: anywhere; margin-bottom: 20px; }
.result-resolution b { display: block; font-weight: 550; color: #304767; margin-bottom: 5px; }
.knowledge-result footer { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; color: var(--muted); }
.knowledge-result footer > span { color: var(--brand); }
.knowledge-start { padding: 32px 5px 20px; }
.knowledge-guide-heading { display: flex; gap: 14px; align-items: center; }
.knowledge-guide-heading > .el-icon { font-size: 24px; color: #778eaf; }
.knowledge-guide-heading h2 { font-size: 16px; font-weight: 550; }
.knowledge-guide-heading p { margin-top: 6px; color: var(--muted); font-size: 12px; }
.knowledge-guide { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); margin-top: 30px; }
.knowledge-guide > div { padding: 0 28px; border-left: 1px solid #dce4f1; }
.knowledge-guide > div:first-child { padding-left: 0; border-left: 0; }
.knowledge-guide b { font-size: 13px; font-weight: 550; }
.knowledge-guide p { font-size: 12px; line-height: 1.8; color: var(--muted); margin: 9px 0 0; }
.knowledge-empty { text-align: center; padding: 46px 20px; }
.empty-symbol { display: inline-flex; color: #8198ba; font-size: 32px; margin-bottom: 16px; }
.knowledge-empty h2 { font-size: 18px; }
.knowledge-empty p { margin: 12px 0 24px; font-size: 13px; color: var(--muted); }
@media (max-width: 650px) {
  .knowledge-search { padding: 24px 18px; }
  .search-intro { gap: 8px; margin-bottom: 20px; }
  .search-intro h2 { font-size: 15px; }
  .search-main { flex-wrap: wrap; gap: 10px; }
  .knowledge-query { flex-basis: 100%; }
  .search-main > .el-select { flex: 1; min-width: 0; }
  .search-main > .el-button { padding: 0 19px; }
  .search-suggestions > span { width: 100%; margin-bottom: 3px; }
  .knowledge-results { padding: 0 18px; }
  .knowledge-result { gap: 0; padding: 25px 0; }
  .result-symbol { display: none; }
  .result-heading h3 { font-size: 17px; }
  .result-meta { gap: 8px; }
  .result-meta > span:first-child { width: 100%; }
  .result-description { margin-top: 18px; }
  .result-resolution { padding-left: 12px; }
  .knowledge-guide { grid-template-columns: 1fr; margin-top: 25px; gap: 22px; }
  .knowledge-guide > div, .knowledge-guide > div:first-child { padding: 0 0 0 15px; border-left: 2px solid #dce4f1; }
}
@media (prefers-reduced-motion: reduce) { .knowledge-results, .result-arrow, .search-suggestions button { transition: none; } }
</style>
