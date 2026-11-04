<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { User, Lock, ArrowRight } from "@element-plus/icons-vue";
import { post, action } from "../api";
import { useSession } from "../stores/session";
import BrandMark from "../components/BrandMark.vue";
import GithubLink from "../components/GithubLink.vue";
import coffeePhoto from "../assets/campus/haitang.webp";
const router = useRouter(),
  route = useRoute(),
  session = useSession(),
  register = ref(false),
  busy = ref(false);
const form = reactive({ username: "", password: "", displayName: "" });
const submitError = ref("");
watch(register, () => { submitError.value = ""; });
async function submit() {
  if (busy.value) return;
  submitError.value = "";
  busy.value = true;
  try {
    if (register.value) {
      await post("/auth/register", form);
      ElMessage.success("注册成功，请等待管理员审核");
      register.value = false;
    } else {
      await post("/auth/login", form);
      await session.load();
      const destination = typeof route.query.redirect === "string" ? route.query.redirect : "/";
      router.push(destination.startsWith("/") && !destination.startsWith("//") && !destination.startsWith("/login") ? destination : "/");
    }
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : "暂时无法登录，请重试";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-screen public-theme">
    <section class="login-story" aria-labelledby="login-story-title">
      <router-link to="/welcome" class="login-brand" aria-label="返回 Reconis 产品首页">
        <BrandMark />
        <span>Reconis<small>校园支付对账协作</small></span>
      </router-link>

      <img class="login-photo" :src="coffeePhoto" width="1536" height="1024" alt="" />
      <div class="login-copy">
        <p class="public-kicker"><i />海棠咖啡店 · 图书馆旁</p>
        <h1 id="login-story-title">校园账务工作台</h1>
        <p>收款、退款、账单核对与差错复核。</p>
      </div>
    </section>

    <section class="login-panel" aria-labelledby="login-form-title">
      <div class="login-panel-nav"><router-link to="/welcome">返回产品首页</router-link><GithubLink /></div>
      <div class="login-card">
        <div class="login-form-icon" aria-hidden="true"><el-icon><Lock /></el-icon></div>
        <Transition name="mode-heading" mode="out-in">
          <div :key="register ? 'register' : 'login'" class="login-heading">
            <h2 id="login-form-title">{{ register ? "申请账号" : "登录 Reconis" }}</h2>
            <p v-if="register" class="login-subtitle">提交后由管理员审核。</p>
          </div>
        </Transition>

        <el-alert v-if="route.query.expired === '1'" class="login-expired" title="登录已失效，请重新登录" type="warning" :closable="false" show-icon />
        <el-alert v-if="submitError" class="login-expired" :title="submitError" type="error" :closable="false" show-icon role="alert" />
        <el-form label-position="top" @submit.prevent="action(submit)">
          <el-form-item label="用户名">
            <el-input
              v-model="form.username"
              :prefix-icon="User"
              placeholder="请输入用户名"
              autocomplete="username"
              size="large"
              maxlength="32"
            />
          </el-form-item>
          <Transition name="name-field">
            <el-form-item v-if="register" class="display-name-field" label="显示姓名">
              <el-input
                v-model="form.displayName"
                :prefix-icon="User"
                placeholder="便于协作人员识别"
                autocomplete="name"
                maxlength="80"
                size="large"
              />
            </el-form-item>
          </Transition>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              :prefix-icon="Lock"
              type="password"
              show-password
              :placeholder="register ? '至少10位密码' : '请输入密码'"
              :autocomplete="register ? 'new-password' : 'current-password'"
              size="large"
              maxlength="64"
            />
          </el-form-item>
          <el-button class="login-submit" type="primary" size="large" native-type="submit" :loading="busy">
            {{ register ? "提交注册申请" : "进入工作空间" }}
            <el-icon v-if="!busy"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <div class="login-switch">
          <span>{{ register ? "已有账号？" : "还没有账号？" }}</span>
          <el-button link type="primary" @click="register = !register">{{ register ? "返回登录" : "注册账号" }}</el-button>
        </div>
      </div>
      <footer class="login-footer"><span>© 2026 Reconis</span><span>校园收款与对账</span></footer>
    </section>
  </main>
</template>

<style scoped>
.login-screen {
  min-height: 100svh;
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(0, 1fr);
  background: radial-gradient(ellipse at 86% 4%, #253145, transparent 52%), var(--public-bg);
}
.login-story {
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 36px;
  padding: clamp(28px, 5vw, 74px);
  min-height: 650px;
  border-right: 1px solid var(--public-line);
}
.login-story::after {
  content: "";
  position: absolute;
  z-index: -1;
  inset: 0;
  background: linear-gradient(180deg, #101620c9 0, transparent 40%, #11161ef5 100%);
}
.login-photo { position: absolute; inset: 0; z-index: -2; width: 100%; height: 100%; object-fit: cover; object-position: 61% center; }
.login-brand { display: inline-flex; align-items: center; gap: 13px; align-self: start; font-size: 23px; font-weight: 650; }
.login-brand :deep(.brand-mark) { color: #a16f48; }
.login-brand small { display: block; font-size: 12px; font-weight: 400; color: var(--public-muted); }
.login-copy { padding-bottom: 14px; }
.login-copy h1 { font-family: var(--font-display); font-size: clamp(31px, 4vw, 60px); margin-top: 13px; }
.login-copy > p:last-child { margin-top: 9px; color: #d2d9e3; font-size: 15px; }
.login-panel { display: flex; flex-direction: column; align-items: center; min-width: 0; padding: 26px clamp(24px, 5vw, 76px) 18px; }
.login-panel-nav, .login-footer { display: flex; justify-content: space-between; align-items: center; gap: 16px; width: 100%; color: var(--public-muted); font-size: 13px; }
.login-panel-nav > a:first-child:hover { color: var(--public-accent); }
.login-card { width: min(100%, 418px); margin: auto; padding: 28px 0; }
.login-form-icon { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 10px; background: #e7bd911a; border: 1px solid #e7bd9130; color: var(--public-accent); font-size: 20px; }
.login-heading { margin: 16px 0 26px; }
.login-heading h2 { font-size: clamp(26px, 2.5vw, 34px); letter-spacing: -.035em; }
.login-subtitle { margin-top: 6px; color: var(--public-muted); font-size: 14px; }
.login-expired { margin-bottom: 14px; }
.login-card :deep(.el-alert--warning.is-light) { --el-alert-bg-color: #382e23; color: #f3d09f; }
.login-card :deep(.el-alert--error.is-light) { --el-alert-bg-color: #39262f; color: #f4b5b5; }
.login-card :deep(.el-form-item) { margin-bottom: 20px; }
.login-card :deep(.el-form-item__label) { color: var(--public-text); margin-bottom: 8px; font-size: 14px; }
.login-card :deep(.el-input) {
  --el-input-bg-color: var(--public-panel);
  --el-input-text-color: var(--public-text);
  --el-input-placeholder-color: #a5b0bf;
  --el-input-icon-color: var(--public-muted);
  --el-input-border-color: var(--public-line);
  --el-input-hover-border-color: #738196;
}
.login-card :deep(.el-input__wrapper) { min-height: 46px; }
.login-submit.el-button {
  --el-button-bg-color: var(--public-accent);
  --el-button-border-color: var(--public-accent);
  --el-button-text-color: #251e19;
  --el-button-hover-bg-color: #f1ceab;
  --el-button-hover-border-color: #f1ceab;
  --el-button-hover-text-color: #251e19;
  --el-button-active-bg-color: #dca776;
  --el-button-active-border-color: #dca776;
  --el-button-disabled-bg-color: #be9c78;
  --el-button-disabled-border-color: #be9c78;
  --el-button-disabled-text-color: #251e19;
  justify-content: space-between;
  width: 100%;
  height: 47px;
  font-size: 15px;
  font-weight: 650;
}
.login-submit > :deep(span) { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.login-switch { display: flex; align-items: center; gap: 8px; margin-top: 20px; color: var(--public-muted); }
.login-switch .el-button { --el-button-text-color: var(--public-accent); --el-button-hover-text-color: #f1ceab; }
.login-footer { padding-top: 14px; border-top: 1px solid var(--public-line); font-size: 12px; }
.mode-heading-enter-active, .mode-heading-leave-active { transition: opacity .14s, transform .14s; }
.mode-heading-enter-from, .mode-heading-leave-to { opacity: 0; transform: translateY(4px); }
.name-field-enter-active, .name-field-leave-active { transition: opacity .16s; }
.name-field-enter-from, .name-field-leave-to { opacity: 0; }

@media (max-width: 760px) {
  .login-screen { grid-template-columns: 1fr; }
  .login-story { min-height: 215px; padding: 22px 24px; gap: 30px; border-right: 0; border-bottom: 1px solid var(--public-line); }
  .login-photo { object-position: center 57%; }
  .login-copy { padding-bottom: 0; }
  .login-copy h1 { margin-top: 8px; }
  .login-panel { padding: 12px 24px 16px; }
  .login-panel-nav { max-width: 418px; }
  .login-card { padding: 16px 0 28px; }
  .login-footer { max-width: 418px; }
}
@media (prefers-reduced-motion: reduce) {
  .mode-heading-enter-active, .mode-heading-leave-active, .name-field-enter-active, .name-field-leave-active { transition: none; }
}
</style>
