import { createRouter, createWebHistory } from "vue-router";
import { useSession } from "./stores/session";
export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: "/welcome",
      component: () => import("./pages/Home.vue"),
      meta: { title: "校园支付对账" },
    },
    {
      path: "/login",
      component: () => import("./pages/Login.vue"),
      meta: { title: "登录" },
    },
    {
      path: "/",
      component: () => import("./components/Layout.vue"),
      children: [
        {
          path: "",
          component: () => import("./pages/Dashboard.vue"),
          meta: { title: "总览工作台", section: "工作空间" },
        },
        {
          path: "merchants",
          component: () => import("./pages/Merchants.vue"),
          meta: { title: "商户与门店", section: "经营管理" },
        },
        {
          path: "orders",
          component: () => import("./pages/Orders.vue"),
          meta: { title: "收款订单", section: "交易管理" },
        },
        {
          path: "orders/:id",
          component: () => import("./pages/OrderDetail.vue"),
          meta: { title: "订单详情", section: "交易管理" },
        },
        {
          path: "refunds",
          component: () => import("./pages/Refunds.vue"),
          meta: { title: "退款工作台", section: "交易管理" },
        },
        {
          path: "reconciliation",
          component: () => import("./pages/Reconciliation.vue"),
          meta: { title: "账单与对账", section: "对账协作" },
        },
        {
          path: "cases",
          component: () => import("./pages/Cases.vue"),
          meta: { title: "差错工作台", section: "对账协作" },
        },
        {
          path: "cases/:id",
          component: () => import("./pages/CaseDetail.vue"),
          meta: { title: "工单详情", section: "对账协作" },
        },
        {
          path: "search",
          component: () => import("./pages/Search.vue"),
          meta: { title: "案例知识库", section: "对账协作" },
        },
        {
          path: "analytics",
          component: () => import("./pages/Analytics.vue"),
          meta: { title: "经营与差错分析", section: "数据洞察" },
        },
        {
          path: "audit",
          component: () => import("./pages/Audit.vue"),
          meta: { title: "审计中心", section: "系统管理" },
        },
        {
          path: "users",
          component: () => import("./pages/Users.vue"),
          meta: { title: "账号审核", section: "系统管理" },
        },
        {
          path: "integration",
          component: () => import("./pages/Integration.vue"),
          meta: { title: "支付接入", section: "系统管理" },
        },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  const s = useSession();
  if (to.path === "/welcome") {
    if (!s.user) await s.load();
    return true;
  }
  if (to.path === "/login") {
    if (to.query.expired === "1") return true;
    if (s.user || await s.load()) return "/";
    return true;
  }
  if (!s.user && !(await s.load())) {
    return to.path === "/" ? "/welcome" : { path: "/login", query: { redirect: to.fullPath } };
  }
  if (
    s.admin &&
    [
      "/orders",
      "/refunds",
      "/reconciliation",
      "/cases",
      "/analytics",
      "/search",
    ].some((p) => to.path.startsWith(p))
  )
    return "/";
  if (!s.admin && to.path === "/users") return "/";
  if (!s.admin && !s.auditor && to.path === "/audit") return "/";
  return true;
});
router.afterEach((to) => {
  document.title = `${String(to.meta.title || "校园支付对账")} · Reconis`;
});
