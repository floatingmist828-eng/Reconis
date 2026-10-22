<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import {
  codeName,
  api,
  post,
  query,
  money,
  time,
  dateDays,
  exportFile,
  action,
  type Row,
} from "../api";
import { useSession } from "../stores/session";
import StatusTag from "../components/StatusTag.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  list = ref<Row>({ items: [], total: 0 }),
  filters = reactive({ q: "", page: 1 }),
  busy = ref(false),
  importing = ref(false),
  merchants = ref<Row[]>([]),
  file = ref<File>(),
  preview = ref<Row | null>(null),
  importBusy = ref(false);
const importScope = ref("MERCHANT"),
  identifyBatch = ref<Row | null>(null),
  importDone = ref(false);
const canImport = computed(
  () =>
    session.finance ||
    session.user?.memberships?.some((m: Row) =>
      session.can(m.merchantId, ["OWNER", "FINANCE"]),
    ),
);
const form = reactive({
    merchantId: "",
    start: dateDays(-29),
    end: dateDays(),
    completeness: "UNKNOWN",
  }),
  selected = ref<Row | null>(null),
  results = ref<Row>({ items: [], total: 0, distribution: [] }),
  resultFilters = reactive({ classification: "", page: 1 }),
  comparison = ref<Row[]>([]),
  baseline = ref(""),
  tab = ref("results"),
  pair = ref<Row | null>(null);
watch(
  form,
  () => {
    preview.value = null;
    importDone.value = false;
  },
  { deep: true },
);
watch(importScope, () => {
  preview.value = null;
  importDone.value = false;
});
function canRun(batch: Row) {
  return batch.merchantId
    ? session.can(batch.merchantId, ["OWNER", "FINANCE"])
    : session.finance;
}
function identifiedBatches(event: Row): Row[] {
  try {
    return JSON.parse(event.afterJson)?.batches || [];
  } catch {
    return [];
  }
}
function startImport() {
  identifyBatch.value = null;
  preview.value = null;
  importDone.value = false;
  importing.value = true;
}
async function identify(batch: Row) {
  identifyBatch.value = batch;
  preview.value = null;
  importDone.value = false;
  importing.value = true;
  await upload(true);
}
const classifications = [
  "MATCHED",
  "CHANNEL_ONLY",
  "LOCAL_ONLY",
  "AMOUNT_MISMATCH",
  "BILL_DUPLICATE",
  "KEY_UNRESOLVED",
  "PENDING_CONFIRMATION",
  "EXTRA_SUCCESS",
];
async function load() {
  busy.value = true;
  try {
    list.value = await api("/reconciliation/batches?" + query(filters));
  } finally {
    busy.value = false;
  }
}
onMounted(() =>
  action(async () => {
    await load();
    merchants.value = (
      await api("/merchants?status=ACTIVE&pageSize=100")
    ).items;
    form.merchantId = merchants.value[0]?.id || "";
    if (route.query.batchId) await open({ id: route.query.batchId });
  }),
);
async function upload(isPreview: boolean) {
  if (
    !identifyBatch.value &&
    (!file.value || (importScope.value === "MERCHANT" && !form.merchantId))
  )
    return ElMessage.warning("请选择商户与账单文件");
  const b = new FormData();
  if (file.value) b.append("file", file.value);
  Object.entries(form).forEach(([k, v]) => {
    if (k !== "merchantId" || importScope.value === "MERCHANT") b.append(k, v);
  });
  b.append("preview", String(isPreview));
  importBusy.value = true;
  try {
    preview.value = identifyBatch.value
      ? await post(
          `/reconciliation/batches/${identifyBatch.value.id}/identify?preview=${isPreview}`,
        )
      : await post("/reconciliation/imports", b);
    if (!isPreview) {
      importDone.value = true;
      ElMessage.success(
        preview.value!.reused
          ? "原件、账期和解析条件相同，已返回现有批次"
          : `已按归属保存 ${preview.value!.batches.length} 个批次，可分别执行对账`,
      );
      await load();
      if (preview.value!.batches?.length === 1) {
        importing.value = false;
        await open(preview.value!.batch);
      }
    }
  } finally {
    importBusy.value = false;
  }
}
async function open(row: Row) {
  selected.value = row;
  resultFilters.page = 1;
  resultFilters.classification = "";
  tab.value = "results";
  await loadResults();
}
async function loadResults() {
  results.value = await api(
    `/reconciliation/batches/${selected.value!.id}/results?` +
      query(resultFilters),
  );
  selected.value = results.value.batch;
  baseline.value = selected.value!.baselineBatchId || "";
}
async function run(row: Row, rerun = false) {
  const next = await post(
    `/reconciliation/batches/${row.id}/${rerun ? "rerun" : "run"}`,
  );
  ElMessage.success("对账结果已保存");
  await load();
  await open({ id: next.id });
}
async function compare() {
  comparison.value = await api(
    "/reconciliation/compare?" +
      query({ baseline: baseline.value, current: selected.value!.id }),
  );
  tab.value = "compare";
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">RECONCILIATION WORKSPACE</div>
        <h1>账单与对账</h1>
        <p>导入账单，核对差异，查看每次结果。</p>
      </div>
      <el-button v-if="canImport" type="primary" @click="startImport"
        >＋ 导入渠道账单</el-button
      >
    </div>
    <section class="panel" v-loading="busy">
      <div class="table-toolbar">
        <el-input
          v-model="filters.q"
          placeholder="搜索批次编号"
          clearable
          @keyup.enter="action(load)"
        /><el-button
          @click="
            filters.page = 1;
            action(load);
          "
          >查询批次</el-button
        ><span class="grow"></span
        ><span class="muted">{{ list.total }} 个历史批次</span>
      </div>
      <el-table :data="list.items"
        ><el-table-column label="对账批次" min-width="230"
          ><template #default="{ row }"
            ><b class="mono">{{ row.batchNo }}</b
            ><span class="subline">{{
              row.statementSourceBatchId
                ? "复用原始输入 · 历史重跑"
                : "首次导入 · " + row.sourceType
            }}</span></template
          ></el-table-column
        ><el-table-column label="归属范围" min-width="135"
          ><template #default="{ row }">{{
            row.merchantName || "平台待核验归属"
          }}</template> </el-table-column
        ><el-table-column label="账期（北京时间）" min-width="190"
          ><template #default="{ row }"
            ><span class="mono"
              >{{ time(row.periodStart).split(" ")[0] }} —
              {{
                time(
                  new Date(new Date(row.periodEnd).getTime() - 1).toISOString(),
                ).split(" ")[0]
              }}</span
            ><span class="subline"
              ><StatusTag
                :value="row.completeness" /></span></template></el-table-column
        ><el-table-column label="状态" width="105"
          ><template #default="{ row }"
            ><StatusTag :value="row.status" /></template></el-table-column
        ><el-table-column label="操作" width="190"
          ><template #default="{ row }"
            ><div class="row-actions">
              <el-button link type="primary" @click="action(() => open(row))"
                >查看结果</el-button
              ><el-button
                v-if="canRun(row) && ['READY', 'FAILED'].includes(row.status)"
                link
                @click="action(() => run(row))"
                >执行对账</el-button
              ><el-button
                v-if="canRun(row) && row.status === 'COMPLETED'"
                link
                @click="action(() => run(row, true))"
                >重新核对</el-button
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
    <el-dialog
      v-model="importing"
      :title="identifyBatch ? '重新识别账单归属' : '导入渠道账单'"
      width="880"
      ><div v-if="!identifyBatch" class="row-actions">
        <a href="/api/v1/templates?format=xlsx"
          ><el-button>下载 Excel 模板 ↓</el-button></a
        ><a href="/api/v1/templates?format=xml"
          ><el-button>下载 XML 模板 ↓</el-button></a
        >
      </div>
      <div v-if="identifyBatch" class="help-note">
        重新读取原件并核对当前交易关联，创建或复用独立输入批次；历史账单、对账结果与工单保持可追溯。
      </div>
      <div v-else class="help-note">
        支持标准 XLSX/XML（金额为分、时间带时区），以及支付宝中文交易明细
        CSV/ZIP（订单金额为元、完成时间为北京时间）。不接受资金账单或单独汇总表。导入当前
        {{ session.integration.environment }} 环境。
      </div>
      <el-form v-if="!identifyBatch" label-position="top"
        ><div class="form-grid">
          <el-form-item v-if="session.finance" label="归属识别方式">
            <el-select v-model="importScope">
              <el-option label="指定单个商户并校验" value="MERCHANT" />
              <el-option label="平台混合账单 · 自动拆分归属" value="AUTO" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="importScope === 'MERCHANT'" label="所属商户"
            ><el-select v-model="form.merchantId" @change="preview = null"
              ><el-option
                v-for="m in merchants"
                :key="m.id"
                :label="m.name + ' · ' + m.merchantCode"
                :value="m.id" /></el-select></el-form-item
          ><el-form-item label="输入完整性"
            ><el-select v-model="form.completeness"
              ><el-option label="未知 / 不完整" value="UNKNOWN" /><el-option
                label="操作人声明完整"
                value="DECLARED_COMPLETE" /></el-select></el-form-item
          ><el-form-item label="开始业务日"
            ><el-date-picker
              v-model="form.start"
              type="date"
              value-format="YYYY-MM-DD"
              @change="preview = null" /></el-form-item
          ><el-form-item label="结束业务日（包含）"
            ><el-date-picker
              v-model="form.end"
              type="date"
              value-format="YYYY-MM-DD"
              @change="preview = null"
          /></el-form-item>
        </div>
        <el-form-item label="选择 XLSX / XML / 支付宝 CSV / ZIP（不超过20MB）"
          ><input
            type="file"
            accept=".xlsx,.xml,.csv,.zip"
            @change="
              (e: Event) => {
                file = (e.target as HTMLInputElement).files?.[0];
                preview = null;
                importDone = false;
              }
            " /></el-form-item></el-form
      ><template v-if="preview"
        ><div class="metric-inline">
          <div>
            <small>原始行数</small><strong>{{ preview.rows }}</strong>
          </div>
          <div>
            <small>有效行数</small><strong>{{ preview.accepted }}</strong>
          </div>
          <div>
            <small>重复业务键</small><strong>{{ preview.duplicates }}</strong>
          </div>
          <div>
            <small>错误行</small><strong>{{ preview.errors.length }}</strong>
          </div>
        </div>
        <el-alert
          v-if="preview.emptyComplete"
          type="warning"
          :closable="false"
          title="本文件只有合法表头，没有渠道交易。你已声明账期完整，执行对账将把对应范围内所有本地流水判为本地单边，请确认日期和完整性。" />
        <el-alert
          v-if="preview.unassigned"
          type="warning"
          :closable="false"
          :title="`${preview.unassigned} 行无法通过原支付或退款识别商户，将进入平台待核验范围；由平台财务运行对账并分派工单。`" />
        <el-table
          :data="preview.batches || preview.partitions"
          max-height="230"
        >
          <el-table-column label="归属" prop="merchantName" min-width="160" />
          <el-table-column label="有效行" prop="rows" width="85" />
          <el-table-column
            v-if="importDone"
            label="批次"
            prop="batchNo"
            min-width="220"
          />
          <el-table-column v-if="importDone" label="操作" width="110"
            ><template #default="{ row }">
              <el-button
                link
                type="primary"
                @click="
                  action(async () => {
                    importing = false;
                    await open(row);
                  })
                "
                >查看批次</el-button
              >
            </template></el-table-column
          >
        </el-table>
        <el-table
          v-if="preview.errors.length"
          :data="preview.errors"
          max-height="210"
          ><el-table-column
            v-if="preview.errors.some((e: Row) => e.sourceFile)"
            label="文件 / 文件行"
            min-width="160"
          >
            <template #default="{ row }"
              >{{ row.sourceFile }} : {{ row.sourceLine }}</template
            > </el-table-column
          ><el-table-column
            label="原行号"
            prop="line"
            width="90" /><el-table-column
            label="错误原因"
            prop="reason" /></el-table></template
      ><template #footer
        ><el-button :loading="importBusy" @click="action(() => upload(true))"
          >预览与校验</el-button
        ><el-button
          type="primary"
          :loading="importBusy"
          :disabled="!preview?.canImport || importDone"
          @click="action(() => upload(false))"
          >{{
            preview?.emptyComplete ? "确认导入完整空账单" : "导入有效原始行"
          }}</el-button
        ></template
      ></el-dialog
    >
    <el-drawer
      :model-value="!!selected"
      :title="'对账结果 · ' + (selected?.batchNo || '')"
      size="1100px"
      @close="selected = null"
      ><template v-if="selected"
        ><div class="table-toolbar">
          <StatusTag :value="selected.status" /><span class="muted"
            >{{
              selected.merchantName ||
              (selected.merchantId ? "商户批次" : "平台待核验归属")
            }}
            · {{ time(selected.observedAt) }}</span
          ><span class="grow"></span
          ><el-button
            v-if="
              canRun(selected) && ['READY', 'FAILED'].includes(selected.status)
            "
            type="primary"
            @click="action(() => run(selected!))"
            >执行对账</el-button
          ><el-button
            v-if="canRun(selected) && selected.status === 'COMPLETED'"
            @click="action(() => run(selected!, true))"
            >创建重跑批次</el-button
          ><el-button
            v-if="
              session.finance &&
              selected.scopeType === 'PLATFORM_UNASSIGNED' &&
              selected.sourceFileUri
            "
            @click="action(() => identify(selected!))"
            >重新识别归属</el-button
          ><el-dropdown
            @command="
              (v: string) =>
                action(() =>
                  exportFile('reconciliation', v, { batchId: selected!.id }),
                )
            "
            ><el-button>导出结果 ↓</el-button
            ><template #dropdown
              ><el-dropdown-menu
                ><el-dropdown-item command="xlsx">Excel</el-dropdown-item
                ><el-dropdown-item command="xml"
                  >XML</el-dropdown-item
                ></el-dropdown-menu
              ></template
            ></el-dropdown
          >
        </div>
        <div class="metric-inline">
          <div v-for="g in results.distribution" :key="g.classification">
            <StatusTag :value="g.classification" /><strong>{{
              g.count
            }}</strong>
          </div>
        </div>
        <section v-if="results.identifications?.length" class="help-note">
          <b>归属重新识别记录（最近20次）</b>
          <div v-for="event in results.identifications" :key="event.id">
            {{ time(event.occurredAt) }} · {{ event.actorName }} →
            <el-button
              v-for="batch in identifiedBatches(event)"
              :key="batch.id"
              link
              type="primary"
              @click="action(() => open(batch))"
              >{{ batch.merchantName }} · {{ batch.batchNo }}</el-button
            >
          </div>
        </section>
        <el-tabs v-model="tab"
          ><el-tab-pane label="逐笔对账结果" name="results"
            ><div class="table-toolbar">
              <el-select
                v-model="resultFilters.classification"
                placeholder="全部分类"
                clearable
                @change="
                  resultFilters.page = 1;
                  action(loadResults);
                "
                ><el-option
                  v-for="c in classifications"
                  :key="c"
                  :value="c"
                  :label="codeName(c)" /></el-select
              ><span class="muted">点击查看两侧明细</span
              ><span class="grow"></span
              ><el-button link @click="router.push('/cases')"
                >查看关联差错工单 →</el-button
              >
            </div>
            <el-table
              :data="results.items"
              class="clickable"
              @row-click="(r: Row) => (pair = r)"
              ><el-table-column
                label="标准事件键"
                prop="sourceEventKey"
                min-width="210"
                show-overflow-tooltip /><el-table-column
                label="本地金额"
                align="right"
                width="115"
                ><template #default="{ row }">{{
                  row.localAmountCent ? "¥ " + money(row.localAmountCent) : "—"
                }}</template></el-table-column
              ><el-table-column label="渠道金额" align="right" width="115"
                ><template #default="{ row }">{{
                  row.channelAmountCent
                    ? "¥ " + money(row.channelAmountCent)
                    : "—"
                }}</template></el-table-column
              ><el-table-column label="分类" width="130"
                ><template #default="{ row }"
                  ><StatusTag
                    :value="row.classification" /></template></el-table-column
              ><el-table-column
                label="判定原因"
                prop="reasonCode"
                min-width="160"
            /></el-table>
            <div class="pagination">
              <el-pagination
                v-model:current-page="resultFilters.page"
                :total="Number(results.total)"
                :page-size="30"
                layout="total,prev,pager,next"
                @current-change="action(loadResults)"
              /></div></el-tab-pane
          ><el-tab-pane label="历史批次比较" name="compare"
            ><div class="table-toolbar">
              <el-select
                v-model="baseline"
                placeholder="选择同一归属范围的基线批次"
                style="width: 350px"
                ><el-option
                  v-for="b in list.items.filter(
                    (b: Row) =>
                      b.id !== selected!.id &&
                      b.merchantId === selected!.merchantId &&
                      b.scopeType === selected!.scopeType &&
                      b.status === 'COMPLETED',
                  )"
                  :key="b.id"
                  :label="b.batchNo"
                  :value="b.id" /></el-select
              ><el-button :disabled="!baseline" @click="action(compare)"
                >比较两个运行</el-button
              >
            </div>
            <el-table :data="comparison"
              ><el-table-column
                label="事件键"
                prop="eventKey"
                min-width="200" /><el-table-column label="基线分类" width="150"
                ><template #default="{ row }"
                  ><StatusTag
                    :value="
                      row.beforeClassification
                    " /></template></el-table-column
              ><el-table-column label="当前分类" width="150"
                ><template #default="{ row }"
                  ><StatusTag
                    :value="
                      row.afterClassification
                    " /></template></el-table-column
              ><el-table-column label="变化" prop="changeType" width="120"
            /></el-table>
            <div class="help-note">
              选择历史批次，查看同一笔交易的结果变化。
            </div></el-tab-pane
          ></el-tabs
        ></template
      ></el-drawer
    >
    <el-dialog
      :model-value="!!pair"
      title="交易核对"
      width="780"
      @close="pair = null"
      ><template v-if="pair"
        ><div class="table-toolbar">
          <StatusTag :value="pair.classification" /><span class="mono">{{
            pair.sourceEventKey
          }}</span>
        </div>
        <div class="comparison">
          <section>
            <h3>本地流水</h3>
            <dl>
              <dt>流水 ID</dt>
              <dd>{{ pair.entryId || "当前批次未观察到" }}</dd>
              <dt>金额</dt>
              <dd>
                {{
                  pair.localAmountCent
                    ? "¥ " + money(pair.localAmountCent)
                    : "—"
                }}
              </dd>
              <dt>订单</dt>
              <dd>
                <el-button
                  v-if="pair.orderId"
                  link
                  type="primary"
                  @click="router.push('/orders/' + pair.orderId)"
                  >{{ pair.orderNo }} ↗</el-button
                ><span v-else>—</span>
              </dd>
              <dt>发生时间</dt>
              <dd>{{ time(pair.localOccurredAt) }}</dd>
            </dl>
          </section>
          <section class="channel">
            <h3>渠道原始账单</h3>
            <dl>
              <dt>原始行 ID</dt>
              <dd>{{ pair.statementLineId || "无对应原行" }}</dd>
              <dt>金额</dt>
              <dd>
                {{
                  pair.channelAmountCent
                    ? "¥ " + money(pair.channelAmountCent)
                    : "—"
                }}
              </dd>
              <dt>支付 / 退款号</dt>
              <dd>{{ pair.outTradeNo || pair.outRequestNo || "—" }}</dd>
              <dt>发生时间</dt>
              <dd>{{ time(pair.channelOccurredAt) }}</dd>
            </dl>
          </section>
        </div>
        <div class="help-note">{{ codeName(pair.reasonCode) }}</div></template
      ></el-dialog
    >
  </div>
</template>
