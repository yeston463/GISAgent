<template>
  <div class="app-container">
    <!-- 1. 地理底座 -->
    <MapViewer @map-ready="handleMapReady" />

    <!-- 2. 知识库管理（左侧） -->
    <KnowledgeManager />

    <!-- 3. AI 聊天助手（右侧） -->
    <ChatAgent :mapView="mainView" />
    <KnowledgeGraphWorkbench />

    <AnalysisDashboard :data="chartData"/>
    <CityEngineResultViewer />
  </div>
</template>

<script setup>
import { ref ,onMounted} from 'vue';
import MapViewer from './components/MapViewer.vue';
import ChatAgent from './components/ChatAgent.vue';
import KnowledgeManager from './components/KnowledgeManager.vue';
import AnalysisDashboard from "./components/AnalysisDashboard.vue";
import CityEngineResultViewer from "./components/CityEngineResultViewer.vue";
import KnowledgeGraphWorkbench from './components/KnowledgeGraphWorkbench.vue';

const mainView = ref(null);
const chartData = ref({});
const handleMapReady = (view) => {
  console.log("地图已就绪，正在同步至智能体...");
  mainView.value = view;
};

const chatRef = ref(null);
// 接收到地图点击的属性后，直接塞给聊天组件执行发送
const handleSpatialClick = (context) => {
  if (chatRef.value) {
    chatRef.value.externalSend(context);
  }
};
onMounted(() => {
  window.addEventListener("show-gis-charts", (e) => {
    console.log("📊 App.vue 接收到图表指令:", e.detail);
    chartData.value = e.detail;
  });
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
