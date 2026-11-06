<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessageBox, ElMessage } from "element-plus";
import { Search, Refresh, InfoFilled } from "@element-plus/icons-vue";
import { api, post, query, money, time, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import ListFeedback from "../components/ListFeedback.vue";
import CategoryArtwork from "../components/CategoryArtwork.vue";
const router = useRouter(),
  route = useRoute(),
  session = useSession(),
  list = ref<Row>({ items: [], total: 0 }),
  filters = reactive({ q: "", status: String(route.query.status || ""), page: 1 }),
  busy = ref(false), failed = ref(false);
async function load() {
  busy.value = true;
  failed.value = false;
  try {
    list.value = await api("/refunds?" + query(filters));
  } catch (error) {
    failed.value = true;
    throw error;
  } finally {
    busy.value = false;
  }
}
onMounted(() => action(load));
const statusOptions = [
  { value: "", label: "全部退款" },
  { value: "PENDING_REVIEW", label: "待审核" },
  { value: "APPROVED", label: "已批准" },
  { value: "PROCESSING", label: "处理中" },
  { value: "UNKNOWN", label: "待确认" },
  { value: "SUCCEEDED", label: "已成功" },
  { value: "REJECTED", label: "已退回" },
];
function selectStatus(value: string) {
  if (busy.value) return;
  filters.status = value;
  filters.page = 1;
  action(load);
}
async function resetFilters() { filters.q = ''; filters.status = ''; filters.page = 1; await load(); }
async function review(row: Row, approve: boolean) {
  const { value } = await ElMessageBox.prompt("填写审批意见", "退款审批", {
    inputValidator: (v) => !!v || "请填写意见",
  });
  await post(`/refunds/${row.id}/review`, { approve, reason: value });
  ElMessage.success("审批已保存");
  await load();
}
async function sync(row: Row) {
  await post(`/refunds/${row.id}/sync`);
  await load();
}
</script>
<template>
  <div class="refund-workspace">
    <div class="page-heading">
      <div>
        <h1>{{ session.consumer ? '我的退款' : '退款' }}</h1>
        <p>{{ session.consumer ? '查看退款申请与到账进度' : '审核申请，跟进退款结果' }}</p>
      </div>
      <el-button :icon="Refresh" @click="action(load)">刷新状态</el-button>
    </div>
      <div class="refund-status-nav" role="group" aria-label="退款状态筛选"><button v-for="option in statusOptions" :key="option.value" :class="{selected: filters.status === option.value}" :aria-pressed="filters.status === option.value" :aria-disabled="busy" @click="selectStatus(option.value)">{{option.label}}</button></div>
    <section class="panel refund-list" v-loading="busy" :aria-busy="busy">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          :prefix-icon="Search"
          placeholder="退款编号 / 申请原因"
          clearable
          aria-label="退款编号或申请原因"
          @keyup.enter="filters.page = 1; action(load)"
        /><el-button type="primary"
          @click="
            filters.page = 1;
            action(load);
          "
          >查询</el-button
        ><span class="grow"></span
        ><span class="muted">{{ list.total }} 笔</span>
        <el-button v-if="filters.q || filters.status" link @click="action(resetFilters)">清除筛选</el-button>
      </div>
      <div v-if="['UNKNOWN', 'PROCESSING'].includes(filters.status)" class="refund-hint"><el-icon><InfoFilled /></el-icon><span>渠道尚未确认退款结果，可通过“查询恢复”更新进度。</span></div>
      <ListFeedback :failed="failed" :busy="busy" :has-previous="!!list.items.length" noun="退款记录" @retry="action(load)" />
      <el-table :data="list.items" class="refund-desktop"
        ><el-table-column label="退款与订单" min-width="270"
          ><template #default="{ row }"
            ><div class="refund-identity"><CategoryArtwork :merchant-name="row.merchantName" size="sm" /><div><span class="refund-reference"><b class="mono">{{ row.outRequestNo }}</b></span>
            <span class="subline"
              ><el-button link @click="router.push('/orders/' + row.orderId)"
                >{{ row.orderNo }} ↗</el-button
              ></span
            ></div></div></template
          ></el-table-column
        ><el-table-column label="商户 / 申请人" min-width="130"
          ><template #default="{ row }"
            >{{ row.merchantName
            }}<span class="subline">{{ row.applicantName }}</span></template
          ></el-table-column
        ><el-table-column label="金额" width="110" align="right"
          ><template #default="{ row }"
            ><b>¥ {{ money(row.amountCent) }}</b></template
          ></el-table-column
        ><el-table-column
          label="退款原因"
          prop="reason"
          min-width="160"
          show-overflow-tooltip
        /><el-table-column label="状态" width="105"
          ><template #default="{ row }"
            ><StatusTag :value="row.status" /></template></el-table-column
        ><el-table-column label="申请时间" width="160"
          ><template #default="{ row }">{{
            time(row.createdAt)
          }}</template></el-table-column
        ><el-table-column v-if="session.writable" label="操作" width="145"
          ><template #default="{ row }"
            ><div class="row-actions">
              <template
                v-if="
                  session.can(row.merchantId, ['OWNER', 'FINANCE']) &&
                  row.status === 'PENDING_REVIEW' &&
                  row.applicantId !== session.user?.id
                "
                ><el-button
                  link
                  type="primary"
                  @click="action(() => review(row, true))"
                  >批准</el-button
                ><el-button link @click="action(() => review(row, false))"
                  >退回</el-button
                ></template
              ><el-button
                v-if="session.can(row.merchantId, ['OWNER', 'FINANCE']) && ['UNKNOWN', 'PROCESSING'].includes(row.status)"
                link
                type="primary"
                @click="action(() => sync(row))"
                >查询恢复</el-button
              ><span
                v-if="
                  row.status === 'PENDING_REVIEW' &&
                  row.applicantId === session.user?.id
                "
                class="muted"
                >等待他人审批</span
              >
            </div></template
          ></el-table-column
        ><template #empty><el-empty :description="busy ? '正在加载退款' : failed ? '退款暂不可用' : '暂无符合条件的退款'" :image-size="68"><el-button v-if="!busy && !failed && (filters.q || filters.status)" @click="action(resetFilters)">清除筛选</el-button></el-empty></template></el-table
      >
      <div class="refund-mobile">
        <article v-for="row in list.items" :key="row.id" class="refund-mobile-row">
          <header><span>{{ row.outRequestNo }}</span><StatusTag :value="row.status" /></header>
          <div class="refund-mobile-amount"><strong>¥ {{ money(row.amountCent) }}</strong><time>{{ time(row.createdAt) }}</time></div>
          <p class="refund-mobile-reason">{{ row.reason }}</p>
          <div class="refund-mobile-source"><CategoryArtwork :merchant-name="row.merchantName" size="sm" /><div><b>{{ row.merchantName }}</b><span>申请人：{{ row.applicantName }}</span></div></div>
          <footer>
            <el-button link type="primary" @click="router.push('/orders/' + row.orderId)">查看订单</el-button>
            <div v-if="session.writable" class="row-actions">
              <template v-if="session.can(row.merchantId, ['OWNER', 'FINANCE']) && row.status === 'PENDING_REVIEW' && row.applicantId !== session.user?.id">
                <el-button size="small" @click="action(() => review(row, false))">退回</el-button><el-button size="small" type="primary" @click="action(() => review(row, true))">批准</el-button>
              </template>
              <el-button v-if="session.can(row.merchantId, ['OWNER', 'FINANCE']) && ['UNKNOWN', 'PROCESSING'].includes(row.status)" size="small" @click="action(() => sync(row))">查询恢复</el-button>
              <span v-if="row.status === 'PENDING_REVIEW' && row.applicantId === session.user?.id" class="muted">等待他人审批</span>
            </div>
          </footer>
        </article>
        <el-empty v-if="!list.items.length" :description="busy ? '正在加载退款' : failed ? '退款暂不可用' : '暂无符合条件的退款'" :image-size="68"><el-button v-if="!busy && !failed && (filters.q || filters.status)" @click="action(resetFilters)">清除筛选</el-button></el-empty>
      </div>
      <div class="pagination">
        <el-pagination
          v-model:current-page="filters.page"
          :total="Number(list.total)"
          :page-size="20"
          layout="total,prev,pager,next"
          @current-change="action(load)"
        />
      </div>
    </section>
  </div>
</template>
<style scoped>
.refund-list { padding: 0; overflow: hidden; }
.refund-list > .table-toolbar { padding: 24px 26px; margin: 0; }
.refund-list > .pagination { padding: 20px 26px; margin: 0; border-top: 1px solid var(--line); }
.refund-status-nav { display: flex; align-items: center; gap: 28px; border-bottom: 1px solid var(--line); margin: 0 0 20px; overflow-x: auto; scrollbar-width: thin; }
.refund-status-nav button { position: relative; padding: 14px 0 17px; border: 0; background: transparent; font-size: 13px; color: var(--muted); white-space: nowrap; flex: none; transition: color .15s; }
.refund-status-nav button::after { position: absolute; content: ''; bottom: 0; left: 0; right: 0; height: 3px; border-radius: 3px 3px 0 0; background: var(--brand); transform: scaleX(0); transition: transform .18s; }
.refund-status-nav button.selected { color: var(--brand); font-weight: 600; }
.refund-status-nav button.selected::after { transform: scaleX(1); }
.refund-status-nav button:hover { color: var(--brand); }
.refund-list > .table-toolbar .el-input { width: 320px; }
.refund-hint { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #667c9e; background: #f5f8fd; padding: 11px 14px; margin-bottom: 20px; border-radius: 7px; }
.refund-hint > .el-icon { color: #93acd7; flex: none; }
.refund-reference { display: flex; align-items: center; gap: 9px; }
.refund-identity { display: flex; align-items: center; gap: 12px; padding-block: 3px; }
.refund-identity > div { min-width: 0; }
.refund-identity .subline :deep(.el-button) { max-width: 100%; height: auto; min-height: 24px; white-space: normal; text-align: left; }
.refund-identity .subline :deep(.el-button > span) { overflow-wrap: anywhere; }
.refund-reference b { min-width: 0; overflow-wrap: anywhere; }
.refund-mobile { display: none; }
.refund-desktop :deep(.cell) { padding-inline: 22px; }
.refund-desktop :deep(td.el-table__cell) { height: 80px; }
.refund-desktop :deep(.el-table__header-wrapper) { border-radius: 0; }
.refund-mobile-row { padding: 22px 20px; border-top: 1px solid var(--line); }
.refund-mobile-row header { display: flex; align-items: center; gap: 12px; }
.refund-mobile-row header > span:first-child { min-width: 0; flex: 1; overflow-wrap: anywhere; font-size: 11px; color: var(--muted); }
.refund-mobile-row header :deep(.el-tag) { flex: none; }
.refund-mobile-amount { display: flex; align-items: baseline; gap: 15px; margin: 18px 0 12px; }
.refund-mobile-amount strong { font-size: 26px; font-weight: 600; font-variant-numeric: tabular-nums; }
.refund-mobile-amount time { margin-left: auto; font-size: 11px; color: var(--muted); }
.refund-mobile-reason { margin: 0; font-size: 14px; overflow-wrap: anywhere; }
.refund-mobile-source { display: flex; align-items: center; gap: 11px; margin: 16px 0 0; font-size: 12px; }
.refund-mobile-source > div { min-width: 0; }
.refund-mobile-source b { display: block; color: #425575; font-weight: 500; line-height: 1.6; overflow-wrap: anywhere; }
.refund-mobile-source > div > span { display: block; margin-top: 3px; color: var(--muted); overflow-wrap: anywhere; }
.refund-mobile-row footer { display: flex; justify-content: space-between; gap: 10px; margin-top: 18px; align-items: center; }
.refund-mobile-row footer .muted { font-size: 12px; }
@media(max-width:650px) { .refund-status-nav { gap: 22px; } .refund-list > .table-toolbar .el-input { width: 100%; } .refund-hint { align-items: flex-start; line-height: 1.7; } .refund-hint > .el-icon { margin-top: 4px; } }
@media(max-width:760px) { .refund-desktop { display: none; } .refund-mobile { display: block; } .refund-list > .table-toolbar { padding: 20px; } .refund-list > .pagination { padding: 20px 14px; } }
@media(prefers-reduced-motion:reduce) { .refund-status-nav button, .refund-status-nav button::after { transition: none; } }
</style>
