<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Search, Reading, ArrowRight, Clock, User, Document } from "@element-plus/icons-vue";
import { codeName, api, query, time, action, type Row } from "../api";
import StatusTag from "../components/StatusTag.vue";
import ListFeedback from "../components/ListFeedback.vue";
const router = useRouter(), route = useRoute(),
  filters = reactive({ q: "", status: String(route.query.status || ""), classification: "", mine: route.query.mine === "true", page: 1 }),
  list = ref<Row>({ items: [], total: 0 }), busy = ref(false), failed = ref(false);
const statuses = [
  { value: "", label: "全部工单" }, { value: "OPEN", label: "待分派" }, { value: "ASSIGNED", label: "已分派" },
  { value: "PROCESSING", label: "处理中" }, { value: "PENDING_REVIEW", label: "待复核" }, { value: "CLOSED", label: "已关闭" },
];
const classifications = ['CHANNEL_ONLY', 'LOCAL_ONLY', 'AMOUNT_MISMATCH', 'BILL_DUPLICATE', 'KEY_UNRESOLVED', 'PENDING_CONFIRMATION', 'EXTRA_SUCCESS'];
async function load() {
  busy.value = true;
  failed.value = false;
  try { list.value = await api("/cases?" + query(filters)); }
  catch (error) { failed.value = true; throw error; }
  finally { busy.value = false; }
}
async function search() { filters.page = 1; await load(); }
async function resetFilters() { Object.assign(filters, { q: '', status: '', classification: '', mine: false }); await search(); }
async function selectStatus(status: string) { filters.status = status; await search(); }
async function changeMine(value: string | number | boolean) { filters.mine = Boolean(value); await search(); }
function isOverdue(row: Row) { return row.status !== 'CLOSED' && row.dueAt && new Date(row.dueAt) < new Date(); }
onMounted(() => action(load));
</script>
<template>
  <div class="cases-page">
    <div class="page-heading cases-heading">
      <div><h1>差错工作台</h1><p>分派任务、核查差异与独立复核</p></div>
      <el-button :icon="Reading" class="case-knowledge" @click="router.push('/search')">检索历史解决案例</el-button>
    </div>
      <div class="case-status-nav" role="group" aria-label="工单状态">
        <button v-for="status in statuses" :key="status.value" type="button" :class="{ active: filters.status === status.value }" :aria-pressed="filters.status === status.value" :aria-disabled="busy" @click="!busy && action(() => selectStatus(status.value))">{{ status.label }}</button>
      </div>
    <section class="panel cases-surface" aria-label="差错工单列表">
      <form class="case-search" @submit.prevent="action(search)">
        <el-input v-model="filters.q" class="case-keyword" :prefix-icon="Search" placeholder="工单编号 / 问题描述" aria-label="工单编号或问题描述" clearable />
        <el-select v-model="filters.classification" class="case-type" placeholder="全部差错类型" clearable @change="action(search)"><el-option v-for="c in classifications" :key="c" :label="codeName(c)" :value="c" /></el-select>
        <el-button type="primary" native-type="submit" :loading="busy">查询</el-button>
        <div class="case-mine"><el-checkbox v-model="filters.mine" @change="(value: string | number | boolean) => action(() => changeMine(value))">我的待办</el-checkbox></div>
      </form>
      <div class="case-list-heading"><span>{{ filters.mine ? '我的工单' : '工单记录' }}<strong>{{ list.total }}</strong></span><el-button v-if="filters.q || filters.status || filters.classification || filters.mine" link @click="action(resetFilters)">清除筛选</el-button></div>
      <ListFeedback :failed="failed" :busy="busy" :has-previous="!!list.items.length" noun="工单" @retry="action(load)" />
      <div class="case-results" v-loading="busy" :aria-busy="busy">
        <el-table :data="list.items" class="clickable case-desktop-table" @row-click="(r: Row) => router.push('/cases/' + r.id)">
          <el-table-column label="问题与来源" min-width="260"><template #default="{ row }"><div class="case-identity"><span class="case-record-icon" :class="{ overdue: isOverdue(row) }"><el-icon><Document /></el-icon></span><div><b>{{ row.title }}</b><span class="subline case-no">{{ row.caseNo }}</span></div></div></template></el-table-column>
          <el-table-column label="所属商户" prop="merchantName" min-width="130" />
          <el-table-column label="差错类型" width="130"><template #default="{ row }"><span class="case-classification">{{ codeName(row.classification) }}</span></template></el-table-column>
          <el-table-column label="处理人" width="110"><template #default="{ row }"><span class="case-assignee" :class="{ unassigned: !row.assigneeName }"><el-icon><User /></el-icon>{{ row.assigneeName || '待分派' }}</span></template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :value="row.status" /></template></el-table-column>
          <el-table-column label="截止时间" width="165"><template #default="{ row }"><div class="case-deadline" :class="{ overdue: isOverdue(row) }"><span>{{ time(row.dueAt) }}</span><small v-if="isOverdue(row)"><i></i>已逾期</small></div></template></el-table-column>
          <el-table-column width="80"><template #default="{ row }"><el-button link type="primary" @click.stop="router.push('/cases/' + row.id)">查看</el-button></template></el-table-column>
          <template #empty><el-empty :description="busy ? '正在加载工单' : failed ? '工单暂不可用' : '暂无符合条件的工单'" :image-size="76"><el-button v-if="!busy && !failed && (filters.q || filters.status || filters.classification || filters.mine)" @click="action(resetFilters)">清除筛选</el-button></el-empty></template>
        </el-table>
        <div class="case-mobile-list">
          <router-link v-for="row in list.items" :key="row.id" :to="'/cases/'+row.id" class="case-mobile-card">
            <div class="case-mobile-top"><span>{{ row.caseNo }}</span><StatusTag :value="row.status" /></div><h3>{{ row.title }}</h3>
            <div class="case-mobile-source"><span class="case-type-label">{{ codeName(row.classification) }}</span><span>{{ row.merchantName }}</span></div>
            <footer><span class="case-assignee" :class="{ unassigned: !row.assigneeName }"><el-icon><User /></el-icon>{{ row.assigneeName || '待分派' }}</span><el-icon><ArrowRight /></el-icon></footer>
            <div class="case-mobile-deadline" :class="{ overdue: isOverdue(row) }"><el-icon><Clock /></el-icon><span>{{ time(row.dueAt) }} 截止</span><b v-if="isOverdue(row)">已逾期</b></div>
          </router-link>
          <el-empty v-if="!list.items.length" :description="busy ? '正在加载工单' : failed ? '工单暂不可用' : '暂无符合条件的工单'" :image-size="76"><el-button v-if="!busy && !failed && (filters.q || filters.status || filters.classification || filters.mine)" @click="action(resetFilters)">清除筛选</el-button></el-empty>
        </div>
      </div>
      <div class="pagination cases-pagination"><el-pagination v-model:current-page="filters.page" :total="Number(list.total)" :page-size="20" :pager-count="5" layout="total,prev,pager,next" @current-change="action(load)" /></div>
    </section>
  </div>
</template>
<style scoped>
.cases-heading { margin-bottom: 28px; }
.case-knowledge { min-height: 42px; padding-inline: 17px; color: #415a84; }
.cases-surface { padding: 0; overflow: hidden; }
.case-status-nav { display: flex; gap: 28px; margin-bottom: 20px; padding: 0 2px; border-bottom: 1px solid var(--line); overflow-x: auto; scrollbar-width: thin; }
.case-status-nav button { position: relative; flex: none; min-height: 48px; padding: 0 2px; border: 0; background: transparent; color: var(--muted); font-size: 14px; white-space: nowrap; transition: color .18s; }
.case-status-nav button::after { content: ''; position: absolute; inset: auto 0 0; height: 3px; border-radius: 3px 3px 0 0; background: var(--brand); transform: scaleX(0); transition: transform .2s; }
.case-status-nav button.active { color: var(--brand); font-weight: 600; }
.case-status-nav button.active::after { transform: scaleX(1); }
.case-status-nav button:hover:not(:disabled) { color: var(--brand); }
.case-status-nav button:focus-visible { outline-offset: -5px; border-radius: 6px; }
.case-status-nav button[aria-disabled="true"] { cursor: wait; }
.case-search { display: flex; align-items: center; gap: 10px; padding: 24px 26px 20px; }
.case-keyword { flex: 1; min-width: 160px; max-width: 430px; }
.case-type { width: 170px; flex: none; }
.case-mine { display: flex; align-items: center; margin-left: auto; padding-left: 22px; border-left: 1px solid var(--line); }
.case-mine :deep(.el-checkbox__label) { font-size: 13px; }
.case-list-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 0 26px 16px; color: var(--ink); font-size: 13px; }
.case-list-heading > span { display: flex; align-items: center; gap: 9px; }
.case-list-heading strong { color: #5b6f91; font-size: 12px; font-weight: 500; background: #eff3f9; padding: 2px 7px; border-radius: 5px; font-variant-numeric: tabular-nums; }
.case-list-heading small { display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 12px; }
.case-results { min-height: 130px; }
.case-results :deep(.el-table .cell) { padding-inline: 22px; }
.case-results :deep(.el-table__header-wrapper) { border-radius: 0; }
.case-results :deep(.el-table td.el-table__cell) { height: 82px; }
.case-identity { display: flex; align-items: center; gap: 13px; }
.case-record-icon { display: grid; place-items: center; flex: none; width: 35px; height: 39px; border-radius: 8px; border: 1px solid #e3eaf7; background: #f1f5fd; color: #758db7; font-size: 18px; }
.case-record-icon.overdue { border-color: #f0e3d3; background: #fff7ed; color: #bc8b52; }
.case-identity > div { min-width: 0; }
.case-identity b { color: #253956; font-size: 14px; font-weight: 600; }
.case-no { font-size: 11px; color: #75849c; }
.case-classification { color: #566b8b; font-size: 12px; }
.case-assignee { display: inline-flex; align-items: center; gap: 6px; color: #435978; font-size: 12px; }
.case-assignee .el-icon { flex: none; color: #8495ae; font-size: 14px; }
.case-assignee.unassigned { color: #8793a5; }
.case-deadline { color: #64748b; font-size: 12px; font-variant-numeric: tabular-nums; }
.case-deadline.overdue > span { color: #9c6b2b; }
.case-deadline small { display: flex; align-items: center; gap: 5px; margin-top: 5px; font-size: 11px; color: #a67331; }
.case-deadline i { width: 4px; height: 4px; border-radius: 50%; background: #c4934d; }
.cases-pagination { margin: 0; padding: 21px 26px; border-top: 1px solid var(--line); }
.empty-guidance { color: var(--muted); font-size: 12px; }
.case-mobile-list { display: none; padding: 0 20px; }
.case-mobile-card { display: block; padding: 20px 0; border-top: 1px solid var(--line); }
.case-mobile-top { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.case-mobile-top > span { min-width: 0; color: #76849b; font-size: 11px; overflow-wrap: anywhere; }
.case-mobile-top :deep(.el-tag) { flex: none; }
.case-mobile-card h3 { margin-top: 12px; font-size: 15px; line-height: 1.6; overflow-wrap: anywhere; }
.case-mobile-source { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-top: 10px; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.case-type-label { padding: 3px 7px; color: #536d9b; background: #f0f4fc; border-radius: 4px; }
.case-mobile-card footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 18px; }
.case-mobile-card footer > .el-icon { color: #9cabc0; font-size: 13px; }
.case-mobile-card .case-assignee { min-width: 0; overflow-wrap: anywhere; }
.case-mobile-deadline { display: flex; align-items: center; gap: 6px; margin-top: 15px; padding-top: 12px; border-top: 1px dashed var(--line); color: #75839a; font-size: 11px; font-variant-numeric: tabular-nums; }
.case-mobile-deadline > .el-icon { flex: none; }
.case-mobile-deadline.overdue { color: #9c6b2b; }
.case-mobile-deadline b { margin-left: auto; flex: none; font-size: 11px; font-weight: 500; }
@media (max-width: 1150px) { .case-search { flex-wrap: wrap; } .case-keyword { max-width: none; } .case-mine { padding-left: 12px; } }
@media (max-width: 760px) {
 .cases-heading { gap: 20px; } .case-knowledge { width: 100%; } .case-status-nav { gap: 24px; padding-inline: 20px; } .case-status-nav button { min-height: 55px; font-size: 13px; }
 .case-search { padding: 20px; gap: 10px; } .case-keyword { flex-basis: calc(100% - 80px); min-width: 0; order: 0; } .case-search > .el-button--primary { order: 1; flex: none; }
 .case-type { order: 2; width: calc(100% - 110px); } .case-mine { order: 3; margin-left: auto; padding-left: 10px; border: 0; }
 .case-list-heading { padding: 0 20px 17px; } .case-list-heading small { display: none; } .cases-pagination { padding: 20px 14px; }
}
@media (max-width: 760px) { .case-desktop-table { display: none; } .case-mobile-list { display: block; } }
@media (prefers-reduced-motion: reduce) { .case-status-nav button, .case-status-nav button::after { transition: none; } }
</style>
