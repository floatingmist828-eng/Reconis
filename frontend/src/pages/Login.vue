<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { User, Lock, ArrowRight, Check, DocumentChecked } from "@element-plus/icons-vue";
import { post, action } from "../api";
import { useSession } from "../stores/session";
import BrandMark from "../components/BrandMark.vue";
import GithubLink from "../components/GithubLink.vue";
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
  <main class="login-screen">
    <section class="login-story" aria-labelledby="login-story-title">
      <router-link to="/welcome" class="login-brand" aria-label="返回 Reconis 产品首页">
        <BrandMark />
        <span>Reconis<small>校园支付对账协作</small></span>
      </router-link>

      <div class="login-copy">
        <h1 id="login-story-title">把每笔账，<br />核对清楚。</h1>
        <p>校园收款、账单核对与差错处理。</p>

        <figure class="reconcile-illustration" aria-label="订单与渠道账单逐笔核对示意">
          <div class="reconcile-documents" aria-hidden="true">
            <div class="ledger-sheet ledger-local">
              <div class="sheet-heading"><el-icon><DocumentChecked /></el-icon><span>本地订单</span></div>
              <div class="sheet-divider"></div>
              <div class="ledger-line"><i></i><span></span><b><el-icon><Check /></el-icon></b></div>
              <div class="ledger-line"><i></i><span></span><b><el-icon><Check /></el-icon></b></div>
              <div class="ledger-line"><i></i><span></span><b><el-icon><Check /></el-icon></b></div>
              <div class="sheet-total"><span></span><i></i></div>
            </div>
            <div class="matching-connector"><span></span><b><el-icon><Check /></el-icon></b><span></span></div>
            <div class="ledger-sheet ledger-channel">
              <div class="sheet-heading"><el-icon><DocumentChecked /></el-icon><span>渠道账单</span></div>
              <div class="sheet-divider"></div>
              <div class="ledger-line"><i></i><span></span><b><el-icon><Check /></el-icon></b></div>
              <div class="ledger-line"><i></i><span></span><b><el-icon><Check /></el-icon></b></div>
              <div class="ledger-line"><i></i><span></span><b><el-icon><Check /></el-icon></b></div>
              <div class="sheet-total"><span></span><i></i></div>
            </div>
          </div>
          <figcaption>订单与渠道账单核对示意</figcaption>
        </figure>
      </div>

      <div class="login-capabilities" aria-label="主要功能">
        <span><el-icon><Check /></el-icon>收款与退款</span>
        <span><el-icon><Check /></el-icon>账单核对</span>
        <span><el-icon><Check /></el-icon>差错协作</span>
      </div>
    </section>

    <section class="login-panel" aria-labelledby="login-form-title">
      <div class="login-panel-nav"><router-link to="/welcome">返回产品首页</router-link><GithubLink /></div>
      <div class="login-card">
        <div class="login-form-icon" aria-hidden="true"><el-icon><Lock /></el-icon></div>
        <Transition name="mode-heading" mode="out-in">
          <div :key="register ? 'register' : 'login'" class="login-heading">
            <h2 id="login-form-title">{{ register ? "申请账号" : "登录 Reconis" }}</h2>
            <p class="login-subtitle">{{ register ? "提交后由管理员审核。" : "进入你的校园支付工作台。" }}</p>
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
        <p class="login-note"><el-icon><Lock /></el-icon>账号审核通过后即可登录</p>
      </div>
      <footer class="login-footer"><span>© 2026 Reconis</span><span>校园收款与对账</span></footer>
    </section>
  </main>
</template>

<style scoped>
.login-panel-nav { position: absolute; inset: 12px 24px auto; display: flex; justify-content: space-between; align-items: center; color: var(--muted); font-size: 12px; }
.login-panel-nav > a:first-child { padding: 10px 0; }
.login-panel-nav > a:hover { color: var(--brand); }
.login-screen {
  min-height: 100svh;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  padding: 20px;
  background: var(--canvas, #f4f6fa);
  color: var(--ink, #172b4d);
}
.login-story {
  min-height: calc(100svh - 40px);
  display: flex;
  flex-direction: column;
  padding: clamp(32px, 3.8vw, 64px);
  border-radius: 18px;
  background: #14243b;
  color: #fff;
  overflow: hidden;
  animation: login-arrive 560ms cubic-bezier(.2,.7,.3,1) both;
}
.login-brand {
  display: flex;
  align-items: center;
  gap: 13px;
  font-size: 27px;
  font-weight: 650;
  letter-spacing: -0.8px;
}
.login-brand :deep(.brand-mark) { width: 44px; height: 44px; flex: none; color: var(--brand, #325ddb); }
.login-brand small { display: block; margin-top: 4px; color: #b9c9de; font-size: 12px; letter-spacing: 0.4px; font-weight: 400; }
.login-copy { margin: auto 0; padding: 52px 0 32px; }
.login-copy h1 { margin: 0; font-size: clamp(36px, 3.7vw, 56px); line-height: 1.35; font-weight: 600; letter-spacing: -1.5px; }
.login-copy > p { margin-top: 22px; color: #b9c9de; font-size: 15px; line-height: 1.9; }
.reconcile-illustration { margin: 40px 0 0; max-width: 500px; }
.reconcile-documents { position: relative; display: grid; grid-template-columns: 1fr 1fr; gap: 32px; padding: 8px 3px; }
.ledger-sheet { position: relative; padding: 20px 18px 16px; border: 1px solid #dfe7f4; border-radius: 10px; background: #f8faff; color: #314564; box-shadow: 0 15px 30px #08132326; transition: transform 320ms cubic-bezier(.2,.7,.3,1), box-shadow 320ms ease; }
.ledger-local { transform: rotate(-5deg); }
.ledger-channel { transform: rotate(5deg) translateY(20px); background: #eaf0ff; }
.sheet-heading { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; white-space: nowrap; }
.sheet-heading .el-icon { font-size: 17px; color: #325ddb; }
.sheet-divider { height: 1px; margin: 15px 0 10px; background: #d9e2f0; }
.ledger-line { display: flex; align-items: center; gap: 8px; height: 31px; }
.ledger-line > i { width: 5px; height: 5px; flex: none; border-radius: 50%; background: #b2c2dd; }
.ledger-line > span { height: 6px; width: 57%; border-radius: 3px; background: #d6e0f1; }
.ledger-line:nth-of-type(4) > span { width: 45%; }
.ledger-line:nth-of-type(5) > span { width: 65%; }
.ledger-line > b { display: grid; place-items: center; margin-left: auto; color: #317d73; font-size: 13px; }
.sheet-total { display: flex; justify-content: space-between; align-items: center; border-top: 1px dashed #c9d5e7; padding-top: 14px; margin-top: 10px; }
.sheet-total span { width: 30px; height: 5px; border-radius: 3px; background: #bdcce3; }
.sheet-total i { width: 50px; height: 8px; border-radius: 3px; background: #718fc5; }
.matching-connector { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; z-index: 1; pointer-events: none; }
.matching-connector > span { width: 14px; height: 1px; background: #6e8ebd; }
.matching-connector > b { width: 44px; height: 44px; display: grid; place-items: center; border: 4px solid #14243b; border-radius: 50%; color: #fff; background: #325ddb; box-shadow: 0 0 0 1px #446391; font-size: 22px; transition: transform 320ms ease, box-shadow 320ms ease; }
.reconcile-illustration figcaption { margin-top: 37px; text-align: center; font-size: 12px; color: #a9bdd9; }
.login-capabilities { display: flex; flex-wrap: wrap; gap: 12px 24px; padding-top: 24px; border-top: 1px solid #31425b; color: #c7d4e7; font-size: 12px; }
.login-capabilities span { display: inline-flex; align-items: center; gap: 7px; }
.login-capabilities .el-icon { color: #91b2ed; }
.login-panel { min-width: 0; position: relative; display: flex; flex-direction: column; justify-content: center; padding: 50px clamp(28px, 6vw, 110px) 20px; }
.login-card { width: 100%; max-width: 390px; margin: auto; animation: login-arrive 560ms 90ms cubic-bezier(.2,.7,.3,1) both; }
.login-form-icon { display: grid; place-items: center; width: 48px; height: 48px; margin-bottom: 24px; border: 1px solid #dee6f6; border-radius: 12px; background: #eaf0fc; color: var(--brand, #325ddb); font-size: 23px; }
.login-card h2 { margin: 0; color: var(--ink, #172b4d); font-size: clamp(25px, 2.1vw, 30px); font-weight: 650; line-height: 1.4; letter-spacing: -0.6px; }
.login-subtitle { margin: 12px 0 32px; color: var(--muted, #64748b); font-size: 14px; line-height: 1.8; }
.login-expired { margin-bottom: 20px; }
.login-card :deep(.el-form-item) { margin-bottom: 23px; }
.display-name-field { max-height: 110px; }
.name-field-enter-active, .name-field-leave-active { overflow: hidden; transition: max-height 240ms ease, margin-bottom 240ms ease, opacity 180ms ease, transform 240ms ease; }
.login-card :deep(.name-field-enter-from), .login-card :deep(.name-field-leave-to) { max-height: 0; margin-bottom: 0; opacity: 0; transform: translateY(-6px); }
.mode-heading-enter-active { transition: opacity 140ms ease, transform 140ms ease; }
.mode-heading-leave-active { transition: opacity 90ms ease; }
.mode-heading-enter-from { opacity: 0; transform: translateY(3px); }
.mode-heading-leave-to { opacity: 0; }
.login-card :deep(.el-form-item__label) { margin-bottom: 9px; font-size: 14px; font-weight: 500; color: #354560; }
.login-card :deep(.el-input__wrapper) { min-height: 48px; padding: 0 15px; border-radius: 8px; background: #fff; box-shadow: 0 0 0 1px #dce3ee inset; }
.login-card :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #a5b6d4 inset; }
.login-card :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px #325ddb inset, 0 0 0 3px #325ddb15; }
.login-card :deep(.el-input__inner) { font-size: 14px; }
.login-card :deep(.el-input__prefix) { color: #8292a9; padding-right: 3px; }
.login-submit { width: 100%; min-height: 48px; margin-top: 5px; border-radius: 8px; font-size: 15px; font-weight: 600; box-shadow: 0 4px 10px #325ddb18; }
.login-submit :deep(> span) { display: flex; align-items: center; justify-content: center; gap: 12px; }
.login-switch { display: flex; align-items: center; justify-content: center; gap: 4px; margin-top: 24px; color: var(--muted, #64748b); font-size: 13px; }
.login-switch .el-button { min-height: 28px; font-size: 13px; }
.login-note { display: flex; align-items: center; justify-content: center; gap: 6px; margin: 34px 0 0; padding-top: 24px; border-top: 1px solid var(--line, #e5eaf2); color: #6d7b91; font-size: 12px; line-height: 1.7; }
.login-note .el-icon { font-size: 13px; }
.login-footer { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px 20px; margin-top: 48px; color: #738198; font-size: 12px; line-height: 1.6; }
@keyframes login-arrive {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
@media (hover: hover) and (pointer: fine) {
  .reconcile-illustration:hover .ledger-local { transform: rotate(-2deg) translateY(-3px); box-shadow: 0 18px 32px #08132335; }
  .reconcile-illustration:hover .ledger-channel { transform: rotate(2deg) translateY(12px); box-shadow: 0 18px 32px #08132335; }
  .reconcile-illustration:hover .matching-connector > b { transform: scale(1.06); box-shadow: 0 0 0 1px #7194cb, 0 0 0 7px #325ddb15; }
  .login-submit:not(:disabled):hover .el-icon { transform: translateX(3px); }
  .login-submit .el-icon { transition: transform 160ms ease; }
}
@media (min-width: 1600px) {
  .login-screen { padding: 28px; }
  .login-story { min-height: calc(100svh - 56px); }
  .login-copy { padding-top: 70px; padding-bottom: 48px; }
  .reconcile-illustration { margin-top: 52px; }
}
@media (max-width: 1050px) {
  .login-story { padding: 32px; }
  .login-panel { padding-right: 28px; padding-left: 38px; }
  .login-copy h1 { font-size: 38px; }
  .reconcile-documents { gap: 24px; }
  .ledger-sheet { padding: 15px 12px; }
  .sheet-heading { gap: 5px; font-size: 12px; }
  .login-capabilities { gap: 12px 18px; }
}
@media (max-width: 740px) {
  .login-screen { display: flex; flex-direction: column; padding: 12px; }
  .login-story { min-height: 0; padding: 25px 24px; border-radius: 12px; }
  .login-brand { font-size: 24px; }
  .login-brand :deep(.brand-mark) { width: 38px; height: 38px; }
  .login-copy { margin: 0; padding: 24px 0 0; }
  .login-copy h1 { font-size: 28px; letter-spacing: -0.5px; }
  .login-copy h1 br { display: none; }
  .login-copy > p, .reconcile-illustration, .login-capabilities { display: none; }
  .login-panel { flex: 1; padding: 68px 20px 12px; }
  .login-card { max-width: 420px; }
  .login-form-icon { display: none; }
  .login-card h2 { font-size: 24px; }
  .login-subtitle { margin: 10px 0 24px; font-size: 13px; }
  .login-card :deep(.el-form-item) { margin-bottom: 20px; }
  .login-note { margin-top: 25px; padding-top: 20px; }
  .login-footer { margin-top: 30px; }
}
@media (max-width: 360px) {
  .login-story { padding: 22px 18px; }
  .login-copy h1 { font-size: 24px; }
  .login-panel { padding-right: 14px; padding-left: 14px; }
  .login-card h2 { font-size: 22px; }
}
@media (prefers-reduced-motion: reduce) {
  .login-screen :deep(*) { animation: none !important; transition: none !important; }
}
</style>
