<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ArrowDown, ArrowRight } from "@element-plus/icons-vue";
import BrandMark from "../components/BrandMark.vue";
import GithubLink from "../components/GithubLink.vue";
import ReconciliationDemo from "../components/landing/ReconciliationDemo.vue";
import CapabilityChapters from "../components/landing/CapabilityChapters.vue";
import coffeePhoto from "../assets/campus/haitang.webp";
import claypotPhoto from "../assets/campus/chengdu.webp";
import printPhoto from "../assets/campus/songfeng.webp";
import bakeryPhoto from "../assets/campus/bailu.webp";
import { useSession } from "../stores/session";

const session = useSession();
const destination = computed(() => session.user ? "/" : "/login");
const actionLabel = computed(() => session.user ? "进入工作台" : "开始使用");
const home = ref<HTMLElement>();
const shops = [
  {
    name: "老成都砂锅店",
    location: "宿舍区",
    photo: claypotPhoto,
    alt: "傍晚的宿舍区步道旁，砂锅店窗口亮着暖灯",
    description: "晚市收款 28 元，次日退回 8 元。两天分别入账，退款关联原订单。",
  },
  {
    name: "松风文印社",
    location: "教学楼旁",
    photo: printPhoto,
    alt: "教学楼旁的文印社，店员正在为学生装订资料",
    description: "取消一份装订后退回差价，店员在原订单里查看退款进度。",
  },
  {
    name: "白露烘焙坊",
    location: "宿舍楼下",
    photo: bakeryPhoto,
    alt: "宿舍楼下的烘焙坊，店员将面包递给学生",
    description: "闭店后核对当天收款。发现渠道多记一笔时，财务分派工单复核。",
  },
];

let observer: IntersectionObserver | undefined;
function jump(event: Event, id: string) {
  const target = document.getElementById(id);
  if (!target) return;
  event.preventDefault();
  target.focus({ preventScroll: true });
  target.scrollIntoView({
    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth",
    block: "start",
  });
}
onMounted(() => {
  if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches && "IntersectionObserver" in window) {
    observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add("in-view");
        observer?.unobserve(entry.target);
      }
    }, { threshold: 0.1 });
    home.value?.querySelectorAll(".reveal-section").forEach((element) => observer?.observe(element));
  }
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <div ref="home" class="product-home public-theme">
    <a href="#product-content" class="home-skip" @click="jump($event, 'product-content')">跳至正文</a>
    <header class="home-header">
      <div class="home-container nav-inner">
        <router-link class="home-brand" to="/welcome" aria-label="Reconis 产品首页">
          <BrandMark /><span>Reconis</span>
        </router-link>
        <nav class="home-nav" aria-label="产品介绍">
          <a href="#campus-shops" @click="jump($event, 'campus-shops')">校园门店</a>
          <a href="#reconciliation-demo-section" @click="jump($event, 'reconciliation-demo-section')">账单核对</a>
          <a href="#capabilities" @click="jump($event, 'capabilities')">功能介绍</a>
        </nav>
        <div class="header-actions">
          <GithubLink />
          <router-link class="home-button nav-button" :to="destination">{{ actionLabel }}<el-icon><ArrowRight /></el-icon></router-link>
        </div>
      </div>
    </header>

    <main id="product-content" tabindex="-1">
      <section class="home-container home-hero" aria-labelledby="hero-title">
        <div class="hero-copy">
          <p class="public-kicker"><i />校园支付对账</p>
          <h1 id="hero-title">给校园小店的<span>收款对账台</span></h1>
          <p class="hero-description">图书馆旁的咖啡店、宿舍区的砂锅店，白天收款，打烊后要对账。Reconis 把订单、退款和渠道账单放在一起，差额可以交给财务复核。</p>
          <div class="hero-actions">
            <router-link class="home-button primary" :to="destination">{{ actionLabel }}<el-icon><ArrowRight /></el-icon></router-link>
            <a class="home-text-link" href="#reconciliation-demo-section" @click="jump($event, 'reconciliation-demo-section')">查看账单样例<el-icon><ArrowDown /></el-icon></a>
          </div>
        </div>
        <figure class="hero-scene">
          <div class="hero-photo">
            <img :src="coffeePhoto" width="1536" height="1024" fetchpriority="high" alt="雨后傍晚，图书馆旁的咖啡店亮着暖灯，学生沿校园步道走过" />
            <div class="scene-caption">
              <span>图书馆旁</span>
              <strong>海棠咖啡店</strong>
            </div>
          </div>
          <a class="hero-ledger-link" href="#reconciliation-demo-section" @click="jump($event, 'reconciliation-demo-section')">
            <span><i />日账单 · 4 笔交易</span>
            <span class="hero-ledger-result">3 笔匹配 <i />1 笔差异</span>
            <el-icon><ArrowRight /></el-icon>
          </a>
        </figure>
      </section>

      <section id="campus-shops" class="home-container campus-section" tabindex="-1" aria-labelledby="shops-title">
        <div class="section-heading reveal-section">
          <div><p class="public-kicker">校园门店</p><h2 id="shops-title">从午餐到晚自习</h2></div>
          <p>餐饮、文印和烘焙，三种常见的收退款场景。</p>
        </div>
        <div class="campus-shops reveal-section">
          <article v-for="shop in shops" :key="shop.name" class="campus-shop">
            <div class="shop-photo"><img :src="shop.photo" width="1536" height="1024" :alt="shop.alt" loading="lazy" decoding="async" /></div>
            <div class="shop-copy"><span>{{ shop.location }}</span><h3>{{ shop.name }}</h3><p>{{ shop.description }}</p></div>
          </article>
        </div>
      </section>

      <section id="reconciliation-demo-section" class="home-container demo-section" tabindex="-1" aria-labelledby="demo-title">
        <div class="section-heading reveal-section">
          <div><p class="public-kicker">账单核对</p><h2 id="demo-title">海棠咖啡店的日账单</h2></div>
          <p>导入四笔记录，看看 0.50 元差额如何进入复核。</p>
        </div>
        <div class="reveal-section"><ReconciliationDemo /></div>
      </section>

      <CapabilityChapters />

      <section class="home-container closing-section" aria-labelledby="closing-title">
        <div class="closing-content reveal-section">
          <div><p class="public-kicker">Reconis</p><h2 id="closing-title">进入账务工作台</h2></div>
          <router-link class="home-button primary" :to="destination">{{ actionLabel }}<el-icon><ArrowRight /></el-icon></router-link>
        </div>
      </section>
    </main>
    <footer class="home-container home-footer">
      <router-link class="footer-brand" to="/welcome">Reconis<span>校园支付对账</span></router-link>
      <GithubLink label />
    </footer>
  </div>
</template>

<style scoped>
.product-home {
  min-height: 100vh;
  background: radial-gradient(ellipse at 80% 6%, #283345 0, transparent 28%), var(--public-bg);
  overflow: clip;
}
.home-container { width: min(1240px, calc(100% - 96px)); margin-inline: auto; }
.home-skip {
  position: fixed; top: 12px; left: 16px; z-index: 50;
  padding: 10px 16px; border-radius: 6px; background: var(--public-accent); color: var(--public-bg);
  transform: translateY(-160%);
}
.home-skip:focus { transform: none; }
#product-content:focus, section[tabindex="-1"]:focus { outline: none; }
section[tabindex="-1"] { scroll-margin-top: 36px; }
.home-header { border-bottom: 1px solid var(--public-line); background: #11161ed9; }
.nav-inner { min-height: 76px; display: flex; align-items: center; gap: 60px; }
.home-brand, .footer-brand { display: flex; align-items: center; gap: 11px; font-size: 22px; font-weight: 650; letter-spacing: -.04em; }
.home-brand :deep(.brand-mark) { width: 34px; height: 34px; }
.home-nav, .header-actions { display: flex; align-items: center; gap: 26px; }
.home-nav { color: var(--public-muted); font-size: 14px; }
.home-nav a { padding-block: 10px; transition: color .18s; }
.home-nav a:hover { color: var(--public-text); }
.header-actions { margin-left: auto; gap: 12px; color: var(--public-muted); }
.home-button {
  display: inline-flex; align-items: center; justify-content: center; gap: 12px;
  min-height: 46px; padding: 0 17px; border-radius: 8px; border: 1px solid var(--public-line);
  background: var(--public-panel); color: var(--public-text); white-space: nowrap;
  font-size: 14px; font-weight: 600; transition: background .18s, border-color .18s, transform .18s;
}
.home-button:hover { border-color: #9fbde275; background: var(--public-raised); }
.home-button.primary { border-color: var(--public-accent); background: var(--public-accent); color: var(--public-on-accent); }
.home-button.primary:hover { background: var(--public-accent-hover); border-color: var(--public-accent-hover); transform: translateY(-2px); }
.nav-button { min-height: 37px; padding-inline: 12px; font-size: 13px; }
.home-hero { display: grid; grid-template-columns: minmax(0, .82fr) minmax(0, 1.18fr); align-items: center; gap: clamp(36px, 5vw, 76px); padding-block: 78px 90px; }
.hero-copy { animation: home-entry .55s ease both; }
.hero-copy h1 {
  margin-top: 20px; font-family: var(--font-display); font-size: clamp(38px, 4.5vw, 68px);
  font-weight: 650; letter-spacing: -.055em; line-height: 1.25;
}
.hero-copy h1 > span { display: block; color: var(--public-accent); }
.hero-description { max-width: 31em; margin-top: 22px; color: var(--public-muted); font-size: clamp(15px, 1.2vw, 17px); line-height: 1.9; }
.hero-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 20px; margin-top: 26px; }
.home-text-link { display: inline-flex; align-items: center; gap: 7px; min-height: 42px; color: var(--public-muted); font-size: 14px; }
.home-text-link:hover { color: var(--public-accent); }
.hero-scene {
  min-width: 0; margin: 0; overflow: hidden; border-radius: 17px; border: 1px solid #b2c7e53b;
  box-shadow: 0 24px 68px #06091080; background: var(--public-panel);
  animation: home-entry .65s .12s ease both;
}
.hero-photo, .shop-photo { position: relative; overflow: hidden; background: var(--public-raised); }
.hero-photo { aspect-ratio: 1.52; }
.hero-photo::after { content: ""; position: absolute; inset: 40% 0 0; background: linear-gradient(transparent, #0d111bdc); pointer-events: none; }
.hero-photo img, .shop-photo img { display: block; width: 100%; height: 100%; object-fit: cover; transition: transform .6s cubic-bezier(.2,.75,.25,1); }
.hero-scene:hover img, .campus-shop:hover img { transform: scale(1.04); }
.scene-caption { position: absolute; left: 24px; bottom: 22px; z-index: 1; display: grid; gap: 5px; }
.scene-caption span { color: #c5daf8; font-size: 12px; }
.scene-caption strong { font-family: var(--font-display); font-size: clamp(22px, 2.3vw, 31px); line-height: 1.25; font-weight: 600; }
.hero-ledger-link { display: flex; align-items: center; gap: 14px; justify-content: space-between; padding: 16px 22px; font-size: 13px; color: var(--public-muted); transition: color .2s, background .2s; }
.hero-ledger-link:hover { color: var(--public-text); background: var(--public-raised); }
.hero-ledger-link:focus-visible { outline-offset: -4px; }
.hero-ledger-link > span { display: flex; gap: 8px; align-items: center; }
.hero-ledger-link > span > i { display: inline-block; width: 5px; height: 5px; border-radius: 50%; background: var(--public-accent); flex: none; }
.hero-ledger-link .hero-ledger-result { margin-left: auto; }
.hero-ledger-link > .hero-ledger-result > i { background: #ffffff3d; width: 3px; height: 3px; }
.campus-section, .demo-section { padding-block: 58px 74px; border-top: 1px solid var(--public-line); }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 18px 36px; margin-bottom: 28px; }
.section-heading h2, .closing-section h2 { margin-top: 9px; font-family: var(--font-display); font-size: clamp(29px, 3.4vw, 46px); font-weight: 650; letter-spacing: -.036em; line-height: 1.3; }
.section-heading > p { color: var(--public-muted); max-width: 32em; font-size: 15px; }
.campus-shops { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.campus-shop { overflow: hidden; border: 1px solid var(--public-line); border-radius: 13px; background: var(--public-panel); transition: border-color .22s, transform .22s; }
.campus-shop:hover { border-color: #a6c4ec65; transform: translateY(-3px); }
.shop-photo { aspect-ratio: 1.75; }
.shop-copy { padding: 18px 20px 21px; }
.shop-copy > span { color: var(--public-accent); font-size: 12px; }
.shop-copy h3 { margin-top: 5px; font-size: 21px; line-height: 1.4; letter-spacing: -.025em; }
.shop-copy p { color: var(--public-muted); margin-top: 8px; font-size: 14px; line-height: 1.8; }
.closing-section { padding-block: 46px; }
.closing-content { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 30px 36px; border: 1px solid var(--public-line); border-radius: 14px; background: linear-gradient(112deg, var(--public-panel), #202e43); }
.home-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; border-top: 1px solid var(--public-line); padding-block: 20px; color: var(--public-muted); }
.footer-brand { font-size: 17px; color: var(--public-text); }
.footer-brand span { font-size: 12px; color: var(--public-muted); font-weight: 400; letter-spacing: normal; }
:deep(.reveal-section.in-view) { animation: home-reveal .42s ease both; }
@keyframes home-reveal { from { opacity: .75; transform: translateY(7px); } to { opacity: 1; transform: none; } }
@keyframes home-entry { from { opacity: .15; transform: translateY(10px); } to { opacity: 1; transform: none; } }

@media (max-width: 980px) {
  .home-container { width: calc(100% - 64px); }
  .nav-inner { gap: 30px; }
  .home-hero { gap: 28px; padding-block: 56px 60px; }
  .hero-ledger-result { display: none !important; }
  .campus-section, .demo-section { padding-block: 44px 56px; }
  .shop-copy { padding: 15px; }
}
@media (max-width: 740px) {
  .home-container { width: calc(100% - 36px); }
  .nav-inner { min-height: 62px; }
  .home-nav { display: none; }
  .home-hero { grid-template-columns: 1fr; padding-block: 35px 42px; gap: 24px; }
  .hero-description { max-width: 34em; margin-top: 16px; }
  .hero-copy h1 { margin-top: 12px; font-size: clamp(38px, 8vw, 56px); }
  .hero-actions { margin-top: 18px; }
  .section-heading { align-items: flex-start; flex-direction: column; gap: 9px; margin-bottom: 20px; }
  .campus-shops { grid-template-columns: 1fr; gap: 13px; }
  .campus-shop { display: grid; grid-template-columns: minmax(0, .83fr) minmax(0, 1.17fr); }
  .shop-photo { aspect-ratio: auto; min-height: 134px; }
  .shop-photo img { position: absolute; inset: 0; }
  .shop-copy h3 { font-size: 18px; }
  .shop-copy p { font-size: 13px; }
  .closing-content { padding: 22px; flex-wrap: wrap; }
}
@media (max-width: 370px) {
  .home-brand { font-size: 19px; gap: 8px; }
  .home-brand :deep(.brand-mark) { width: 28px; height: 28px; }
  .nav-inner { gap: 8px; }
  .header-actions { gap: 4px; }
  .hero-ledger-link { padding-inline: 13px; }
  .shop-copy { padding: 11px; }
}
@media (prefers-reduced-motion: reduce) {
  .hero-copy, .hero-scene, :deep(.reveal-section.in-view) { animation: none; }
  .home-button, .hero-photo img, .shop-photo img, .campus-shop { transition: none; }
  .home-button:hover, .hero-scene:hover img, .campus-shop:hover, .campus-shop:hover img { transform: none; }
}
</style>
