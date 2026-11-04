<script setup lang="ts">
import { onMounted, onBeforeUnmount, watch, ref } from "vue";
import { init, use, type ECharts } from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);
const props = withDefaults(defineProps<{ option: any; height?: number }>(), {
  height: 300,
});
const element = ref<HTMLElement>();
let instance: ECharts | undefined;
let observer: ResizeObserver;
const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
const chartTheme = {
  color: ["#325ddb", "#8295b8", "#23958b", "#8094cf", "#c66c72", "#75aabd", "#b6a0ce", "#7e8fa6"],
  backgroundColor: "transparent",
  textStyle: { fontFamily: getComputedStyle(document.documentElement).getPropertyValue("--font-ui").trim(), fontSize: 12, color: "#64748b" },
  legend: { itemWidth: 12, itemHeight: 8, itemGap: 18, textStyle: { color: "#64748b", fontSize: 12 } },
  tooltip: {
    confine: true,
    backgroundColor: "#fff",
    borderColor: "#e5eaf2",
    borderWidth: 1,
    padding: [12, 16],
    textStyle: { color: "#172b4d", fontSize: 13 },
    extraCssText: "border-radius:10px;box-shadow:0 8px 28px rgba(20,36,59,.1)",
    axisPointer: { lineStyle: { color: "#b1bed6", type: "dashed" }, shadowStyle: { color: "rgba(50,93,219,.04)" } },
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: "#e5eaf2" } }, axisTick: { show: false },
    axisLabel: { color: "#64748b", fontSize: 12, margin: 14 }, splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false }, axisTick: { show: false },
    axisLabel: { color: "#64748b", fontSize: 12, margin: 12 },
    nameTextStyle: { color: "#64748b", fontSize: 12 },
    splitLine: { lineStyle: { color: "#edf0f5", type: "dashed" } },
  },
  line: { symbol: "circle", symbolSize: 6, lineStyle: { width: 2.5 }, emphasis: { lineStyle: { width: 3 } } },
  bar: { itemStyle: { borderRadius: [3, 3, 0, 0] } },
  pie: { itemStyle: { borderColor: "#fff", borderWidth: 3, borderRadius: 3 } },
};
function render() {
  instance?.setOption({ ...props.option, animation: !motion.matches, animationDuration: 350, animationDurationUpdate: 280, animationEasingUpdate: "cubicOut" }, { replaceMerge: ["series"], lazyUpdate: true });
}
onMounted(() => {
  if (element.value) {
    instance = init(element.value, chartTheme);
    render();
    observer = new ResizeObserver(() => instance?.resize());
    observer.observe(element.value);
    motion.addEventListener("change", render);
  }
});
watch(
  () => props.option,
  render,
  { deep: true },
);
onBeforeUnmount(() => {
  observer?.disconnect();
  motion.removeEventListener("change", render);
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
