<script setup lang="ts">
import { onMounted, onBeforeUnmount, watch, ref } from "vue";
import * as echarts from "echarts";
const props = withDefaults(defineProps<{ option: any; height?: number }>(), {
  height: 300,
});
const element = ref<HTMLElement>();
let instance: echarts.ECharts | undefined;
let observer: ResizeObserver;
onMounted(() => {
  if (element.value) {
    instance = echarts.init(element.value);
    instance.setOption(props.option);
    observer = new ResizeObserver(() => instance?.resize());
    observer.observe(element.value);
  }
});
watch(
  () => props.option,
  (option) => instance?.setOption(option, true),
  { deep: true },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  instance?.dispose();
});
</script>
<template>
  <div
    ref="element"
    :style="{ height: height + 'px', width: '100%' }"
    role="img"
    aria-label="统计图表"
  ></div>
</template>
