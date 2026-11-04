<script setup lang="ts">
import { Warning, Refresh } from "@element-plus/icons-vue";
defineProps<{ failed: boolean; busy: boolean; noun?: string; hasPrevious?: boolean }>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <div v-if="failed" class="list-feedback" role="alert">
    <el-icon><Warning /></el-icon>
    <div><strong>{{ noun || '记录' }}未能加载</strong><p>{{ hasPrevious ? '下方仍显示上次结果，请重新加载。' : '请重试。仍有问题时，检查网络连接。' }}</p></div>
    <el-button :icon="Refresh" :loading="busy" @click="$emit('retry')">重新加载</el-button>
  </div>
</template>
<style scoped>
.list-feedback { display: flex; gap: 13px; align-items: center; padding: 20px 24px; border-bottom: 1px solid #eadfcf; background: #fffaf2; color: #715024; }
.list-feedback > .el-icon { flex: none; font-size: 20px; }
.list-feedback > div { flex: 1; min-width: 0; }
.list-feedback strong { font-size: 14px; font-weight: 600; }
.list-feedback p { margin: 5px 0 0; color: #785f3a; font-size: 12px; }
@media(max-width: 500px) { .list-feedback { flex-wrap: wrap; padding: 18px; } .list-feedback > .el-button { margin-left: 33px; } }
</style>
