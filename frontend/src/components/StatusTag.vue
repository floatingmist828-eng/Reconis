<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{ value?: string | null }>();
const labels: Record<string, string> = {
  ACTIVE: "已启用",
  PENDING: "待确认",
  PENDING_REVIEW: "待审核",
  REJECTED: "已退回",
  DISABLED: "已停用",
  DRAFT: "草稿",
  WAITING_PAYMENT: "待付款",
  PAID: "已支付",
  CANCELED: "已取消",
  PAYMENT_EXCEPTION: "支付异常",
  CREATED: "已创建",
  SUCCEEDED: "已成功",
  CLOSED: "已关闭",
  FAILED: "失败",
  UNKNOWN: "待确认",
  APPROVED: "已批准",
  PROCESSING: "处理中",
  READY: "待对账",
  RUNNING: "运行中",
  COMPLETED: "已完成",
  IMPORTING: "导入中",
  MATCHED: "账实相符",
  CHANNEL_ONLY: "渠道单边",
  LOCAL_ONLY: "本地单边",
  AMOUNT_MISMATCH: "金额不符",
  BILL_DUPLICATE: "账单重复",
  KEY_UNRESOLVED: "标识待核实",
  PENDING_CONFIRMATION: "待确认",
  EXTRA_SUCCESS: "额外成功支付",
  OPEN: "待分派",
  ASSIGNED: "已分派",
  SUCCESS: "成功",
  RECEIPT: "收款",
  REFUND: "退款",
  DECLARED_COMPLETE: "已声明完整",
  PROVIDER_COMPLETE: "渠道确认完整",
};
const type = computed(() =>
  [
    "ACTIVE",
    "PAID",
    "SUCCEEDED",
    "COMPLETED",
    "MATCHED",
    "SUCCESS",
    "CLOSED",
  ].includes(props.value || "")
    ? "success"
    : [
          "FAILED",
          "AMOUNT_MISMATCH",
          "EXTRA_SUCCESS",
          "PAYMENT_EXCEPTION",
          "REJECTED",
          "LOCAL_ONLY",
          "CHANNEL_ONLY",
        ].includes(props.value || "")
      ? "danger"
      : [
            "PENDING",
            "PENDING_REVIEW",
            "UNKNOWN",
            "OPEN",
            "PENDING_CONFIRMATION",
            "BILL_DUPLICATE",
            "KEY_UNRESOLVED",
          ].includes(props.value || "")
        ? "warning"
        : "info",
);
</script>
<template>
  <el-tag :type="type" effect="light" round size="small">{{
    labels[value || ""] || value || "—"
  }}</el-tag>
</template>
