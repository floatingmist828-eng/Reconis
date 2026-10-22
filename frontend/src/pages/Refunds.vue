<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessageBox, ElMessage } from "element-plus";
import { api, post, query, money, time, action, type Row } from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
const router = useRouter(),
  session = useSession(),
  list = ref<Row>({ items: [], total: 0 }),
  filters = reactive({ q: "", status: "", page: 1 }),
  busy = ref(false);
async function load() {
  busy.value = true;
  try {
    list.value = await api("/refunds?" + query(filters));
  } finally {
    busy.value = false;
  }
}
onMounted(() => action(load));
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
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">REFUND OPERATIONS</div>
        <h1>退款工作台</h1>
        <p>查看申请，审批退款，跟进处理结果。</p>
      </div>
      <el-button @click="action(load)">刷新状态</el-button>
    </div>
    <div class="help-note">待确认的退款可点击“查询恢复”更新进度。</div>
    <section class="panel" v-loading="busy">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          placeholder="退款编号 / 申请原因"
          clearable
          @keyup.enter="action(load)"
        /><el-select v-model="filters.status" placeholder="全部状态" clearable
          ><el-option
            v-for="(label, value) in {
              PENDING_REVIEW: '待审核',
              APPROVED: '已批准',
              PROCESSING: '处理中',
              UNKNOWN: '待确认',
              SUCCEEDED: '已成功',
              REJECTED: '已退回',
            }"
            :key="value"
            :label="label"
            :value="value" /></el-select
        ><el-button
          @click="
            filters.page = 1;
            action(load);
          "
          >查询</el-button
        ><span class="grow"></span
        ><span class="muted">{{ list.total }} 笔退款</span>
      </div>
      <el-table :data="list.items"
        ><el-table-column label="退款与订单" min-width="230"
          ><template #default="{ row }"
            ><b class="mono">{{ row.outRequestNo }}</b
            ><span class="subline"
              ><el-button link @click="router.push('/orders/' + row.orderId)"
                >{{ row.orderNo }} ↗</el-button
              ></span
            ></template
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
        ></el-table
      >
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
