<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Grid,
  Shop,
  Tickets,
  RefreshLeft,
  DocumentChecked,
  Warning,
  Search,
  DataAnalysis,
  Lock,
  User,
  Connection,
  ArrowRight,
  SwitchButton,
  Bell,
  Fold,
} from "@element-plus/icons-vue";
import { api, action } from "../api";
import { useSession } from "../stores/session";
import { ref } from "vue";
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  collapsed = ref(false);
const items = computed(() =>
  [
    {
      path: "/",
      text: "总览工作台",
      icon: Grid,
      group: "工作空间",
      show: true,
    },
    {
      path: "/orders",
      text: "收款订单",
      icon: Tickets,
      group: "交易管理",
      show: !session.admin,
    },
    {
      path: "/refunds",
      text: "退款工作台",
      icon: RefreshLeft,
      group: "",
      show: !session.admin,
    },
    {
      path: "/merchants",
      text: "商户与门店",
      icon: Shop,
      group: session.admin ? "经营管理" : "",
      show: true,
    },
    {
      path: "/reconciliation",
      text: "账单与对账",
      icon: DocumentChecked,
      group: "对账协作",
      show: !session.admin,
    },
    {
      path: "/cases",
      text: "差错工作台",
      icon: Warning,
      group: "",
      show: !session.admin,
    },
    {
      path: "/search",
      text: "案例知识库",
      icon: Search,
      group: "",
      show: !session.admin,
    },
    {
      path: "/analytics",
      text: "经营与差错分析",
      icon: DataAnalysis,
      group: "数据洞察",
      show: !session.admin,
    },
    {
      path: "/users",
      text: "账号审核",
      icon: User,
      group: "系统管理",
      show: session.admin,
    },
    {
      path: "/audit",
      text: "审计中心",
      icon: Lock,
      group: session.admin ? "" : "系统管理",
      show: session.admin || session.auditor,
    },
    {
      path: "/integration",
      text: "支付接入",
      icon: Connection,
      group: !session.admin && !session.auditor ? "系统管理" : "",
      show: true,
    },
  ].filter((i) => i.show),
);
const role = computed(
  () =>
    ({
      ADMIN: "平台管理员",
      AUDITOR: "只读审计员",
      PLATFORM_FINANCE: "平台财务",
      MEMBER: "商户 / 消费者",
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
  <div class="app-shell" :class="{ 'nav-collapsed': collapsed }">
    <aside class="sidebar">
      <router-link to="/" class="brand"
        ><span class="brand-symbol">R<span></span></span
        ><span>Reconis<small>校园支付对账协作</small></span></router-link
      >
      <div class="workspace-chip">
        <span class="workspace-icon">校</span>
        <div>校园商户工作空间<small>Campus workspace</small></div>
        <span class="online-dot"></span>
      </div>
      <nav>
        <template v-for="item in items" :key="item.path"
          ><div v-if="item.group" class="nav-group">{{ item.group }}</div>
          <router-link
            :to="item.path"
            class="nav-item"
            :class="{
              active:
                item.path === '/'
                  ? route.path === '/'
                  : route.path.startsWith(item.path),
            }"
            @click="collapsed = false"
            ><el-icon><component :is="item.icon" /></el-icon
            ><span>{{ item.text }}</span
            ><span v-if="item.path === '/cases'" class="nav-pill"
              >协作</span
            ></router-link
          ></template
        >
      </nav>
      <div class="sidebar-bottom">
        <div class="environment-mini">
          <span class="online-dot"></span
          >{{
            session.integration.environment === "DEMO"
              ? "DEMO · 教学演示"
              : session.integration.environment === "ALIPAY_SANDBOX"
                ? "支付宝沙箱"
                : "服务连接中"
          }}
        </div>
        <p>每一笔，都有据可循。</p>
      </div>
    </aside>
    <div v-if="collapsed" class="mobile-mask" @click="collapsed = false"></div>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumbs">
          <el-button
            class="mobile-toggle"
            text
            :icon="Fold"
            aria-label="切换导航"
            @click="collapsed = !collapsed"
          /><span>工作空间</span><el-icon><ArrowRight /></el-icon
          ><strong>{{ route.meta.section }}</strong>
        </div>
        <div class="topbar-right">
          <span class="environment-badge">{{
            session.integration.environment === "DEMO"
              ? "教学演示环境"
              : session.integration.environment === "ALIPAY_SANDBOX"
                ? "支付宝沙箱"
                : "后端连接中"
          }}</span
          ><el-button
            v-if="!session.admin"
            text
            :icon="Bell"
            aria-label="我的待办"
            @click="router.push('/cases?mine=true')"
          />
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
      <main class="workspace"><router-view :key="route.path" /></main>
      <footer class="page-footer">
        <span>© 2026 Reconis</span><span>校园收款与对账</span>
      </footer>
    </div>
  </div>
</template>
