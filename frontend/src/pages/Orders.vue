<script setup lang="ts">
import { ref, reactive, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Plus, Search, Filter, ArrowDown, ArrowRight, Shop, RefreshLeft } from "@element-plus/icons-vue";
import { api, query, money, toCent, time, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import OrderForm from "../components/OrderForm.vue";
import ListFeedback from "../components/ListFeedback.vue";
const route = useRoute(), router = useRouter(), session = useSession(),
  list = ref<Row>({ items: [], total: 0 }), merchants = ref<Row[]>([]), stores = ref<Row[]>([]),
  busy = ref(false), failed = ref(false), dialog = ref(false), advanced = ref(Boolean(route.query.storeId)), minAmount = ref(""), maxAmount = ref("");
const statuses = [
  { value: "", label: "全部订单" }, { value: "WAITING_PAYMENT", label: "待付款" },
  { value: "PAID", label: "已支付" }, { value: "PAYMENT_EXCEPTION", label: "支付异常" },
  { value: "DRAFT", label: "草稿" }, { value: "CANCELED", label: "已取消" },
];
const filters = reactive({ q: "", status: String(route.query.status || ""), merchantId: "", storeId: route.query.storeId || "", sort: "createdAt", page: 1 });
const advancedCount = computed(() => [filters.merchantId, filters.storeId, minAmount.value, maxAmount.value].filter(Boolean).length);
async function load() {
  busy.value = true;
  failed.value = false;
  try { list.value = await api("/orders?" + query({...filters,minCent:minAmount.value?toCent(minAmount.value):"",maxCent:maxAmount.value?toCent(maxAmount.value):""})); }
  catch (error) { failed.value = true; throw error; }
  finally { busy.value = false; }
}
async function search() { filters.page = 1; await load(); }
async function selectStatus(status: string) { filters.status = status; await search(); }
async function resetFilters() {
  Object.assign(filters, { q: "", status: "", merchantId: "", storeId: "", sort: "createdAt" });
  minAmount.value = ""; maxAmount.value = ""; stores.value = []; await search();
}
async function loadStores() {
  filters.storeId = "";
  stores.value = filters.merchantId ? await api(`/merchants/${filters.merchantId}/stores`) : [];
}
onMounted(() => action(async () => {
  await load(); merchants.value = (await api("/merchants?pageSize=100")).items;
  if (route.query.create === "true" && session.canAny(["OWNER", "CASHIER", "FINANCE"])) dialog.value = true;
}));
</script>
<template>
  <div class="orders-page">
    <div class="page-heading orders-heading">
      <div><h1>{{ session.consumer ? '我的订单' : '收款订单' }}</h1><p>{{ session.consumer ? '查看付款与退款进度' : '收款、付款状态与订单明细' }}</p></div>
      <el-button v-if="session.canAny(['OWNER', 'CASHIER', 'FINANCE'])" type="primary" :icon="Plus" @click="dialog = true">新建收款订单</el-button>
    </div>
      <div class="order-status-nav" role="group" aria-label="订单状态">
        <button v-for="status in statuses" :key="status.value" type="button" :class="{ active: filters.status === status.value }" :aria-pressed="filters.status === status.value" :aria-disabled="busy" @click="!busy && action(() => selectStatus(status.value))">{{ status.label }}</button>
      </div>
    <section class="panel orders-surface" aria-label="订单列表">
      <form class="order-search" @submit.prevent="action(search)">
        <div class="order-search-main">
          <el-input v-model="filters.q" class="order-keyword" :prefix-icon="Search" placeholder="订单编号 / 收款主题" aria-label="订单编号或收款主题" clearable />
          <el-button type="primary" native-type="submit" :loading="busy">查询</el-button>
          <span class="search-divider" aria-hidden="true"></span>
          <el-select v-model="filters.sort" class="order-sort" placeholder="排序" aria-label="订单排序" @change="action(search)"><el-option label="最新创建" value="createdAt" /><el-option label="金额从高到低" value="amountDesc" /><el-option label="金额从低到高" value="amountAsc" /></el-select>
          <el-button class="advanced-toggle" :class="{ 'is-expanded': advanced }" :icon="Filter" :aria-expanded="advanced" aria-controls="order-advanced-filters" @click="advanced = !advanced">高级筛选<span v-if="advancedCount" class="filter-count">{{ advancedCount }}</span><el-icon class="filter-chevron"><ArrowDown /></el-icon></el-button>
        </div>
        <div id="order-advanced-filters" class="order-advanced" :class="{ open: advanced }" :inert="!advanced" :aria-hidden="!advanced"><div class="order-advanced-inner"><div class="advanced-fields">
          <label v-if="!session.consumer" class="advanced-field"><span>所属商户</span><el-select v-model="filters.merchantId" placeholder="全部商户" clearable @change="action(loadStores)"><el-option v-for="m in merchants" :key="m.id" :label="m.name" :value="m.id" /></el-select></label>
          <label v-if="stores.length" class="advanced-field"><span>收款门店</span><el-select v-model="filters.storeId" placeholder="全部门店" clearable><el-option v-for="s in stores" :key="s.id" :label="s.name" :value="s.id" /></el-select></label>
          <div class="advanced-field amount-range"><span>订单金额</span><div><el-input v-model="minAmount" placeholder="最低金额（元）" aria-label="最低金额（元）" inputmode="decimal" clearable /><span class="range-divider">至</span><el-input v-model="maxAmount" placeholder="最高金额（元）" aria-label="最高金额（元）" inputmode="decimal" clearable /></div></div>
          <el-button class="reset-filters" :icon="RefreshLeft" text @click="action(resetFilters)">重置筛选</el-button>
        </div></div></div>
      </form>
      <div class="orders-list-heading"><span>{{ statuses.find(s => s.value === filters.status)?.label || '订单记录' }}<strong>{{ list.total }}</strong></span><el-button v-if="filters.q || filters.status || advancedCount" link @click="action(resetFilters)">清除筛选</el-button><small v-else>金额单位：元</small></div>
      <ListFeedback :failed="failed" :busy="busy" :has-previous="!!list.items.length" noun="订单" @retry="action(load)" />
      <div class="orders-results" v-loading="busy" :aria-busy="busy">
        <el-table :data="list.items" class="clickable desktop-order-table" @row-click="(r: Row) => router.push('/orders/' + r.id)">
          <el-table-column label="订单" min-width="250"><template #default="{ row }"><div class="order-identity"><div><b>{{ row.subject }}</b><span class="subline mono">{{ row.orderNo }}</span></div></div></template></el-table-column>
          <el-table-column label="商户 / 门店" min-width="170"><template #default="{ row }">{{ row.merchantName }}<span class="subline">{{ row.storeName }}</span></template></el-table-column>
          <el-table-column label="订单金额" align="right" width="130"><template #default="{ row }"><span class="amount">¥ {{ money(row.amountCent) }}</span></template></el-table-column>
          <el-table-column label="状态" width="115"><template #default="{ row }"><StatusTag :value="row.status" /></template></el-table-column>
          <el-table-column label="创建时间" width="170"><template #default="{ row }"><span class="order-time">{{ time(row.createdAt) }}</span></template></el-table-column>
          <el-table-column width="85"><template #default="{ row }"><el-button link type="primary" @click.stop="router.push('/orders/' + row.id)">详情<el-icon><ArrowRight /></el-icon></el-button></template></el-table-column>
          <template #empty><el-empty :description="busy ? '正在加载订单' : failed ? '订单暂不可用' : '暂无符合条件的订单'" :image-size="76"><el-button v-if="!busy && !failed && (filters.q || filters.status || advancedCount)" @click="action(resetFilters)">清除筛选</el-button></el-empty></template>
        </el-table>
        <div class="mobile-order-list orders-mobile">
          <router-link v-for="row in list.items" :key="row.id" :to="'/orders/'+row.id" class="order-mobile-card">
            <div class="mobile-record-top"><span class="mobile-record-no">{{ row.orderNo }}</span><StatusTag :value="row.status" /></div><h3>{{ row.subject }}</h3>
            <p><el-icon><Shop /></el-icon><span>{{ row.storeName || row.merchantName }}</span></p>
            <footer><time>{{ time(row.createdAt) }}</time><strong>¥ {{ money(row.amountCent) }}</strong><el-icon><ArrowRight /></el-icon></footer>
          </router-link>
          <el-empty v-if="!list.items.length" :description="busy ? '正在加载订单' : failed ? '订单暂不可用' : '暂无符合条件的订单'" :image-size="76"><el-button v-if="!busy && !failed && (filters.q || filters.status || advancedCount)" @click="action(resetFilters)">清除筛选</el-button></el-empty>
        </div>
      </div>
      <div class="pagination orders-pagination"><el-pagination v-model:current-page="filters.page" :total="Number(list.total)" :page-size="20" :pager-count="5" layout="total,prev,pager,next,jumper" @current-change="action(load)" /></div>
    </section>
    <el-dialog v-model="dialog" title="创建收款订单" width="740"><OrderForm v-if="dialog" @saved="(id) => { dialog = false; router.push('/orders/' + id); }" @cancel="dialog = false" /></el-dialog>
  </div>
</template>
<style scoped>
.orders-heading { margin-bottom: 28px; }
.orders-heading > .el-button { min-height: 42px; padding-inline: 19px; }
.orders-surface { padding: 0; overflow: hidden; }
.order-status-nav { display: flex; gap: 28px; margin-bottom: 20px; padding: 0 2px; border-bottom: 1px solid var(--line); overflow-x: auto; scrollbar-width: thin; }
.order-status-nav button { position: relative; flex: none; min-height: 48px; padding: 0 2px; border: 0; background: transparent; color: var(--muted); font-size: 14px; white-space: nowrap; transition: color .18s; }
.order-status-nav button::after { content: ''; position: absolute; inset: auto 0 0; height: 3px; border-radius: 3px 3px 0 0; background: var(--brand); transform: scaleX(0); transition: transform .2s; }
.order-status-nav button.active { color: var(--brand); font-weight: 600; }
.order-status-nav button.active::after { transform: scaleX(1); }
.order-status-nav button:hover:not(:disabled) { color: var(--brand); }
.order-status-nav button:focus-visible { outline-offset: -5px; border-radius: 6px; }
.order-status-nav button[aria-disabled="true"] { cursor: wait; }
.order-search { padding: 24px 26px 20px; }
.order-search-main { display: flex; align-items: center; gap: 10px; }
.order-keyword { flex: 1; max-width: 430px; min-width: 160px; }
.order-sort { width: 158px; margin-left: auto; flex: none; }
.search-divider { width: 1px; height: 22px; margin: 0 4px; background: var(--line); }
.advanced-toggle { color: #546681; gap: 7px; }
.advanced-toggle.is-expanded { border-color: #c6d4f5; color: var(--brand); background: #f7f9ff; }
.filter-chevron { margin-left: 7px; font-size: 12px; transition: transform .2s; }
.is-expanded .filter-chevron { transform: rotate(180deg); }
.filter-count { padding: 1px 5px; margin-left: 6px; border-radius: 4px; background: #e5edff; color: var(--brand); font-size: 11px; }
.order-advanced { display: grid; grid-template-rows: 0fr; opacity: 0; transition: grid-template-rows .22s ease, opacity .18s; }
.order-advanced.open { grid-template-rows: 1fr; opacity: 1; }
.order-advanced-inner { min-height: 0; overflow: hidden; }
.advanced-fields { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 18px; padding: 22px 0 3px; }
.advanced-field { display: flex; flex-direction: column; gap: 8px; width: 180px; min-width: 0; }
.advanced-field > span { font-size: 12px; color: #546681; }
.amount-range { width: 315px; }
.amount-range > div { display: flex; align-items: center; gap: 9px; }
.range-divider { flex: none; color: var(--muted); font-size: 12px; }
.reset-filters { margin-left: auto; font-size: 12px; }
.orders-list-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 26px 16px; font-size: 13px; color: var(--ink); }
.orders-list-heading > span { display: flex; align-items: center; gap: 9px; }
.orders-list-heading strong { color: #5b6f91; font-size: 12px; font-weight: 500; background: #eff3f9; padding: 2px 7px; border-radius: 5px; font-variant-numeric: tabular-nums; }
.orders-list-heading small { font-size: 12px; color: var(--muted); }
.orders-results { min-height: 130px; }
.orders-results :deep(.el-table .cell) { padding-inline: 22px; }
.orders-results :deep(.el-table__row td:last-child .cell) { padding-inline: 12px; }
.orders-results :deep(.el-table__header-wrapper) { border-radius: 0; }
.orders-results :deep(.el-table td.el-table__cell) { height: 76px; }
.orders-results :deep(.el-table .el-button .el-icon) { margin-left: 3px; }
.order-identity { display: flex; align-items: center; gap: 13px; }
.order-record-icon { display: grid; place-items: center; width: 35px; height: 39px; flex: none; background: #f1f5fd; color: #6d87b5; border: 1px solid #e6edf9; border-radius: 8px; font-size: 18px; }
.order-identity > div { min-width: 0; }
.order-identity b { color: #253956; font-size: 14px; font-weight: 600; }
.order-identity .subline { font-size: 11px; color: #66758c; }
.order-time { font-size: 12px; color: #65758b; font-variant-numeric: tabular-nums; }
.orders-pagination { margin: 0; padding: 21px 26px; border-top: 1px solid var(--line); }
.empty-guidance { color: var(--muted); font-size: 12px; }
.orders-mobile { padding: 0 20px; }
.order-mobile-card { display: block; padding: 20px 0; border-top: 1px solid var(--line); }
.mobile-record-top { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.mobile-record-top :deep(.el-tag) { flex: none; }
.mobile-record-no { min-width: 0; overflow-wrap: anywhere; color: #76849b; font-size: 11px; }
.order-mobile-card h3 { margin-top: 12px; font-size: 15px; line-height: 1.6; overflow-wrap: anywhere; }
.order-mobile-card p { display: flex; align-items: center; gap: 6px; margin-top: 6px; color: var(--muted); font-size: 12px; }
.order-mobile-card p .el-icon { flex: none; }
.order-mobile-card p > span { min-width: 0; overflow-wrap: anywhere; }
.order-mobile-card footer { display: flex; align-items: center; gap: 7px; margin-top: 17px; }
.order-mobile-card time { color: #728199; font-size: 11px; flex: 1; }
.order-mobile-card strong { font-size: 21px; font-weight: 600; color: var(--ink); font-variant-numeric: tabular-nums; }
.order-mobile-card footer > .el-icon { color: #9cabc0; font-size: 13px; }
@media (max-width: 1150px) {
 .order-search-main { flex-wrap: wrap; } .order-keyword { max-width: none; } .search-divider { display: none; } .order-sort { margin-left: 0; }
}
@media (max-width: 760px) {
 .desktop-order-table { display: none; } .orders-mobile { display: block; }
 .orders-heading { gap: 20px; } .orders-heading > .el-button { width: 100%; }
 .order-status-nav { gap: 23px; padding-inline: 20px; } .order-status-nav button { min-height: 55px; font-size: 13px; }
 .order-search { padding: 20px; } .order-search-main { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); gap: 10px; }
 .order-keyword { grid-column: span 9; min-width: 0; width: 100%; }
 .order-search-main > .el-button--primary { grid-column: span 3; padding-inline: 8px; }
 .order-sort, .advanced-toggle { grid-column: span 6; width: 100%; min-width: 0; justify-content: center; }
 .advanced-fields { gap: 16px 12px; } .advanced-field { flex: 1 1 calc(50% - 6px); width: auto; } .amount-range { flex-basis: 100%; }
 .reset-filters { margin-left: 0; } .orders-list-heading { padding: 0 20px 17px; } .orders-list-heading small { display: none; } .orders-pagination { padding: 20px 14px; }
}
@media (prefers-reduced-motion: reduce) { .order-status-nav button, .order-status-nav button::after, .order-advanced, .filter-chevron { transition: none; } }
</style>
