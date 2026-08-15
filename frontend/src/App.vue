<template>
  <div class="app-container">
    <!-- 1. 地理底座 -->
    <MapViewer @map-ready="handleMapReady" />

    <!-- 2. 知识库管理（左侧，上传仅管理员可见） -->
    <KnowledgeManager />

    <!-- 3. AI 聊天助手（右侧） -->
    <ChatAgent :mapView="mainView" />
    <KnowledgeGraphWorkbench />

    <AnalysisDashboard :data="chartData"/>
    <CityEngineResultViewer />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, shallowRef } from 'vue';
import MapViewer from './components/MapViewer.vue';
import ChatAgent from './components/ChatAgent.vue';
import KnowledgeManager from './components/KnowledgeManager.vue';
import AnalysisDashboard from "./components/AnalysisDashboard.vue";
import CityEngineResultViewer from "./components/CityEngineResultViewer.vue";
import KnowledgeGraphWorkbench from './components/KnowledgeGraphWorkbench.vue';

// SceneView 是 GeoScene Accessor 实例，不能放进深响应式 ref（Vue 的 Proxy
// 会拦截其只读的 __accessor__ 内部属性并抛错）；用 shallowRef 保持原对象引用。
const mainView = shallowRef(null);
const chartData = ref({});
const handleMapReady = (view) => {
  console.log("地图已就绪，正在同步至智能体...");
  mainView.value = view;
};

const hasComputedMetrics = (payload) => {
  const metrics = payload?.metrics || payload || {};
  const status = String(metrics.status || '').toLowerCase();
  return status !== 'error'
    && Number.isFinite(Number(metrics.far))
    && Number(metrics.site_area ?? metrics.site_area_sqm) > 0
    && Number(metrics.building_count) > 0;
};

const handleGisCharts = (event) => {
  if (!hasComputedMetrics(event.detail)) {
    console.warn('⚠️ 忽略未完成的 GIS 指标，避免展示空分析面板。');
    chartData.value = {};
    return;
  }
  console.log('📊 App.vue 接收到已完成图表指令:', event.detail);
  chartData.value = event.detail;
};

const clearGisCharts = () => {
  chartData.value = {};
};

const chatRef = ref(null);
// 接收到地图点击的属性后，直接塞给聊天组件执行发送
const handleSpatialClick = (context) => {
  if (chatRef.value) {
    chatRef.value.externalSend(context);
  }
};
onMounted(() => {
  window.addEventListener('show-gis-charts', handleGisCharts);
  window.addEventListener('clear-gis-charts', clearGisCharts);
});
onUnmounted(() => {
  window.removeEventListener('show-gis-charts', handleGisCharts);
  window.removeEventListener('clear-gis-charts', clearGisCharts);
});

</script>

<style>
/* 确保基础样式覆盖全屏 */
html, body, #app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.app-container {
  position: relative;
  width: 100vw;
  height: 100vh;
}

</style>
