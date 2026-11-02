<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Plus, Search } from "@element-plus/icons-vue";
import { api, query, money, toCent, time, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
import OrderForm from "../components/OrderForm.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  list = ref<Row>({ items: [], total: 0 }),
  merchants = ref<Row[]>([]),
  stores = ref<Row[]>([]),
  busy = ref(false),
  dialog = ref(false),
  minAmount = ref(""),
  maxAmount = ref("");
const filters = reactive({
  q: "",
  status: String(route.query.status || ""),
  merchantId: "",
  storeId: route.query.storeId || "",
  sort: "createdAt",
  page: 1,
});
async function load() {
  busy.value = true;
  try {
    list.value = await api("/orders?" + query({...filters,minCent:minAmount.value?toCent(minAmount.value):"",maxCent:maxAmount.value?toCent(maxAmount.value):""}));
  } finally {
    busy.value = false;
  }
}
async function loadStores() {
  filters.storeId = "";
  stores.value = filters.merchantId
    ? await api(`/merchants/${filters.merchantId}/stores`)
    : [];
}
onMounted(() =>
  action(async () => {
    await load();
    merchants.value = (await api("/merchants?pageSize=100")).items;
    if (route.query.create === "true" && session.canAny(["OWNER", "CASHIER", "FINANCE"])) dialog.value = true;
  }),
);
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">COLLECTION ORDERS</div>
        <h1>{{ session.consumer ? '我的订单' : '收款订单' }}</h1>
        <p>{{ session.consumer ? '查看校园消费与付款进度。' : '开单、收款，查看订单进展。' }}</p>
      </div>
      <el-button
        v-if="session.canAny(['OWNER', 'CASHIER', 'FINANCE'])"
        type="primary"
        :icon="Plus"
        @click="dialog = true"
        >新建收款订单</el-button
      >
    </div>
    <section class="panel" v-loading="busy">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          :prefix-icon="Search"
          placeholder="订单编号 / 收款主题"
          clearable
          @keyup.enter="action(load)"
        /><el-select v-model="filters.status" placeholder="全部状态" clearable
          ><el-option
            v-for="(text, key) in {
              DRAFT: '草稿',
              WAITING_PAYMENT: '待付款',
              PAID: '已支付',
              CANCELED: '已取消',
              PAYMENT_EXCEPTION: '支付异常',
            }"
            :key="key"
            :label="text"
            :value="key" /></el-select
        ><el-select
          v-if="!session.consumer"
          v-model="filters.merchantId"
          placeholder="全部商户"
          clearable
          @change="action(loadStores)"
          ><el-option
            v-for="m in merchants"
            :key="m.id"
            :label="m.name"
            :value="m.id" /></el-select
        ><el-select
          v-if="stores.length"
          v-model="filters.storeId"
          placeholder="全部门店"
          clearable
          ><el-option
            v-for="s in stores"
            :key="s.id"
            :label="s.name"
            :value="s.id" /></el-select
        ><el-select v-model="filters.sort" placeholder="排序"
          ><el-option label="最新创建" value="createdAt" /><el-option
            label="金额从高到低"
            value="amountDesc" /><el-option
            label="金额从低到高"
            value="amountAsc" /></el-select
        ><el-button
          @click="
            filters.page = 1;
            action(load);
          "
          >查询</el-button
        >
      </div>
      <div class="table-toolbar">
        <el-input
          v-model="minAmount"
          placeholder="最低金额（元）"
          inputmode="decimal"
          style="width: 145px"
        /><span class="muted">—</span
        ><el-input
          v-model="maxAmount"
          placeholder="最高金额（元）"
          inputmode="decimal"
          style="width: 145px"
        /><span class="grow"></span
        ><span class="muted">共 {{ list.total }} 笔订单</span>
      </div>
      <el-table
        :data="list.items"
        class="clickable desktop-order-table"
        @row-click="(r: Row) => router.push('/orders/' + r.id)"
        ><el-table-column label="订单" min-width="230"
          ><template #default="{ row }"
            ><b>{{ row.subject }}</b
            ><span class="subline mono">{{ row.orderNo }}</span></template
          ></el-table-column
        ><el-table-column label="商户 / 门店" min-width="170"
          ><template #default="{ row }"
            >{{ row.merchantName
            }}<span class="subline">{{ row.storeName }}</span></template
          ></el-table-column
        ><el-table-column label="订单金额" align="right" width="115"
          ><template #default="{ row }"
            ><span class="amount">¥ {{ money(row.amountCent) }}</span></template
          ></el-table-column
        ><el-table-column label="状态" width="110"
          ><template #default="{ row }"
            ><StatusTag :value="row.status" /></template></el-table-column
        ><el-table-column label="创建时间" width="165"
          ><template #default="{ row }"
            ><span class="mono">{{ time(row.createdAt) }}</span></template
          ></el-table-column
        ><el-table-column width="65"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              @click.stop="router.push('/orders/' + row.id)"
              >详情 →</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <div class="mobile-order-list">
        <router-link v-for="row in list.items" :key="row.id" :to="'/orders/'+row.id" class="mobile-order-card">
          <div><strong>{{row.subject}}</strong><StatusTag :value="row.status"/></div>
          <p>{{row.storeName || row.merchantName}}</p>
          <footer><span>{{time(row.createdAt)}}</span><b>¥ {{money(row.amountCent)}}</b></footer>
        </router-link>
        <el-empty v-if="!list.items.length" description="暂无订单"/>
      </div>
      <div class="pagination">
        <el-pagination
          v-model:current-page="filters.page"
          :total="Number(list.total)"
          :page-size="20"
          :pager-count="5"
          layout="total,prev,pager,next,jumper"
          @current-change="action(load)"
        />
      </div>
    </section>
    <el-dialog v-model="dialog" title="创建收款订单" width="740"
      ><OrderForm
        v-if="dialog"
        @saved="
          (id) => {
            dialog = false;
            router.push('/orders/' + id);
          }
        "
        @cancel="dialog = false"
    /></el-dialog>
  </div>
</template>
