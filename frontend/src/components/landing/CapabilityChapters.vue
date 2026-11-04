<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { ArrowRight, Check, Document, FolderOpened, Goods, Location, Lock, RefreshLeft } from "@element-plus/icons-vue";

const scope = ref(0);
const scopes = ["商户", "门店", "流水"];
const scopeControls = ref<HTMLElement | null>(null);
function changeScope(next: number, fromDrill = false) {
  scope.value = next;
  if (fromDrill) nextTick(() => scopeControls.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus({ preventScroll: true }));
}
const scopeTitle = computed(() => scope.value === 0 ? "海棠咖啡店" : scope.value === 1 ? "图书馆店" : "资金流水");
const transactions = [
  { id: "001", name: "收款", amount: "+36.00", refund: false },
  { id: "002", name: "收款", amount: "+24.00", refund: false },
  { id: "003", name: "收款", amount: "+18.00", refund: false },
  { id: "R001", name: "退款 · 原订单 001", amount: "−12.00", refund: true },
];
</script>

<template>
  <section id="capabilities" class="capabilities" tabindex="-1" aria-label="产品能力">
    <div class="cap-inner">
      <article class="cap-chapter cap-payment reveal-section" aria-labelledby="cap-payment-title">
        <div class="cap-copy">
          <span class="cap-index"><span>01</span> 收款与退款</span>
          <h2 id="cap-payment-title">从原订单看退款</h2>
          <p>一单收了多少、退了多少，连同审批进度都在订单里。</p>
        </div>

        <div class="cap-payment-visual" aria-label="订单 001 的收款与退款示例">
          <div class="cap-order">
            <div class="cap-windowbar"><span><Goods />订单 001</span><span class="cap-sample">示例</span></div>
            <div class="cap-order-content">
              <div class="cap-order-title"><span>海棠咖啡店 <span class="cap-title-separator">/</span> 图书馆店</span><span class="cap-tag cap-tag-neutral">部分退款</span></div>
              <div class="cap-order-amount"><span>订单收款</span><strong><small>¥</small>36.00</strong></div>
              <div class="cap-money-track" role="img" aria-label="收款 36 元，已退款 12 元，净收款 24 元"><span /><span /></div>
              <div class="cap-money-labels"><div><i />净收款<strong>¥24.00</strong></div><div><i />已退款<strong>¥12.00</strong></div></div>
              <ol class="cap-payment-history" aria-label="资金处理进度">
                <li><span class="cap-history-point"><Check /></span><strong>支付成功</strong><small>收款 ¥36.00</small></li>
                <li><span class="cap-history-point"><Check /></span><strong>退款获批</strong><small>独立审核</small></li>
                <li><span class="cap-history-point"><Check /></span><strong>退款成功</strong><small>退回 ¥12.00</small></li>
              </ol>
            </div>
            <div class="cap-order-footer"><RefreshLeft /><span>退款 R001</span><span>关联原订单 001</span></div>
          </div>
        </div>
      </article>

      <article class="cap-chapter cap-cases reveal-section" aria-labelledby="cap-cases-title">
        <div class="cap-case-visual" aria-label="订单 002 金额差异的处理示例">
          <div class="cap-case-surface">
            <div class="cap-windowbar"><span><FolderOpened />差错处理 <span class="cap-case-number">002</span></span><span class="cap-sample">示例</span></div>
            <div class="cap-case-body">
              <div class="cap-case-heading"><h3>金额差异</h3><span class="cap-tag">待独立复核</span></div>
              <div class="cap-comparison"><div><small>本地收款</small><strong>¥24.00</strong></div><div><small>渠道记录</small><strong>¥24.50</strong></div><div class="cap-difference"><small>差额</small><strong>¥0.50</strong></div></div>
              <div class="cap-case-divider" />
              <div class="cap-evidence"><span class="cap-evidence-icon"><Document /></span><div><strong>原始账单与处理说明</strong><small>已附原始账单</small></div><span class="cap-evidence-state"><Check />已留存</span></div>
              <div class="cap-review-route"><div><span class="cap-person">A</span><div><strong>处理人</strong><small>已提交分析</small></div><Check class="cap-route-check" /></div><ArrowRight class="cap-route-arrow" /><div><span class="cap-person cap-person-review">B</span><div><strong>复核人</strong><small>等待复核</small></div><span class="cap-pending-dot" /></div></div>
              <p class="cap-case-note"><Lock />处理人与复核人相互独立</p>
            </div>
          </div>
        </div>
        <div class="cap-copy">
          <span class="cap-index"><span>02</span> 差错协作</span>
          <h2 id="cap-cases-title">差错单独复核</h2>
          <p>财务分派工单，经办人附上原始账单和处理依据，再由另一人复核。</p>
        </div>
      </article>

      <article class="cap-chapter cap-analysis reveal-section" aria-labelledby="cap-analysis-title">
        <div class="cap-analysis-heading">
          <div class="cap-copy"><span class="cap-index"><span>03</span> 经营分析</span><h2 id="cap-analysis-title">从汇总查到每一笔</h2></div>
          <p>从商户点到门店，再查看单笔收款和退款。</p>
        </div>
        <div class="cap-analysis-workspace" data-testid="analysis-demo" :data-scope="scope" aria-label="经营分析交互示例">
          <div class="cap-analysis-toolbar"><span class="cap-analysis-label">本地资金流水<span class="cap-sample">示例</span></span><nav ref="scopeControls" class="cap-scope-nav" aria-label="选择分析层级"><button v-for="(label, index) in scopes" :key="label" type="button" :aria-pressed="scope === index" @click="changeScope(index)">{{ label }}<ArrowRight v-if="index < 2" aria-hidden="true" /></button></nav></div>
          <div class="cap-analysis-content">
            <aside class="cap-analysis-summary"><span>净收款</span><strong><small>¥</small>66.00</strong><div class="cap-net-components"><div><span><i />收款</span><b>¥78.00</b></div><div><span><i />退款</span><b>¥12.00</b></div></div><p>收款 − 退款 = 净收款</p></aside>
            <div class="cap-drill-panel">
              <div class="cap-breadcrumb" aria-label="当前统计范围"><button type="button" @click="scope = 0">海棠咖啡店</button><template v-if="scope > 0"><span>/</span><button type="button" @click="scope = 1">图书馆店</button></template><template v-if="scope === 2"><span>/</span><span>流水</span></template></div>
              <div class="cap-drill-content" aria-live="polite" aria-atomic="true">
                <Transition name="cap-data" mode="out-in">
                  <div v-if="scope < 2" :key="scope" class="cap-aggregate">
                    <div class="cap-aggregate-top"><div><span class="cap-merchant-icon"><Goods v-if="scope === 0" /><Location v-else /></span><h3>{{ scopeTitle }}</h3></div><span>{{ scope === 0 ? '1 家门店' : '3 笔收款 · 1 笔退款' }}</span></div>
                    <div class="cap-aggregate-chart"><div><span>收款</span><div class="cap-amount-bar"><i class="cap-receipt-bar" /></div><b>78.00</b></div><div><span>退款</span><div class="cap-amount-bar"><i class="cap-refund-bar" /></div><b>12.00</b></div></div>
                    <button type="button" class="cap-drill-button" @click="changeScope(scope + 1, true)">{{ scope === 0 ? '查看门店' : '查看 4 笔流水' }}<ArrowRight /></button>
                  </div>
                  <div v-else key="transactions" class="cap-transactions"><div class="cap-transactions-head"><span>资金记录</span><span>金额 / 元</span></div><div v-for="entry in transactions" :key="entry.id" class="cap-transaction"><span class="cap-transaction-id">{{ entry.id }}</span><span>{{ entry.name }}</span><strong :class="{ 'cap-refund-amount': entry.refund }">{{ entry.amount }}</strong></div></div>
                </Transition>
              </div>
            </div>
          </div>
          <div class="cap-analysis-footer"><span>3 个订单，4 笔资金记录</span></div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.capabilities {
  --cap-ink: var(--public-text);
  --cap-muted: var(--public-muted);
  --cap-line: var(--public-line);
  --cap-blue: var(--public-accent);
  color: var(--cap-ink);
  background: transparent;
  outline: none;
}
.cap-inner { width: min(1240px, calc(100% - 96px)); margin: auto; padding: 22px 0 48px; }
.cap-chapter { padding: 72px 0; }
.cap-chapter + .cap-chapter { border-top: 1px solid var(--cap-line); }
.cap-payment, .cap-cases { display: grid; align-items: center; gap: 84px; }
.cap-payment { grid-template-columns: .9fr 1.25fr; }
.cap-cases { grid-template-columns: 1.25fr .9fr; }
.cap-index { display: flex; gap: 13px; align-items: baseline; color: var(--cap-muted); font-size: 13px; margin-bottom: 26px; }
.cap-index > span { color: var(--cap-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.cap-copy h2 { font-family: var(--font-display); font-size: clamp(29px, 3.2vw, 43px); font-weight: 650; letter-spacing: -.035em; line-height: 1.35; }
.cap-copy > p { margin-top: 24px; color: var(--cap-muted); font-size: 15px; line-height: 1.85; }
.cap-order { background: var(--public-panel); border: 1px solid var(--cap-line); border-radius: 13px; box-shadow: 0 18px 44px #080c1559; overflow: hidden; }
.cap-windowbar { display: flex; align-items: center; justify-content: space-between; padding: 15px 22px; border-bottom: 1px solid var(--cap-line); color: var(--cap-muted); font-size: 12px; }
.cap-windowbar > span:first-child { display: flex; align-items: center; gap: 9px; }
.cap-windowbar svg { width: 15px; height: 15px; color: var(--cap-muted); }
.cap-sample { font-size: 10px; line-height: 1.2; font-weight: 400; color: var(--cap-muted); border: 1px solid var(--cap-line); padding: 3px 6px; border-radius: 4px; }
.cap-order-content { padding: 23px 28px 24px; }
.cap-order-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--cap-muted); font-size: 11px; }
.cap-title-separator { margin: 0 6px; color: var(--cap-muted); }
.cap-tag { display: inline-flex; align-items: center; white-space: nowrap; color: var(--public-accent); background: #e7bd911f; font-size: 10px; border-radius: 4px; padding: 5px 7px; }
.cap-tag-neutral { background: var(--public-raised); color: var(--cap-muted); }
.cap-order-amount { margin: 22px 0 21px; }
.cap-order-amount > span { display: block; color: var(--cap-muted); font-size: 11px; margin-bottom: 4px; }
.cap-order-amount strong, .cap-analysis-summary > strong { font-family: var(--font-ui); font-size: 40px; font-weight: 550; letter-spacing: -1.5px; line-height: 1.3; font-variant-numeric: tabular-nums; }
.cap-order-amount strong small, .cap-analysis-summary > strong small { margin-right: 6px; font-size: 22px; font-weight: 400; color: var(--cap-muted); }
.cap-money-track { display: flex; gap: 4px; height: 6px; }
.cap-money-track span { border-radius: 2px; background: var(--public-accent); width: 66.6667%; }
.cap-money-track span + span { width: 33.3333%; background: repeating-linear-gradient(120deg, #506179 0 2px, #354457 2px 5px); }
.cap-money-labels { display: flex; justify-content: space-between; margin-top: 11px; }
.cap-money-labels > div { display: flex; align-items: center; gap: 5px; font-size: 10px; color: var(--cap-muted); }
.cap-money-labels i { width: 4px; height: 4px; border-radius: 50%; background: var(--public-accent); }
.cap-money-labels > div + div i { background: #72849c; }
.cap-money-labels strong { font-weight: 550; color: var(--cap-muted); margin-left: 3px; font-variant-numeric: tabular-nums; }
.cap-payment-history { display: grid; grid-template-columns: repeat(3, 1fr); padding: 0; margin: 26px 0 0; list-style: none; }
.cap-payment-history li { position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: 5px; }
.cap-payment-history li:not(:last-child)::after { content: ""; position: absolute; top: 9px; left: 22px; width: calc(100% - 28px); height: 1px; background: var(--cap-line); }
.cap-history-point { position: relative; display: flex; align-items: center; justify-content: center; width: 19px; height: 19px; background: #e7bd911f; border-radius: 50%; color: var(--public-accent); margin-bottom: 3px; }
.cap-history-point svg { width: 10px; height: 10px; }
.cap-payment-history strong { font-size: 11px; font-weight: 500; }
.cap-payment-history small { color: var(--cap-muted); font-size: 10px; }
.cap-order-footer { display: flex; align-items: center; gap: 7px; padding: 12px 28px; background: #151c26; border-top: 1px solid var(--cap-line); color: var(--cap-muted); font-size: 10px; }
.cap-order-footer svg { width: 12px; height: 12px; }
.cap-order-footer > span:last-child { margin-left: auto; }
.cap-case-visual { padding: 22px; background: #222b374d; border: 1px solid var(--cap-line); border-radius: 16px; }
.cap-case-surface { background: var(--public-panel); border: 1px solid var(--cap-line); border-radius: 9px; box-shadow: 0 12px 34px #080c1540; overflow: hidden; }
.cap-case-number { color: var(--cap-muted); font-variant-numeric: tabular-nums; }
.cap-case-body { padding: 21px 23px 18px; }
.cap-case-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.cap-case-heading h3 { font-size: 14px; font-weight: 550; }
.cap-comparison { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 25px; gap: 20px; }
.cap-comparison > div { display: flex; flex-direction: column; gap: 7px; }
.cap-comparison small { font-size: 10px; color: var(--cap-muted); }
.cap-comparison strong { font-size: 21px; letter-spacing: -.6px; font-weight: 550; font-variant-numeric: tabular-nums; }
.cap-comparison .cap-difference strong { color: var(--public-accent); }
.cap-case-divider { height: 1px; background: var(--cap-line); margin: 22px 0; }
.cap-evidence { display: flex; align-items: center; gap: 11px; }
.cap-evidence-icon { display: flex; align-items: center; justify-content: center; width: 33px; height: 37px; border: 1px solid var(--cap-line); border-radius: 5px; color: var(--cap-muted); background: var(--public-raised); flex: none; }
.cap-evidence-icon svg { width: 18px; height: 18px; }
.cap-evidence > div { display: flex; flex-direction: column; gap: 5px; }
.cap-evidence strong { font-size: 11px; font-weight: 500; }
.cap-evidence small { font-size: 10px; color: var(--cap-muted); }
.cap-evidence-state { margin-left: auto; display: flex; gap: 3px; align-items: center; color: var(--public-positive); font-size: 10px; white-space: nowrap; }
.cap-evidence-state svg { width: 12px; height: 12px; }
.cap-review-route { display: grid; grid-template-columns: 1fr 24px 1fr; align-items: center; gap: 7px; margin-top: 24px; }
.cap-review-route > div { display: flex; align-items: center; gap: 8px; padding: 9px; border: 1px solid var(--cap-line); border-radius: 6px; min-width: 0; }
.cap-person { width: 25px; height: 25px; display: flex; align-items: center; justify-content: center; flex: none; background: #2c394a; color: var(--cap-muted); border-radius: 50%; font-size: 10px; }
.cap-person-review { background: #e7bd911f; color: var(--cap-muted); }
.cap-review-route > div > div { display: flex; flex-direction: column; gap: 3px; }
.cap-review-route strong { font-size: 10px; font-weight: 500; }
.cap-review-route small { font-size: 9px; color: var(--cap-muted); }
.cap-route-check { width: 11px; color: var(--public-positive); margin-left: auto; flex: none; }
.cap-route-arrow { color: var(--cap-muted); width: 17px; height: 17px; justify-self: center; }
.cap-pending-dot { width: 5px; height: 5px; background: var(--public-accent); border-radius: 50%; margin-left: auto; flex: none; }
.cap-case-note { display: flex; align-items: center; gap: 6px; margin-top: 17px; color: var(--cap-muted); font-size: 10px; }
.cap-case-note svg { width: 11px; height: 11px; }
.cap-analysis { padding-bottom: 30px; }
.cap-analysis-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 40px; margin-bottom: 35px; }
.cap-analysis-heading .cap-index { margin-bottom: 20px; }
.cap-analysis-heading > p { color: var(--cap-muted); font-size: 14px; line-height: 1.9; padding-bottom: 4px; }
.cap-analysis-workspace { background: var(--public-panel); border: 1px solid var(--cap-line); border-radius: 12px; box-shadow: 0 16px 42px #080c154d; overflow: hidden; }
.cap-analysis-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 17px 28px; border-bottom: 1px solid var(--cap-line); }
.cap-analysis-label { display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--cap-muted); }
.cap-scope-nav { display: flex; align-items: center; gap: 6px; }
.cap-scope-nav button { display: flex; align-items: center; gap: 16px; color: var(--cap-muted); padding: 7px 8px; background: transparent; border: 0; border-radius: 4px; font-size: 11px; transition: color .16s, background .16s; }
.cap-scope-nav button svg { width: 11px; height: 11px; color: var(--cap-muted); }
.cap-scope-nav button[aria-pressed="true"] { color: var(--public-accent); font-weight: 600; }
.cap-scope-nav button:hover { color: var(--public-accent); background: var(--public-raised); }
.cap-analysis-content { display: grid; grid-template-columns: 33% 1fr; }
.cap-analysis-summary { padding: 32px 40px; border-right: 1px solid var(--cap-line); background: #151c26; display: flex; flex-direction: column; align-items: flex-start; }
.cap-analysis-summary > span { color: var(--cap-muted); font-size: 11px; margin-bottom: 8px; }
.cap-analysis-summary > strong { font-size: 46px; }
.cap-net-components { display: grid; width: 100%; gap: 12px; margin-top: 23px; }
.cap-net-components > div { display: flex; justify-content: space-between; gap: 20px; font-size: 11px; }
.cap-net-components span { display: flex; align-items: center; gap: 7px; color: var(--cap-muted); }
.cap-net-components i { width: 5px; height: 5px; border-radius: 1px; background: var(--public-accent); }
.cap-net-components > div + div i { background: #72849c; }
.cap-net-components b { font-variant-numeric: tabular-nums; font-weight: 500; color: var(--cap-muted); }
.cap-analysis-summary > p { margin-top: auto; padding-top: 25px; color: var(--cap-muted); font-size: 10px; }
.cap-drill-panel { padding: 24px 40px 27px; }
.cap-breadcrumb { display: flex; align-items: center; gap: 8px; min-height: 26px; color: var(--cap-muted); font-size: 10px; }
.cap-breadcrumb button { color: var(--cap-muted); background: transparent; border: 0; padding: 3px 0; font-size: inherit; border-radius: 2px; }
.cap-breadcrumb button:hover { color: var(--public-accent); }
.cap-drill-content { height: 193px; margin-top: 10px; }
.cap-aggregate-top { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.cap-aggregate-top > div { display: flex; align-items: center; gap: 8px; }
.cap-aggregate-top h3 { font-size: 14px; font-weight: 550; }
.cap-aggregate-top > span { color: var(--cap-muted); font-size: 10px; }
.cap-merchant-icon { width: 26px; height: 26px; display: flex; align-items: center; justify-content: center; background: var(--public-raised); color: var(--cap-muted); border-radius: 6px; }
.cap-merchant-icon svg { width: 15px; height: 15px; }
.cap-aggregate-chart { display: grid; gap: 18px; margin-top: 25px; }
.cap-aggregate-chart > div { display: grid; grid-template-columns: 29px 1fr 40px; align-items: center; gap: 10px; }
.cap-aggregate-chart span { font-size: 10px; color: var(--cap-muted); }
.cap-aggregate-chart b { text-align: right; font-size: 11px; color: var(--cap-muted); font-weight: 500; font-variant-numeric: tabular-nums; }
.cap-amount-bar { height: 11px; background: var(--public-raised); border-radius: 2px; overflow: hidden; }
.cap-amount-bar > i { display: block; height: 100%; border-radius: 2px; }
.cap-receipt-bar { width: 100%; background: var(--public-accent); }
.cap-refund-bar { width: 15.3846%; background: #72849c; }
.cap-drill-button { display: flex; align-items: center; justify-content: center; gap: 14px; margin-top: 23px; color: var(--public-accent); background: var(--public-raised); border: 1px solid var(--cap-line); padding: 9px 12px; border-radius: 5px; font-size: 11px; transition: background .16s, border-color .16s, transform .16s; }
.cap-drill-button svg { width: 12px; height: 12px; transition: transform .16s; }
.cap-drill-button:hover { background: #29374a; border-color: #e7bd9166; }
.cap-drill-button:hover svg { transform: translateX(2px); }
.cap-drill-button:active { transform: translateY(1px); }
.cap-transactions-head { display: flex; justify-content: space-between; padding-bottom: 9px; color: var(--cap-muted); font-size: 10px; }
.cap-transaction { display: grid; grid-template-columns: 55px 1fr auto; gap: 10px; align-items: center; min-height: 38px; border-top: 1px solid var(--cap-line); color: var(--cap-muted); font-size: 11px; }
.cap-transaction-id { color: var(--cap-muted); font-size: 10px; }
.cap-transaction strong { color: var(--public-text); font-size: 12px; font-weight: 500; font-variant-numeric: tabular-nums; }
.cap-transaction .cap-refund-amount { color: var(--cap-muted); }
.cap-analysis-footer { display: flex; justify-content: space-between; gap: 20px; padding: 13px 28px; border-top: 1px solid var(--cap-line); color: var(--cap-muted); font-size: 10px; }
.cap-comparison small, .cap-evidence small, .cap-review-route small, .cap-case-note, .cap-analysis-summary > p, .cap-aggregate-top > span, .cap-aggregate-chart span, .cap-transactions-head, .cap-transaction-id, .cap-analysis-footer { color: var(--cap-muted); }
.capabilities button:focus-visible { outline: 2px solid var(--public-accent); outline-offset: 4px; }
.cap-analysis-workspace { overflow-anchor: none; }
.cap-breadcrumb { height: 28px; white-space: nowrap; }
.cap-drill-content { min-height: 202px; height: 202px; }
.cap-data-enter-active, .cap-data-leave-active { transition: opacity .12s ease, transform .12s ease; }
.cap-data-enter-from { opacity: 0; transform: translateY(3px); }
.cap-data-leave-to { opacity: 0; }
@media (min-width: 1440px) { .cap-inner { width: min(1200px, calc(100% - 144px)); } }
@media (max-width: 1100px) { .cap-payment, .cap-cases { gap: 42px; } .cap-inner { width: calc(100% - 72px); } .cap-case-visual { padding: 15px; } .cap-case-body { padding: 20px 18px 17px; } .cap-comparison { gap: 12px; } .cap-analysis-summary { padding: 30px; } .cap-drill-panel { padding: 24px 30px; } .cap-desktop-break { display: none; } }
@media (max-width: 800px) { .cap-inner { width: calc(100% - 48px); padding: 8px 0 34px; } .cap-chapter { padding: 56px 0; } .cap-payment, .cap-cases { grid-template-columns: 1fr; gap: 30px; } .cap-cases .cap-copy { grid-row: 1; } .cap-payment-visual, .cap-case-visual { width: 100%; max-width: 580px; justify-self: center; } .cap-index { margin-bottom: 17px; } .cap-copy h2 { font-size: 33px; letter-spacing: -1px; line-height: 1.45; } .cap-copy > p { margin-top: 18px; font-size: 14px; } .cap-analysis-heading { display: block; margin-bottom: 25px; } .cap-analysis-heading > p { margin-top: 18px; font-size: 14px; } .cap-analysis-content { grid-template-columns: 36% 1fr; } .cap-analysis-summary { padding: 24px; } .cap-drill-panel { padding: 20px 22px; } .cap-analysis-summary > strong { font-size: 39px; } }
@media (max-width: 560px) { .cap-inner { width: calc(100% - 40px); padding-bottom: 23px; } .cap-chapter { padding: 48px 0; } .cap-copy h2 { font-size: 30px; } .cap-index { font-size: 12px; } .cap-copy > p { font-size: 13px; } .cap-order-content { padding: 20px; } .cap-windowbar { padding: 13px 18px; } .cap-order-title { gap: 8px; font-size: 10px; } .cap-title-separator { margin: 0 3px; } .cap-order-amount { margin-top: 18px; } .cap-order-footer { padding: 12px 20px; } .cap-order-amount strong { font-size: 37px; } .cap-money-labels > div { gap: 4px; } .cap-case-visual { padding: 12px; border-radius: 12px; } .cap-case-body { padding: 18px 14px 15px; } .cap-comparison { margin-top: 20px; gap: 10px; } .cap-comparison strong { font-size: 19px; } .cap-evidence { gap: 8px; flex-wrap: wrap; } .cap-evidence-state { margin-left: 41px; } .cap-review-route { gap: 2px; grid-template-columns: 1fr 18px 1fr; margin-top: 18px; } .cap-review-route > div { padding: 8px 6px; gap: 6px; } .cap-route-arrow { width: 12px; } .cap-person { width: 21px; height: 21px; } .cap-case-divider { margin: 18px 0; } .cap-case-note { font-size: 9px; } .cap-analysis-heading > p { font-size: 13px; } .cap-analysis-workspace { border-radius: 9px; } .cap-analysis-toolbar { flex-wrap: wrap; padding: 16px 18px 12px; gap: 12px; } .cap-analysis-label { font-size: 11px; width: 100%; justify-content: space-between; } .cap-scope-nav { width: 100%; justify-content: space-between; gap: 5px; } .cap-scope-nav button { padding: 7px 0; gap: 22px; font-size: 12px; } .cap-analysis-content { display: block; } .cap-analysis-summary { display: grid; grid-template-columns: 1fr 1fr; padding: 21px 21px 17px; border-right: 0; border-bottom: 1px solid var(--cap-line); } .cap-analysis-summary > span { grid-column: 1; grid-row: 1; margin: 0; font-size: 10px; } .cap-analysis-summary > strong { grid-column: 1; grid-row: 2; font-size: 35px; } .cap-analysis-summary > strong small { font-size: 18px; } .cap-net-components { grid-column: 2; grid-row: 1 / 3; margin: 0; padding-left: 18px; align-self: center; gap: 11px; } .cap-net-components > div { font-size: 10px; gap: 10px; } .cap-analysis-summary > p { grid-column: 1 / 3; padding-top: 9px; font-size: 9px; } .cap-drill-panel { padding: 17px 21px 20px; } .cap-aggregate-top { gap: 8px; } .cap-aggregate-top h3 { font-size: 12px; } .cap-aggregate-top > span { font-size: 9px; } .cap-aggregate-chart { margin-top: 23px; } .cap-drill-content { height: 187px; margin-top: 8px; } .cap-analysis-footer { padding: 12px 18px; gap: 8px; flex-wrap: wrap; font-size: 9px; } .cap-transaction { grid-template-columns: 42px 1fr auto; gap: 6px; font-size: 10px; } }
@media (max-width: 360px) { .cap-inner { width: calc(100% - 32px); } .cap-copy h2 { font-size: 27px; } .cap-order-content { padding: 17px; } .cap-order-title { align-items: flex-start; flex-wrap: wrap; } .cap-order-title .cap-tag { font-size: 9px; } .cap-comparison strong { font-size: 16px; } .cap-case-visual { padding: 8px; } .cap-case-body { padding: 16px 12px; } .cap-evidence strong { font-size: 10px; } .cap-evidence small { font-size: 9px; } .cap-review-route > div { padding: 7px 4px; gap: 5px; } .cap-person { width: 18px; height: 18px; font-size: 9px; } .cap-review-route strong { font-size: 9px; } .cap-review-route small { font-size: 8px; } .cap-analysis-summary { padding: 18px; } .cap-drill-panel { padding: 15px 17px 18px; } .cap-net-components { padding-left: 12px; } .cap-analysis-summary > strong { font-size: 32px; } }
.cap-order-title, .cap-tag, .cap-order-amount > span, .cap-money-labels > div, .cap-payment-history small, .cap-order-footer, .cap-comparison small, .cap-evidence small, .cap-evidence-state, .cap-review-route small, .cap-case-note, .cap-analysis-summary > span, .cap-net-components > div, .cap-analysis-summary > p, .cap-aggregate-top > span, .cap-aggregate-chart span, .cap-transactions-head, .cap-transaction-id, .cap-analysis-footer, .cap-breadcrumb { font-size: 11px; }
.cap-payment-history strong, .cap-review-route strong, .cap-scope-nav button, .cap-analysis-label, .cap-drill-button, .cap-transaction { font-size: 12px; }
.cap-evidence strong { font-size: 13px; }
.cap-money-labels strong, .cap-net-components b, .cap-aggregate-chart b, .cap-transaction strong { font-size: 14px; }
.cap-payment-history li { gap: 6px; }
.cap-order-amount { display: flex; align-items: baseline; justify-content: space-between; gap: 18px; margin: 22px 0 24px; }
.cap-order-amount > span { margin-bottom: 0; }
.cap-money-track { height: 8px; }
.cap-money-labels { margin-top: 14px; }
@media (max-width: 560px) { .cap-drill-content { height: 202px; min-height: 202px; } .cap-order-title { align-items: flex-start; flex-wrap: wrap; } .cap-order-title .cap-tag { font-size: 11px; } .cap-evidence-state { margin-left: 41px; } .cap-aggregate-top { flex-wrap: wrap; gap: 8px; } .cap-aggregate-top > span { margin-left: 34px; } .cap-aggregate-chart { margin-top: 18px; } .cap-drill-button { margin-top: 17px; } .cap-transaction { grid-template-columns: 39px 1fr auto; font-size: 11px; } .cap-money-labels > div { flex-wrap: wrap; gap: 4px; } .cap-money-labels strong { margin-left: 3px; } .cap-order-footer { font-size: 10px; } }
@media (max-width: 360px) { .cap-review-route strong { font-size: 11px; } .cap-review-route small { font-size: 11px; } .cap-evidence strong { font-size: 13px; } .cap-evidence small { font-size: 11px; } .cap-comparison strong { font-size: 18px; } .cap-net-components b { font-size: 12px; } .cap-transaction { font-size: 11px; } .cap-transaction-id { font-size: 10px; } .cap-transaction strong { font-size: 14px; } .cap-money-labels > div { max-width: 48%; } .cap-breadcrumb { font-size: 10px; } }
.cap-index > span, .cap-sample, .cap-order-title, .cap-order-amount > span, .cap-order-amount strong small, .cap-analysis-summary > strong small, .cap-money-labels > div, .cap-payment-history small, .cap-order-footer, .cap-case-number, .cap-comparison small, .cap-evidence small, .cap-review-route small, .cap-case-note, .cap-scope-nav button, .cap-analysis-label, .cap-analysis-summary > span, .cap-net-components span, .cap-analysis-summary > p, .cap-breadcrumb, .cap-breadcrumb button, .cap-aggregate-top > span, .cap-aggregate-chart span, .cap-aggregate-chart b, .cap-transactions-head, .cap-transaction, .cap-transaction-id, .cap-transaction .cap-refund-amount, .cap-analysis-footer { color: var(--cap-muted); }
.cap-tag-neutral, .cap-person { color: var(--cap-muted); }
.cap-person-review { color: var(--public-accent); }
.cap-evidence-state { color: var(--public-positive); }
.cap-comparison .cap-difference strong { color: var(--public-accent); }
@media (prefers-reduced-motion: reduce) { .capabilities *, .capabilities *::before, .capabilities *::after { animation: none !important; transition: none !important; } }
</style>
