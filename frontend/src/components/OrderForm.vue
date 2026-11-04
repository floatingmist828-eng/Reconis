<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { api, post, patch, money, toCent, action, type Row } from "../api";
import { useSession } from "../stores/session";
const session = useSession();
const props = defineProps<{ existing?: Row }>(),
  emit = defineEmits<{ saved: [id: string]; cancel: [] }>();
const merchants = ref<Row[]>([]),
  stores = ref<Row[]>([]),
  merchantId = ref(""),
  busy = ref(false);
const form = reactive<Row>({
  clientRequestId: crypto.randomUUID(),
  subject: "",
  remark: "",
  storeId: "",
  consumerUsername: "",
  items: [
    { itemName: "", categoryCode: "其他", quantity: 1, unitPriceCent: "0" },
  ],
  ...props.existing,
});
form.items = form.items.map((i: Row) => ({
  ...i,
  unitPriceYuan: money(i.unitPriceCent).replace(/,/g, ""),
}));
onMounted(() =>
  action(async () => {
    const r = await api("/merchants?status=ACTIVE&pageSize=100");
    merchants.value = r.items.filter((m: Row) => session.can(m.id, ["OWNER", "CASHIER", "FINANCE"]));
    if (props.existing) merchantId.value = props.existing.merchantId;
    else if (merchants.value.length) merchantId.value = merchants.value[0]!.id;
    await loadStores();
  }),
);
async function loadStores() {
  if (!merchantId.value) return;
  stores.value = await api(`/merchants/${merchantId.value}/stores`);
  if (!props.existing)
    form.storeId = stores.value.find((s) => s.status === "ACTIVE")?.id || "";
}
function total() {
  try {
    return money(
      form.items
        .reduce(
          (n: bigint, i: Row) =>
            n + BigInt(toCent(i.unitPriceYuan)) * BigInt(i.quantity || 0),
          0n,
        )
        .toString(),
    );
  } catch {
    return "—";
  }
}
function itemTotal(item: Row) {
  try {
    return money((BigInt(toCent(item.unitPriceYuan)) * BigInt(item.quantity || 0)).toString());
  } catch {
    return "—";
  }
}
async function save() {
  const body = {
    ...form,
    items: form.items.map((i: Row) => ({
      ...i,
      unitPriceCent: toCent(i.unitPriceYuan),
    })),
  };
  busy.value = true;
  try {
    const row = props.existing
      ? await patch("/orders/" + props.existing.id, body)
      : await post("/orders", body);
    emit("saved", String(row.id || props.existing!.id));
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-form label-position="top"
    ><div class="form-grid">
      <el-form-item label="所属商户"
        ><el-select
          v-model="merchantId"
          :disabled="!!existing"
          @change="action(loadStores)"
          ><el-option
            v-for="m in merchants"
            :key="m.id"
            :label="m.name"
            :value="m.id" /></el-select></el-form-item
      ><el-form-item label="收款门店"
        ><el-select v-model="form.storeId" :disabled="!!existing"
          ><el-option
            v-for="s in stores.filter((x) => x.status === 'ACTIVE')"
            :key="s.id"
            :label="s.name"
            :value="s.id" /></el-select></el-form-item
      ><el-form-item label="订单主题" class="full"
        ><el-input
          v-model="form.subject"
          maxlength="160"
          placeholder="例如：图书馆咖啡吧 · 午间消费" /></el-form-item
      ><el-form-item
        v-if="!existing"
        label="消费者用户名（可留空）"
        class="full"
        ><el-input
          v-model="form.consumerUsername"
          placeholder="留空为柜台匿名订单"
      /></el-form-item>
    </div>
    <div class="section-divider">收款明细 <span class="muted"></span></div>
    <el-table :data="form.items" class="order-form-desktop-items"
      ><el-table-column label="项目名称" min-width="160"
        ><template #default="{ row }"
          ><el-input
            v-model="row.itemName"
            maxlength="120" /></template></el-table-column
      ><el-table-column label="数量" width="100"
        ><template #default="{ row }"
          ><el-input-number
            v-model="row.quantity"
            :min="1"
            :max="10000"
            controls-position="right"
            style="width: 85px" /></template></el-table-column
      ><el-table-column label="单价（元）" width="130"
        ><template #default="{ row }"
          ><el-input
            v-model="row.unitPriceYuan"
            inputmode="decimal" /></template></el-table-column
      ><el-table-column width="60"
        ><template #default="{ $index }"
          ><el-button
            link
            type="danger"
            :disabled="form.items.length === 1"
            @click="form.items.splice($index, 1)"
            >移除</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <div class="order-form-mobile-items">
      <article v-for="(item, index) in form.items" :key="index" class="order-form-item-card" role="group" :aria-label="`第 ${index + 1} 项收款明细`">
        <header><strong>明细 {{ index + 1 }}</strong><el-button link type="danger" :disabled="form.items.length === 1" :aria-label="`删除第 ${index + 1} 项明细`" @click="form.items.splice(index, 1)">删除</el-button></header>
        <label :for="`mobile-item-name-${index}`">项目名称</label>
        <el-input :id="`mobile-item-name-${index}`" v-model="item.itemName" maxlength="120" placeholder="填写商品或服务名称" />
        <div class="mobile-item-values">
          <div><label :for="`mobile-item-quantity-${index}`">数量</label><el-input-number :id="`mobile-item-quantity-${index}`" v-model="item.quantity" :min="1" :max="10000" controls-position="right" /></div>
          <div><label :for="`mobile-item-price-${index}`">单价（元）</label><el-input :id="`mobile-item-price-${index}`" v-model="item.unitPriceYuan" inputmode="decimal" /></div>
        </div>
        <footer><span>小计</span><strong>¥ {{ itemTotal(item) }}</strong></footer>
      </article>
    </div>
    <div class="table-toolbar order-form-items-total" style="margin-top: 15px">
      <el-button
        @click="
          form.items.push({
            itemName: '',
            categoryCode: '其他',
            quantity: 1,
            unitPriceCent: '0',
            unitPriceYuan: '0.00',
          })
        "
        >＋ 添加明细</el-button
      ><span class="grow"></span><b>合计 ¥ {{ total() }}</b>
    </div>
    <el-form-item label="备注"
      ><el-input v-model="form.remark" type="textarea" maxlength="1000"
    /></el-form-item>
    <div class="row-actions" style="justify-content: flex-end">
      <el-button @click="emit('cancel')">取消</el-button
      ><el-button type="primary" :loading="busy" @click="action(save)">{{
        existing ? "保存草稿" : "创建订单草稿"
      }}</el-button>
    </div></el-form
  >
</template>

<style scoped>
.order-form-mobile-items { display: none; }
.order-form-item-card { padding: 16px; border: 1px solid var(--line, #e5eaf2); border-radius: 10px; background: #f8faff; }
.order-form-item-card + .order-form-item-card { margin-top: 14px; }
.order-form-item-card header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 17px; }
.order-form-item-card header strong { font-size: 13px; font-weight: 600; }
.order-form-item-card label { display: block; margin-bottom: 8px; font-size: 12px; line-height: 1.5; color: var(--muted, #66758c); }
.mobile-item-values { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 17px; }
.mobile-item-values > div { min-width: 0; }
.mobile-item-values :deep(.el-input-number) { width: 100%; }
.mobile-item-values :deep(.el-input__inner) { font-variant-numeric: tabular-nums; }
.order-form-item-card footer { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--line, #e5eaf2); }
.order-form-item-card footer > span { font-size: 12px; color: var(--muted, #66758c); }
.order-form-item-card footer strong { min-width: 0; overflow-wrap: anywhere; font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; }
@media (max-width: 760px) {
  .order-form-desktop-items { display: none; }
  .order-form-mobile-items { display: block; }
  .order-form-items-total { align-items: center; gap: 12px; }
  .order-form-items-total .grow { display: none; }
  .order-form-items-total > b { margin-left: auto; font-variant-numeric: tabular-nums; }
}
</style>
