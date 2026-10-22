<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { post, action } from "../api";
import { useSession } from "../stores/session";
const router = useRouter(),
  route = useRoute(),
  session = useSession(),
  register = ref(false),
  busy = ref(false);
const form = reactive({ username: "", password: "", displayName: "" });
async function submit() {
  busy.value = true;
  try {
    if (register.value) {
      await post("/auth/register", form);
      ElMessage.success("注册成功，请等待管理员审核");
      register.value = false;
    } else {
      await post("/auth/login", form);
      await session.load();
      router.push("/");
    }
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="login-screen">
    <section class="login-story">
      <div class="brand">
        <span class="brand-symbol">R<span></span></span
        ><span>Reconis<small>校园支付对账协作</small></span>
      </div>
      <div class="login-copy">
        <span class="eyebrow light">CONNECTED. RECONCILED.</span>
        <h1>每一笔，<br />都有据可循<span>。</span></h1>
        <p>
          从校园门店的一次收款，到跨日退款的最后一分。<br />让交易可追溯，让差错有回应，让协作更清晰。
        </p>
        <div class="ledger-art">
          <div class="art-caption">交易事实 → 渠道核对 → 协作闭环</div>
          <div><span class="art-dot"></span><b>订单与收款</b><i>已确认</i></div>
          <div><span class="art-dot"></span><b>逐笔对账</b><i>有据可查</i></div>
          <div><span class="art-dot"></span><b>差错协作</b><i>独立复核</i></div>
        </div>
      </div>
      <div class="login-bottom">
        CAMPUS FINANCIAL WORKSPACE <span>01 / RECONIS</span>
      </div>
    </section>
    <section class="login-panel">
      <div class="login-card">
        <span class="eyebrow">WELCOME TO RECONIS</span>
        <h2>{{ register ? "创建你的账号" : "欢迎回到工作空间" }}</h2>
        <p class="muted">
          {{
            register ? "填写资料，提交账号申请。" : "查看交易，处理对账与待办。"
          }}
        </p>
        <el-alert v-if="route.query.expired === '1'" title="登录已失效，请重新登录" type="warning" :closable="false" style="margin-bottom: 18px" />
        <el-form label-position="top" @submit.prevent="action(submit)"
          ><el-form-item label="用户名"
            ><el-input
              v-model="form.username"
              placeholder="请输入用户名"
              autocomplete="username"
              size="large"
              maxlength="32" /></el-form-item
          ><el-form-item v-if="register" label="显示姓名"
            ><el-input
              v-model="form.displayName"
              placeholder="便于协作人员识别"
              maxlength="80"
              size="large" /></el-form-item
          ><el-form-item label="密码"
            ><el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="register ? '至少10位密码' : '请输入密码'"
              autocomplete="current-password"
              size="large"
              maxlength="64" /></el-form-item
          ><el-button
            class="login-submit"
            type="primary"
            size="large"
            native-type="submit"
            :loading="busy"
            >{{ register ? "提交注册申请" : "进入工作空间" }}
            <span>→</span></el-button
          ></el-form
        >
        <div class="login-switch">
          {{ register ? "已有账号？" : "还没有账号？"
          }}<el-button link type="primary" @click="register = !register">{{
            register ? "返回登录" : "注册账号"
          }}</el-button>
        </div>
        <div class="login-note">
          <b>交易清晰，协作有序</b>
          <p>收款、退款、对账，在一个工作空间完成。</p>
        </div>
      </div>
      <small class="muted">校园多商户跨日支付对账与差错协同处理系统</small>
    </section>
  </div>
</template>
