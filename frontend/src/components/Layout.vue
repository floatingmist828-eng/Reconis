<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, nextTick, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Grid,
  Shop,
  Tickets,
  RefreshLeft,
  DocumentChecked,
  Warning,
  DataAnalysis,
  Lock,
  User,
  Connection,
  ArrowRight,
  SwitchButton,
  Fold,
  Close,
} from "@element-plus/icons-vue";
import { api, action } from "../api";
import { useSession } from "../stores/session";
import { ref } from "vue";
import BrandMark from "./BrandMark.vue";
import GithubLink from "./GithubLink.vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  collapsed = ref(false),
  compact = ref(false),
  mobile = ref(window.matchMedia("(max-width: 760px)").matches);
const mobileQuery = window.matchMedia("(max-width: 760px)");
const workspace = ref<HTMLElement>();
let focusAfterNavigation = false;
const originalOverflow = document.body.style.overflow;
watch(() => route.path, () => { collapsed.value = false; focusAfterNavigation = true; });
function focusWorkspace() {
  if (focusAfterNavigation) workspace.value?.focus({ preventScroll: true });
  focusAfterNavigation = false;
}
watch([mobile, collapsed], ([isMobile, open]) => {
  document.body.style.overflow = isMobile && open ? "hidden" : originalOverflow;
});
function resizeNavigation(event: MediaQueryListEvent) {
  mobile.value = event.matches;
  if (!event.matches) collapsed.value = false;
}
function toggleNavigation() {
  if (mobile.value) collapsed.value = !collapsed.value;
  else compact.value = !compact.value;
}
function closeNavigation(event: KeyboardEvent) {
  if (event.key === "Escape") collapsed.value = false;
  if (event.key === "Tab" && mobile.value && collapsed.value) {
    const focusable = Array.from(document.querySelectorAll<HTMLElement>(".sidebar a[href], .sidebar button:not([disabled])"));
    const first = focusable[0], last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
}
watch(collapsed, async (open) => {
  if (!mobile.value) return;
  await nextTick();
  document.querySelector<HTMLButtonElement>(open ? ".sidebar-close" : ".nav-toggle")?.focus();
});
onMounted(() => {
  mobileQuery.addEventListener("change", resizeNavigation);
  window.addEventListener("keydown", closeNavigation);
});
onBeforeUnmount(() => {
  document.body.style.overflow = originalOverflow;
  mobileQuery.removeEventListener("change", resizeNavigation);
  window.removeEventListener("keydown", closeNavigation);
});
const items = computed(() =>
  [
    {
      path: "/",
      text: session.consumer ? "我的概览" : "总览",
      icon: Grid,
      group: "",
      show: true,
    },
    {
      path: "/orders",
      text: session.consumer ? "我的订单" : "收款订单",
      icon: Tickets,
      group: "交易",
      show: !session.admin,
    },
    {
      path: "/refunds",
      text: session.consumer ? "我的退款" : "退款",
      icon: RefreshLeft,
      group: "",
      show: !session.admin,
    },
    {
      path: "/merchants",
      text: "商户与门店",
      icon: Shop,
      group: session.admin ? "管理" : "",
      show: true,
    },
    {
      path: "/reconciliation",
      text: "账单与对账",
      icon: DocumentChecked,
      group: "对账",
      show: !session.admin && !session.consumer,
    },
    {
      path: "/cases",
      text: "差错工单",
      icon: Warning,
      group: "",
      show: !session.admin && !session.consumer,
    },
    {
      path: "/analytics",
      text: "经营分析",
      icon: DataAnalysis,
      group: "",
      show: !session.admin && !session.consumer,
    },
    {
      path: "/users",
      text: "账号审核",
      icon: User,
      group: "",
      show: session.admin,
    },
    {
      path: "/audit",
      text: "审计中心",
      icon: Lock,
      group: session.admin ? "" : "管理",
      show: session.admin || session.auditor,
    },
    {
      path: "/integration",
      text: "渠道状态",
      icon: Connection,
      group: "",
      show: session.admin,
    },
  ].filter((i) => i.show),
);
const role = computed(
  () =>
    ({
      ADMIN: "平台管理员",
      AUDITOR: "只读审计员",
      PLATFORM_FINANCE: "平台财务",
      MEMBER: session.consumer ? "消费者" : "商户成员",
    })[String(session.user?.platformRole)],
);
onMounted(() =>
  action(async () => {
    session.integration = await api("/integration/status");
  }),
);
async function logout() {
  await session.logout();
  router.push("/login");
}
</script>
<template>
  <div class="app-shell" :class="{ 'nav-collapsed': collapsed, 'sidebar-compact': compact && !mobile }">
    <a class="skip-link" href="#workspace-content" :inert="mobile && collapsed">跳到主要内容</a>
    <aside id="workspace-navigation" class="sidebar" aria-label="工作空间导航" :inert="mobile && !collapsed">
      <el-button class="sidebar-close" text :icon="Close" aria-label="关闭导航" @click="collapsed = false" />
      <router-link to="/" class="brand" aria-label="Reconis 工作台">
        <BrandMark />
        <span class="brand-name">Reconis<small>校园账务</small></span>
      </router-link>
      <nav>
        <template v-for="item in items" :key="item.path"
          ><div v-if="item.group" class="nav-group">{{ item.group }}</div>
          <router-link
            :to="item.path"
            :aria-label="item.text"
            :title="compact && !mobile ? item.text : undefined"
            class="nav-item"
            :class="{
              active:
                item.path === '/'
                  ? route.path === '/'
                  : route.path.startsWith(item.path)
                    || (item.path === '/cases' && route.path === '/search')
                    || (item.path === '/reconciliation' && route.path === '/integration'),
            }"
            @click="collapsed = false"
            ><el-icon><component :is="item.icon" /></el-icon
            ><span class="nav-label">{{ item.text }}</span></router-link
          ></template
        >
      </nav>
      <div class="sidebar-bottom">
        <div class="sidebar-links"><router-link to="/welcome">了解 Reconis</router-link><GithubLink /></div>
      </div>
    </aside>
    <button v-if="collapsed" class="mobile-mask" aria-label="收起导航" @click="collapsed = false"></button>
    <div class="main-shell" :inert="mobile && collapsed">
      <header class="topbar">
        <div class="breadcrumbs">
          <el-button
            class="nav-toggle"
            text
            :icon="Fold"
            aria-label="切换导航"
            :aria-expanded="mobile ? collapsed : !compact"
            aria-controls="workspace-navigation"
            @click="toggleNavigation"
          /><span v-if="route.meta.section !== '工作空间'">{{ route.meta.section }}</span><el-icon v-if="route.meta.section !== '工作空间'"><ArrowRight /></el-icon
          ><strong>{{ session.consumer && route.path === '/' ? '我的概览' : route.meta.title }}</strong>
        </div>
        <div class="topbar-right">
          <span v-if="session.integration.environment === 'ALIPAY_SANDBOX'" class="environment-badge">支付宝沙箱</span>
          <div class="avatar">{{ session.user?.displayName?.slice(-1) }}</div>
          <div class="user-caption">
            <b>{{ session.user?.displayName }}</b
            ><small>{{ role }}</small>
          </div>
          <el-button
            text
            :icon="SwitchButton"
            aria-label="退出登录"
            @click="action(logout)"
          />
        </div>
      </header>
      <main id="workspace-content" ref="workspace" class="workspace" tabindex="-1">
        <router-view v-slot="{ Component }">
          <Transition name="page-switch" mode="out-in" @after-enter="focusWorkspace">
            <component :is="Component" :key="route.path" />
          </Transition>
        </router-view>
      </main>
    </div>
  </div>
</template>
