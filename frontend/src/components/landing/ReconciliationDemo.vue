<script setup lang="ts">
import { computed, nextTick, ref } from "vue";

const step = ref(0);
const stepControls = ref<HTMLElement | null>(null);
const steps = [
  { title: "导入账单", description: "上传渠道账单，预览并校验原始记录。", action: "核对记录" },
  { title: "核对记录", description: "按交易标识匹配，再逐笔比对金额。", action: "查看差异" },
  { title: "发现差异", description: "本地 24 元，渠道 24.50 元，生成金额差异工单。", action: "查看处理进度" },
  { title: "分派与复核", description: "经办人提交依据，由另一人复核。", action: "重新浏览" },
] as const;
const current = computed(() => steps[step.value]!);
const entries = [
  { id: "001", kind: "收款", detail: "09:12", local: "36.00", channel: "36.00", difference: false },
  { id: "002", kind: "收款", detail: "11:23", local: "24.00", channel: "24.50", difference: true },
  { id: "003", kind: "收款", detail: "12:06", local: "18.00", channel: "18.00", difference: false },
  { id: "R001", kind: "退款", detail: "原订单 001", local: "−12.00", channel: "−12.00", difference: false },
] as const;
const summary = computed(() => step.value === 0 ? "4 笔记录，等待核对" : "3 笔匹配，1 笔差异");
function next() { step.value = (step.value + 1) % steps.length; }
async function inspectDifference() {
  step.value = 2;
  await nextTick();
  if (step.value === 2) {
    stepControls.value?.querySelector<HTMLButtonElement>('[data-step-control="2"]')?.focus({ preventScroll: true });
  }
}
</script>

<template>
  <div class="recon-demo" data-testid="reconciliation-demo">
    <div ref="stepControls" class="demo-steps" role="group" aria-label="浏览对账流程">
      <button v-for="(item, index) in steps" :key="item.title" type="button"
        :data-step-control="index" :aria-label="`0${index + 1} ${item.title}`" :aria-pressed="step === index" :class="{ active: step === index, visited: step > index }"
        @click="step = index">
        <span class="step-number">0{{ index + 1 }}</span><span>{{ item.title }}</span>
        <svg v-if="step > index" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m3.5 8 3 3 6-6" /></svg>
      </button>
    </div>

    <div class="demo-scene" data-testid="demo-scene" :data-step="step">
      <div class="demo-ledger">
        <div class="ledger-heading">
          <div class="ledger-title"><svg viewBox="0 0 22 22" fill="none" aria-hidden="true"><path d="M4 6h14M4 11h14M4 16h8"/><path d="m15 15 2 2 3-4"/></svg><div><strong>海棠咖啡店<span class="context-divider">/</span>图书馆旁</strong><span class="ledger-subtitle">10 月 20 日 · {{ summary }}</span></div></div>
          <span class="sample-marker">示例</span>
        </div>

        <div class="ledger-table-wrap">
          <table class="ledger-table" aria-label="四笔示例资金记录比较">
            <thead><tr><th scope="col">资金记录</th><th scope="col">本地流水</th><th scope="col">渠道账单</th><th scope="col">核对结果</th></tr></thead>
            <tbody>
              <tr v-for="entry in entries" :key="entry.id" :class="{ 'difference-row': entry.difference && step >= 2 }">
                <td><span class="entry-identity"><span>{{ entry.kind }}</span><span class="entry-id">{{ entry.id }}</span></span><small>{{ entry.detail }}</small></td>
                <td class="ledger-amount">{{ entry.local }}</td>
                <td class="ledger-amount" :class="{ 'different-amount': entry.difference && step >= 2 }">{{ entry.channel }}</td>
                <td><span v-if="step === 0" class="row-result pending"><i />待核对</span><button v-else-if="entry.difference && step === 1" type="button" class="inspect-difference" @click="inspectDifference">查看差异<svg viewBox="0 0 12 12" fill="none" aria-hidden="true"><path d="m4.5 3 3 3-3 3" /></svg></button><span v-else-if="entry.difference" class="row-result difference"><i />金额差异</span><span v-else class="row-result matched"><svg viewBox="0 0 12 12" fill="none" aria-hidden="true"><path d="m2.5 6 2.5 2.5L9.5 4" /></svg>匹配</span></td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="ledger-totals"><div><span>本地净收款</span><strong><span>¥</span> 66.00</strong></div><div><span>渠道净额</span><strong><span>¥</span> 66.50</strong></div><p>金额单位：元<br>净额 = 收款 − 退款</p></div>
      </div>

      <aside class="demo-inspector" :aria-label="current.title + '详情'">
        <div class="inspector-top"><span>账单明细</span><span class="inspector-indicator" /></div>
        <div :key="step" class="inspector-content">
          <template v-if="step === 0">
            <svg class="inspector-symbol" viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M9 4h10l6 6v18H9z"/><path d="M19 4v7h6M5 10v16M13 16h8M13 21h6"/></svg>
            <h3>账单已准备好</h3><p class="inspector-intro">预览原始记录，再开始核对。</p>
            <div class="file-detail"><svg viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M4 2h7l3 3v11H4zM10 2v4h4M6.5 9h5M6.5 12h5"/></svg><span>海棠咖啡店_1020.xlsx<small>4 笔有效记录 · 0 行错误</small></span></div>
            <div class="inspector-note"><span class="check-circle">✓</span>商户与退款关联已校验</div>
          </template>
          <template v-else-if="step === 1">
            <svg class="inspector-symbol" viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M4 10h24M4 22h24M21 5l5 5-5 5M11 17l-5 5 5 5"/></svg>
            <h3>逐笔核对</h3><p class="inspector-intro">按交易标识匹配，再比较金额。</p>
            <dl class="inspection-counts"><div><dt><i class="matched-dot" />匹配</dt><dd>3 <span>笔</span></dd></div><div><dt><i class="difference-dot" />金额差异</dt><dd>1 <span>笔</span></dd></div></dl>
            <div class="inspector-note">核对结果已保存，可查看差异。</div>
          </template>
          <template v-else-if="step === 2">
            <div class="inspector-reference"><span>收款 002</span><span>金额差异</span></div>
            <h3 class="difference-value"><span>¥</span> 0.50</h3><p class="inspector-intro">渠道金额高于本地流水</p>
            <dl class="difference-breakdown"><div><dt>本地流水</dt><dd>¥ 24.00</dd></div><div><dt>渠道账单</dt><dd>¥ 24.50</dd></div></dl>
            <div class="case-created"><svg viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M5 3H3v13h12V3h-2M6 2h6v3H6zM6 9h6M6 12h4"/></svg><div>已生成差错工单<small>等待分派处理人</small></div></div>
          </template>
          <template v-else>
            <div class="inspector-reference"><span>收款 002</span><span>差错工单</span></div>
            <h3>待独立复核</h3><p class="inspector-intro">处理依据已保存，等待复核意见。</p>
            <ol class="case-timeline"><li class="complete"><i>✓</i><div>分派处理人<small>由财务指定经办人员</small></div></li><li class="complete"><i>✓</i><div>记录分析与证据<small>已保存账单与处理说明</small></div></li><li class="current"><i /><div>提交独立复核<small>由其他人员审核</small></div></li></ol>
            <p class="history-note">原始账单与核对结果保留。</p>
          </template>
        </div>
      </aside>
    </div>

    <div class="demo-controls"><p aria-live="polite"><span class="sr-only">{{ summary }}。</span>{{ current.description }}</p><button type="button" class="next-step" @click="next">{{ current.action }}<svg viewBox="0 0 18 18" fill="none" aria-hidden="true"><path d="M3 9h11M10 5l4 4-4 4" /></svg></button></div>
  </div>
</template>

<style scoped>
.recon-demo { --demo-ink:var(--public-text); --demo-muted:var(--public-muted); --demo-blue:var(--public-accent); --demo-line:var(--public-line); color:var(--demo-ink); background:var(--public-panel); border:1px solid rgba(222,231,249,.14); border-radius:15px; overflow:hidden; box-shadow:0 20px 70px #0003,0 1px 0 #ffffff08 inset; font-size:13px; text-align:left; }
.recon-demo *, .recon-demo *::before, .recon-demo *::after { box-sizing:border-box; }
.recon-demo button { font:inherit; cursor:pointer; -webkit-tap-highlight-color:transparent; }
.recon-demo button:focus-visible { outline:2px solid var(--demo-blue); outline-offset:-4px; }
.recon-demo svg { stroke:currentColor; stroke-width:1.5; stroke-linecap:round; stroke-linejoin:round; flex-shrink:0; }
.demo-steps { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); background:#151c26; border-bottom:1px solid var(--demo-line); }
.demo-steps button { position:relative; min-height:64px; display:flex; align-items:center; justify-content:center; gap:10px; border:0; background:transparent; color:var(--demo-muted); font-size:14px; transition:color .18s,background .18s; }
.demo-steps button + button { border-left:1px solid var(--demo-line); }
.demo-steps button::after { content:""; position:absolute; bottom:-1px; inset-inline:22px; height:2px; background:var(--demo-blue); transform:scaleX(0); transition:transform .2s; }
.demo-steps button.active { color:var(--demo-ink); background:var(--public-accent-soft); }
.demo-steps button.active::after { transform:scaleX(1); }
.demo-steps button:hover { color:var(--demo-ink); background:#ffffff05; }
.step-number { color:#9caabd; font-size:11px; font-variant-numeric:tabular-nums; }
.active .step-number { color:var(--demo-blue); }
.demo-steps svg { width:13px; height:13px; color:#8598b0; }
.demo-scene { display:grid; grid-template-columns:minmax(0,1fr) 30%; }
.demo-ledger { min-width:0; }
.ledger-heading { min-height:72px; padding:16px 25px; display:flex; align-items:center; justify-content:space-between; gap:12px; border-bottom:1px solid var(--demo-line); }
.ledger-title { min-width:0; display:flex; align-items:center; gap:12px; }
.ledger-title > svg { width:23px; height:23px; color:#98a8be; }
.ledger-title strong { display:block; font-size:13px; font-weight:500; }
.context-divider { margin:0 10px; color:#566171; }
.ledger-subtitle { display:block; margin-top:6px; color:var(--demo-muted); font-size:11px; }
.sample-marker { flex:none; font-size:10px; color:#9aa6b9; padding:3px 6px; border:1px solid #303743; border-radius:4px; }
.ledger-table { border-collapse:collapse; width:100%; font-variant-numeric:tabular-nums; }
.ledger-table th { font-size:11px; font-weight:400; color:var(--demo-muted); height:35px; text-align:right; background:#ffffff02; white-space:nowrap; }
.ledger-table th:first-child, .ledger-table td:first-child { text-align:left; padding-left:25px; }
.ledger-table th:last-child, .ledger-table td:last-child { text-align:right; padding-right:25px; }
.ledger-table th:nth-child(2), .ledger-table td:nth-child(2), .ledger-table th:nth-child(3), .ledger-table td:nth-child(3) { padding-right:14px; }
.ledger-table td { height:50px; text-align:right; border-top:1px solid var(--demo-line); transition:background-color .22s; }
.ledger-table td:first-child { width:30%; }
.ledger-table td:nth-child(2), .ledger-table td:nth-child(3) { width:23%; }
.entry-identity { display:flex; align-items:center; gap:8px; font-size:13px; }
.entry-id { font-size:10px; color:#818c9f; }
.ledger-table td small { display:block; font-size:9px; color:#818c9f; margin-top:3px; }
.ledger-amount { font-size:14px; letter-spacing:.015em; color:#dce3ee; }
.difference-row { background:#7d9fff09; }
.difference-row td:first-child { box-shadow:inset 2px 0 var(--demo-blue); }
.different-amount { color:#9ab4ff; }
.row-result { display:inline-flex; align-items:center; justify-content:flex-end; gap:5px; white-space:nowrap; font-size:11px; }
.row-result i, .inspection-counts i { width:4px; height:4px; border-radius:50%; background:currentColor; flex:none; }
.row-result svg { width:11px; height:11px; }
.pending { color:#8390a3; }.matched { color:#a2b5a9; }.difference { color:#9ab4ff; }
.inspect-difference { border:0; padding:5px 0 5px 4px; display:inline-flex; align-items:center; gap:2px; color:var(--demo-blue); background:transparent; font-size:11px !important; white-space:nowrap; }
.inspect-difference svg { width:12px; height:12px; }.inspect-difference:hover { color:#d7e2ff; }
.ledger-totals { min-height:72px; display:flex; align-items:center; gap:32px; padding:12px 25px; border-top:1px solid var(--demo-line); }
.ledger-totals > div > span { display:block; font-size:10px; color:var(--demo-muted); margin-bottom:5px; }
.ledger-totals strong { font-size:18px; font-weight:500; letter-spacing:-.035em; font-variant-numeric:tabular-nums; }
.ledger-totals strong span { color:#8791a1; font-size:12px; }
.ledger-totals p { margin:0 0 0 auto; font-size:9px; color:#8490a3; line-height:1.8; text-align:right; }
.demo-inspector { border-left:1px solid var(--demo-line); background:#171a20; min-width:0; }
.inspector-top { display:flex; align-items:center; justify-content:space-between; padding:17px 23px; border-bottom:1px solid var(--demo-line); font-size:10px; color:var(--demo-muted); }
.inspector-indicator { width:5px; height:5px; border-radius:50%; background:#7186b4; box-shadow:0 0 0 3px #7d9fff08; }
.inspector-content { padding:22px 23px 18px; animation:inspector-in .24s ease-out both; }
.inspector-symbol { width:29px; height:29px; color:#91a7d2; margin-bottom:15px; }
.inspector-content h3 { margin:0; font-size:18px; font-weight:500; letter-spacing:-.015em; }
.inspector-intro { color:#a1a9b6; font-size:12px; line-height:1.8; margin:9px 0 18px; }
.file-detail { display:flex; align-items:center; gap:10px; padding:13px 0; border-block:1px solid var(--demo-line); font-size:12px; }
.file-detail > svg { width:18px; height:18px; color:#94a4bf; }
.file-detail small { display:block; color:#929ba9; margin-top:5px; font-size:11px; }
.inspector-note { margin-top:16px; display:flex; align-items:center; gap:7px; color:#929eaf; font-size:11px; line-height:1.7; }
.check-circle { width:14px; height:14px; border:1px solid #809b8c50; color:#a2b5a9; border-radius:50%; display:inline-flex; align-items:center; justify-content:center; font-size:9px; }
.inspection-counts { margin:0; }
.inspection-counts > div { display:flex; justify-content:space-between; align-items:center; padding:10px 0; border-bottom:1px solid var(--demo-line); }
.inspection-counts dt { display:flex; align-items:center; gap:7px; font-size:12px; color:#bdc6d4; }.inspection-counts dd { margin:0; font-size:18px; }.inspection-counts dd span { font-size:11px; color:var(--demo-muted); margin-left:4px; }.matched-dot { color:#a2b5a9; }.difference-dot { color:var(--demo-blue); }
.inspector-reference { display:flex; justify-content:space-between; gap:8px; font-size:10px; color:#aab5c7; margin-bottom:19px; }.inspector-reference span:last-child { color:#8da8eb; }
.inspector-content h3.difference-value { font-size:36px; font-weight:400; letter-spacing:-.055em; line-height:1; font-variant-numeric:tabular-nums; }.difference-value span { font-size:19px; color:#8b9ab4; letter-spacing:0; }
.difference-breakdown { margin:0; border-top:1px solid var(--demo-line); padding-top:9px; }.difference-breakdown > div { display:flex; align-items:center; justify-content:space-between; padding:6px 0; font-size:12px; }.difference-breakdown dt { color:var(--demo-muted); }.difference-breakdown dd { margin:0; font-variant-numeric:tabular-nums; }
.case-created { display:flex; align-items:center; gap:10px; margin-top:15px; padding-top:15px; border-top:1px solid var(--demo-line); font-size:12px; color:#d4dcec; }.case-created svg { width:18px; height:18px; color:#8fa7db; }.case-created small { display:block; color:var(--demo-muted); font-size:11px; margin-top:5px; }
.case-timeline { list-style:none; margin:0; padding:0; }.case-timeline li { position:relative; display:flex; align-items:flex-start; gap:10px; padding-bottom:15px; font-size:12px; }.case-timeline li:not(:last-child)::after { content:""; position:absolute; width:1px; top:17px; bottom:0; left:7px; background:#3b4557; }.case-timeline i { font-style:normal; display:flex; align-items:center; justify-content:center; flex:none; width:15px; height:15px; border:1px solid #4d5f80; color:#b8c9ed; border-radius:50%; font-size:8px; }.case-timeline small { display:block; font-size:11px; color:var(--demo-muted); margin-top:4px; }.case-timeline .current { padding-bottom:0; }.case-timeline .current i { border-color:#7d9fff; background:#7d9fff; box-shadow:inset 0 0 0 4px #171a20; }.case-timeline .current > div { color:#afc3ff; }.history-note { color:#8692a5; font-size:11px; margin:15px 0 0; }
.demo-controls { min-height:56px; display:flex; align-items:center; justify-content:space-between; gap:16px; padding:11px 25px; border-top:1px solid var(--demo-line); background:#12151a; }
.demo-controls > p { margin:0; font-size:11px; color:#a2adbd; line-height:1.7; }.next-step { display:inline-flex; align-items:center; justify-content:center; flex:none; gap:10px; border:1px solid #46587d; border-radius:6px; color:#d6e1ff; background:#23304b; min-height:32px; padding:5px 12px; font-size:11px !important; transition:background .15s,border-color .15s,transform .15s; }.next-step svg { width:15px; height:15px; }.next-step:hover { background:#2c3e60; border-color:#6a83b3; }.next-step:active { transform:translateY(1px); }
.sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
@keyframes inspector-in { from { opacity:.4; transform:translateY(3px); } to { opacity:1; transform:translateY(0); } }
.demo-scene[data-step="1"] .ledger-table tbody tr { --check-delay:0ms; animation:ledger-check 280ms ease-out var(--check-delay) both; }
.demo-scene[data-step="1"] .ledger-table tbody tr:nth-child(2) { --check-delay:60ms; }
.demo-scene[data-step="1"] .ledger-table tbody tr:nth-child(3) { --check-delay:120ms; }
.demo-scene[data-step="1"] .ledger-table tbody tr:nth-child(4) { --check-delay:180ms; }
.demo-scene[data-step="1"] .ledger-table tbody td:last-child > * { animation:result-check 220ms ease-out var(--check-delay) both; }
@keyframes ledger-check { 0%,100% { background-color:transparent; } 30% { background-color:#7d9fff0d; } }
@keyframes result-check { from { opacity:.45; transform:translateY(2px); } to { opacity:1; transform:translateY(0); } }
@media (max-width:950px) { .demo-scene { grid-template-columns:minmax(0,1fr) 240px; }.ledger-heading { padding-inline:20px; }.ledger-table th:first-child,.ledger-table td:first-child { padding-left:20px; }.ledger-table th:last-child,.ledger-table td:last-child { padding-right:20px; }.ledger-totals { padding-inline:20px; gap:20px; }.inspector-content { padding-inline:20px; }.inspector-top { padding-inline:20px; } }
@media (max-width:680px) { .recon-demo { border-radius:12px; }.demo-steps { grid-template-columns:repeat(2,minmax(0,1fr)); }.demo-steps button { min-height:52px; justify-content:flex-start; padding:0 18px; font-size:12px; gap:9px; }.demo-steps button:nth-child(3) { border-left:0; }.demo-steps button:nth-child(n+3) { border-top:1px solid var(--demo-line); }.demo-steps button::after { inset-inline:18px; }.demo-steps svg { margin-left:auto; }.demo-scene { grid-template-columns:minmax(0,1fr); }.ledger-heading { min-height:73px; padding:17px 17px; }.ledger-title { gap:9px; }.ledger-title > svg { width:20px; height:20px; }.ledger-title strong { font-size:12px; }.context-divider { margin:0 7px; }.ledger-subtitle { font-size:10px; }.ledger-table th:first-child,.ledger-table td:first-child { padding-left:17px; }.ledger-table th:last-child,.ledger-table td:last-child { padding-right:17px; }.ledger-table th:nth-child(2),.ledger-table td:nth-child(2),.ledger-table th:nth-child(3),.ledger-table td:nth-child(3) { padding-right:9px; }.ledger-table th { font-size:9px; height:34px; }.ledger-table td { height:53px; }.ledger-table td:first-child { width:31%; }.ledger-table td:nth-child(2),.ledger-table td:nth-child(3) { width:22%; }.entry-identity { font-size:11px; gap:5px; }.entry-id { font-size:9px; }.ledger-amount { font-size:11px; letter-spacing:0; }.row-result,.inspect-difference { font-size:9px !important; gap:3px; }.ledger-totals { padding:16px 17px; gap:25px; }.ledger-totals strong { font-size:20px; }.ledger-totals > div > span { font-size:9px; }.ledger-totals p { font-size:8px; }.demo-inspector { border-left:0; border-top:1px solid var(--demo-line); }.inspector-top { padding:12px 17px; }.inspector-content { min-height:278px; padding:19px 21px; }.inspector-symbol { width:24px; height:24px; margin-bottom:10px; }.inspector-content h3 { font-size:17px; }.inspector-intro { margin-bottom:14px; }.file-detail { margin-top:7px; }.inspector-note { margin-top:15px; }.inspection-counts > div { padding:9px 0; }.inspector-reference { margin-bottom:16px; }.difference-breakdown { padding-top:7px; }.case-created { margin-top:10px; padding-top:11px; }.demo-controls { align-items:flex-start; padding:16px 17px; flex-direction:column; gap:12px; }.demo-controls > p { font-size:10px; }.next-step { min-height:37px; align-self:flex-end; padding-inline:14px; } }
@media (max-width:360px) { .demo-steps button { padding-inline:13px; font-size:11px; }.ledger-heading { padding-inline:12px; }.ledger-title > svg { display:none; }.ledger-table th:first-child,.ledger-table td:first-child { padding-left:12px; }.ledger-table th:last-child,.ledger-table td:last-child { padding-right:12px; }.ledger-table th:nth-child(2),.ledger-table td:nth-child(2),.ledger-table th:nth-child(3),.ledger-table td:nth-child(3) { padding-right:6px; }.ledger-amount { font-size:10px; }.ledger-totals { padding-inline:12px; gap:19px; }.ledger-totals p { max-width:65px; }.entry-identity { gap:3px; }.row-result { gap:2px; }.row-result i { display:none; } }
@media (max-width:680px) { .inspect-difference, .next-step { min-height:44px; } }
@media (prefers-reduced-motion:reduce) { .recon-demo *, .recon-demo *::before, .recon-demo *::after { animation:none !important; transition:none !important; }.next-step:active { transform:none; } }
</style>
