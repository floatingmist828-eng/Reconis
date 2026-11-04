<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Upload, Search } from "@element-plus/icons-vue";
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
import ListFeedback from "../components/ListFeedback.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  list = ref<Row>({ items: [], total: 0 }),
  filters = reactive({ q: "", page: 1 }),
  busy = ref(false),
  failed = ref(false),
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
  baselines = ref<Row[]>([]),
  baselinesBusy = ref(false),
  resultsBusy = ref(false),
  comparing = ref(false),
  compared = ref(false),
  changeFilter = ref(""),
  tab = ref("results"),
  pair = ref<Row | null>(null);
const changeLabels: Record<string, string> = {
  RESOLVED: "已匹配",
  NEW: "本次新增",
  REMOVED: "仅基线出现",
  CHANGED: "分类变化",
  UNCHANGED: "无变化",
};
const changeCounts = computed(() => {
  const counts: Record<string, number> = {};
  for (const row of comparison.value) counts[row.changeType] = (counts[row.changeType] || 0) + 1;
  return counts;
});
const visibleComparison = computed(() =>
  changeFilter.value
    ? comparison.value.filter((row) => row.changeType === changeFilter.value)
    : comparison.value,
);
let resultsRequest = 0;
let baselinesRequest = 0;
let compareRequest = 0;
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
  failed.value = false;
  try {
    list.value = await api("/reconciliation/batches?" + query(filters));
  } catch (error) {
    failed.value = true;
    throw error;
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
  results.value = { items: [], total: 0, distribution: [] };
  baseline.value = "";
  baselines.value = [];
  baselinesRequest++;
  baselinesBusy.value = false;
  resetComparison();
  tab.value = "results";
  await loadResults(true);
}
function closeResults() {
  selected.value = null;
  resultsRequest++;
  baselinesRequest++;
  resetComparison();
  resultsBusy.value = false;
  baselinesBusy.value = false;
}
async function filterResults(classification: string) {
  resultFilters.classification = classification;
  resultFilters.page = 1;
  await loadResults();
}
async function loadResults(first = false) {
  const id = selected.value?.id;
  if (!id) return;
  const request = ++resultsRequest;
  resultsBusy.value = true;
  try {
    const data = await api("/reconciliation/batches/" + id + "/results?" + query(resultFilters));
    if (request !== resultsRequest) return;
    results.value = data;
    selected.value = data.batch;
    if (first) baseline.value = data.batch.baselineBatchId || "";
  } finally {
    if (request === resultsRequest) resultsBusy.value = false;
  }
}
async function loadBaselines(q = "") {
  const id = selected.value?.id;
  if (!id) return;
  const request = ++baselinesRequest;
  baselinesBusy.value = true;
  try {
    const options = await api("/reconciliation/batches/" + id + "/baselines?" + query({ q }));
    if (request === baselinesRequest && selected.value?.id === id) baselines.value = options;
  } finally {
    if (request === baselinesRequest) baselinesBusy.value = false;
  }
}
async function run(row: Row, rerun = false) {
  const next = await post(
    `/reconciliation/batches/${row.id}/${rerun ? "rerun" : "run"}`,
  );
  ElMessage.success("对账结果已保存");
  await load();
  await open({ id: next.id });
}
function resetComparison() {
  compareRequest++;
  compared.value = false;
  comparing.value = false;
  comparison.value = [];
  changeFilter.value = "";
}
async function compare() {
  resetComparison();
  const request = compareRequest;
  comparing.value = true;
  try {
    const rows = await api("/reconciliation/compare?" +
      query({ baseline: baseline.value, current: selected.value!.id }));
    if (request !== compareRequest) return;
    comparison.value = rows;
    compared.value = true;
  } finally {
    if (request === compareRequest) comparing.value = false;
  }
}
</script>
<template>
  <div class="reconciliation-workspace">
    <div class="page-heading">
      <h1>账单与对账</h1>
      <div class="heading-actions">
        <el-button @click="router.push('/integration')">{{ session.finance || session.auditor ? "自动对账" : "渠道状态" }}</el-button>
        <el-button v-if="canImport" type="primary" :icon="Upload" @click="startImport">导入渠道账单</el-button>
      </div>
    </div>
    <section class="panel reconciliation-list" v-loading="busy" :aria-busy="busy">
      <div class="table-toolbar">
        <h2>对账批次 <span class="reconciliation-count">{{ list.total }}</span></h2><span class="grow"></span>
        <el-input
          v-model="filters.q"
          :prefix-icon="Search"
          placeholder="搜索批次编号"
          clearable
          aria-label="批次编号"
          @keyup.enter="filters.page = 1; action(load)"
        /><el-button type="primary"
          @click="
            filters.page = 1;
            action(load);
          "
          >查询批次</el-button
        >
      </div>
      <ListFeedback :failed="failed" :busy="busy" :has-previous="!!list.items.length" noun="对账批次" @retry="action(load)" />
      <el-table :data="list.items" class="reconciliation-desktop"
        ><el-table-column label="对账批次" min-width="230"
          ><template #default="{ row }"
            ><b class="mono">{{ row.batchNo }}</b
            ><span class="subline">{{
              row.statementSourceBatchId
                ? "历史重跑"
                : codeName(row.sourceType)
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
        ><template #empty><el-empty :description="busy ? '正在加载批次' : failed ? '批次暂不可用' : filters.q ? '没有找到该批次' : '尚未导入账单'" :image-size="68"><el-button v-if="filters.q && !busy && !failed" @click="filters.q = ''; filters.page = 1; action(load)">清除筛选</el-button><el-button v-else-if="canImport && !busy && !failed" @click="startImport">导入渠道账单</el-button></el-empty></template></el-table
      >
      <div class="reconciliation-mobile">
        <article v-for="row in list.items" :key="row.id" class="batch-mobile-row">
          <header><b>{{ row.merchantName || '平台待核验归属' }}</b><StatusTag :value="row.status" /></header>
          <p class="batch-mobile-period">{{ time(row.periodStart).split(' ')[0] }} — {{ time(new Date(new Date(row.periodEnd).getTime() - 1).toISOString()).split(' ')[0] }}</p>
          <p class="batch-mobile-number">{{ row.batchNo }}</p>
          <div class="batch-mobile-tags"><StatusTag :value="row.completeness" /><span>{{ row.statementSourceBatchId ? '历史重跑' : codeName(row.sourceType) }}</span></div>
          <footer><el-button type="primary" plain @click="action(() => open(row))">查看结果</el-button><el-button v-if="canRun(row) && ['READY', 'FAILED'].includes(row.status)" @click="action(() => run(row))">执行对账</el-button><el-button v-if="canRun(row) && row.status === 'COMPLETED'" @click="action(() => run(row, true))">重新核对</el-button></footer>
        </article>
        <el-empty v-if="!list.items.length" :description="busy ? '正在加载批次' : failed ? '批次暂不可用' : filters.q ? '没有找到该批次' : '尚未导入账单'" :image-size="68"><el-button v-if="filters.q && !busy && !failed" @click="filters.q = ''; filters.page = 1; action(load)">清除筛选</el-button><el-button v-else-if="canImport && !busy && !failed" @click="startImport">导入渠道账单</el-button></el-empty>
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
        按当前交易信息重新识别账单归属，保留原始账单与历史结果。
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
      @close="closeResults"
      ><div v-if="selected" v-loading="resultsBusy" :aria-busy="resultsBusy" class="reconciliation-detail">
        <div class="table-toolbar">
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
            v-if="!resultsBusy && canRun(selected) && selected.status === 'COMPLETED'"
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
        <div v-if="results.distribution.length" class="reconciliation-metrics" aria-label="按对账分类筛选">
          <button
            v-for="g in results.distribution"
            :key="g.classification"
            type="button"
            :aria-pressed="tab === 'results' && resultFilters.classification === g.classification"
            @click="tab = 'results'; action(() => filterResults(resultFilters.classification === g.classification ? '' : g.classification))"
          >
            <StatusTag :value="g.classification" />
            <strong>{{ g.count }}</strong>
          </button>
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
        <el-tabs v-model="tab" @tab-change="(name: string | number) => name === 'compare' && action(() => loadBaselines())"
          ><el-tab-pane label="逐笔对账结果" name="results"
            ><div class="table-toolbar">
              <el-select
                v-model="resultFilters.classification"
                placeholder="全部分类"
                clearable
                @change="action(() => filterResults(resultFilters.classification))"
                ><el-option
                  v-for="c in classifications"
                  :key="c"
                  :value="c"
                  :label="codeName(c)" /></el-select
              ><span class="muted">点击查看两侧明细</span
              ><span class="grow"></span
              ><el-button link @click="router.push('/cases')"
                >差错工单 →</el-button
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
                min-width="160"
            ><template #default="{row}">{{codeName(row.reasonCode)}}</template></el-table-column></el-table>
            <div class="pagination">
              <el-pagination
                v-model:current-page="resultFilters.page"
                :total="Number(results.total)"
                :page-size="30"
                layout="total,prev,pager,next"
                @current-change="action(loadResults)"
              /></div></el-tab-pane
          ><el-tab-pane label="历史批次比较" name="compare" :disabled="selected.status !== 'COMPLETED'">
            <div class="table-toolbar">
              <el-select
                v-model="baseline"
                class="baseline-select"
                placeholder="选择同一归属范围的基线批次"
                aria-label="基线批次"
                filterable
                remote
                :loading="baselinesBusy"
                :remote-method="(q: string) => action(() => loadBaselines(q))"
                no-data-text="没有可比较的已完成批次"
                @change="resetComparison"
              >
                <el-option v-for="b in baselines" :key="b.id" :label="b.batchNo" :value="b.id">
                  <span>{{ b.batchNo }}</span><span class="baseline-date">{{ time(b.periodStart).split(' ')[0] }}</span>
                </el-option>
              </el-select>
              <el-button type="primary" :disabled="!baseline" :loading="comparing" @click="action(compare)">比较两个运行</el-button>
            </div>
            <template v-if="compared">
              <div class="comparison-filters" aria-label="筛选结果变化">
                <button type="button" :aria-pressed="changeFilter === ''" @click="changeFilter = ''">全部 <b>{{ comparison.length }}</b></button>
                <button v-for="(label, key) in changeLabels" :key="key" type="button" :aria-pressed="changeFilter === key" @click="changeFilter = key">{{ label }} <b>{{ changeCounts[key] || 0 }}</b></button>
              </div>
              <el-table :data="visibleComparison" max-height="440" empty-text="没有符合条件的交易">
                <el-table-column label="事件键" prop="eventKey" min-width="200" show-overflow-tooltip />
                <el-table-column label="基线分类" width="140"><template #default="{ row }"><StatusTag :value="row.beforeClassification" /></template></el-table-column>
                <el-table-column label="当前分类" width="140"><template #default="{ row }"><StatusTag :value="row.afterClassification" /></template></el-table-column>
                <el-table-column label="变化" width="120"><template #default="{ row }">{{ changeLabels[row.changeType] }}</template></el-table-column>
              </el-table>
            </template>
          </el-tab-pane>
        </el-tabs>
      </div>
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
<style scoped>
.reconciliation-count { color: #5d7399; background: #f2f5fc; padding: 6px 10px; border-radius: 6px; font-size: 12px; white-space: nowrap; }
.reconciliation-list { padding: 0; overflow: hidden; }
.reconciliation-list > .table-toolbar { padding: 24px 26px; margin: 0; }
.reconciliation-list > .table-toolbar h2 { font-size: 17px; display: flex; align-items: center; gap: 10px; }
.reconciliation-list > .table-toolbar :deep(.el-input) { width: 250px; }
.reconciliation-list > .pagination { margin: 0; padding: 20px 26px; border-top: 1px solid var(--line); }
.reconciliation-desktop :deep(.cell) { padding-inline: 22px; }
.reconciliation-desktop :deep(td.el-table__cell) { height: 86px; }
.reconciliation-desktop :deep(.el-table__header-wrapper) { border-radius: 0; }
.reconciliation-mobile { display: none; }
.batch-mobile-row { padding: 22px 20px; border-top: 1px solid var(--line); }
.batch-mobile-row header { display: flex; gap: 12px; align-items: flex-start; }
.batch-mobile-row header b { min-width: 0; flex: 1; overflow-wrap: anywhere; font-size: 15px; line-height: 1.7; }
.batch-mobile-row header :deep(.el-tag) { flex: none; }
.batch-mobile-period { font-size: 14px; font-weight: 500; margin: 13px 0 7px; font-variant-numeric: tabular-nums; }
.batch-mobile-number { margin: 0; font-size: 11px; color: var(--muted); overflow-wrap: anywhere; }
.batch-mobile-tags { margin-top: 16px; display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--muted); }
.batch-mobile-row footer { display: flex; gap: 8px; margin-top: 19px; flex-wrap: wrap; }

@media (max-width: 760px) { .reconciliation-desktop { display: none; } .reconciliation-mobile { display: block; } .reconciliation-list > .table-toolbar { padding: 20px; } .reconciliation-list > .table-toolbar h2 { flex-basis: 100%; margin-bottom: 5px; } .reconciliation-list > .table-toolbar :deep(.el-input) { width: auto; flex: 1; min-width: 0; } .reconciliation-list > .table-toolbar > .grow { display: none; } .reconciliation-list > .pagination { padding: 20px 14px; } }

.reconciliation-detail { min-height: 140px; }
.reconciliation-metrics, .comparison-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 18px 0 24px;
}
.reconciliation-metrics button, .comparison-filters button {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
  font-variant-numeric: tabular-nums;
  transition: background .16s, border-color .16s;
}
.reconciliation-metrics button:hover, .comparison-filters button:hover,
.reconciliation-metrics button[aria-pressed="true"], .comparison-filters button[aria-pressed="true"] {
  border-color: #aebfee;
  background: #f1f5ff;
  color: var(--brand);
}
.comparison-filters b { font-size: 12px; }
.table-toolbar .baseline-select { width: min(100%, 340px); }
.baseline-date { float: right; padding-left: 12px; font-size: 12px; color: var(--muted); }
@media (prefers-reduced-motion: reduce) {
  .reconciliation-metrics button, .comparison-filters button { transition: none; }
}
</style>
