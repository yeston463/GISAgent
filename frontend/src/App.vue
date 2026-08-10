<template>
  <LoginView v-if="!auth.isAuthenticated" @login-success="onLoginSuccess" />
  <div v-else class="app-container">
    <!-- 会话栏 -->
    <div class="session-bar">
      <span class="session-user">
        {{ auth.displayName }}
        <el-tag v-if="auth.isAdmin" size="small" type="danger" effect="dark">管理员</el-tag>
        <el-tag v-else size="small" type="info" effect="plain">普通用户</el-tag>
      </span>
      <el-button size="small" @click="handleLogout">退出登录</el-button>
    </div>

    <!-- 1. 地理底座 -->
    <MapViewer @map-ready="handleMapReady" />

    <!-- 2. 知识库管理（左侧，上传仅管理员可见） -->
    <KnowledgeManager />

    <!-- 3. AI 聊天助手（右侧） -->
    <ChatAgent :mapView="mainView" />
    <!-- 能力图谱工作台仅管理员 -->
    <KnowledgeGraphWorkbench v-if="auth.isAdmin" />

    <AnalysisDashboard :data="chartData"/>
    <CityEngineResultViewer />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { auth, logout } from './auth';
import LoginView from './LoginView.vue';
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

const onLoginSuccess = () => {
  // 登录成功由 auth 响应式状态驱动，自动切换到主界面
};

const handleLogout = () => {
  logout();
  ElMessage.info('已退出登录');
};
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

.session-bar {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(6px);
  border-radius: 8px;
  padding: 6px 10px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.session-user {
  font-size: 13px;
  color: #0e2a45;
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>
