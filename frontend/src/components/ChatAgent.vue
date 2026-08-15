<template>
  <div class="chat-wrapper">
    <div class="chat-window" ref="scrollContainer">
      <div
        v-for="(msg, index) in messages"
        :key="index"
        :class="['msg-bubble', msg.role, { 'report-bubble': msg.report }]"
      >
        <details v-if="msg.trace?.length" class="trace-panel" :open="msg.traceOpen">
          <summary>执行轨迹 · {{ msg.trace.length }} 步</summary>
          <ol class="trace-list">
            <li v-for="(step, traceIndex) in msg.trace" :key="`${traceIndex}-${step.title}`" :class="['trace-item', step.status]">
              <span class="trace-marker">{{ traceIndex + 1 }}</span>
              <div><strong>{{ step.title }}</strong><small>{{ step.detail }}</small></div>
            </li>
          </ol>
        </details>
        <div v-if="msg.hasCommand" class="command-tag">
          <el-icon><Compass /></el-icon>
          <span>空间引擎联动中</span>
        </div>
        <article v-if="msg.report" class="analysis-report">
          <header class="report-header">
            <div>
              <p class="report-kicker">建筑指标分析</p>
              <h2>{{ msg.report.title }}</h2>
            </div>
            <div v-if="msg.report.far" class="far-badge">
              <span>FAR</span>
              <strong>{{ msg.report.far.value }}</strong>
            </div>
          </header>

          <div v-if="msg.report.info.length" class="info-strip">
            <div v-for="item in msg.report.info" :key="item.label" class="info-item">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>

          <div v-if="msg.report.metrics.length" class="metric-grid">
            <div
              v-for="metric in msg.report.metrics"
              :key="metric.label"
              :class="['metric-card', { primary: metric.primary }]"
            >
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
            </div>
          </div>

          <section v-if="msg.report.conclusions.length" class="report-section">
            <h3>筛查结论（辅助参考）</h3>
            <ol class="report-list">
              <li v-for="item in msg.report.conclusions" :key="item">{{ item }}</li>
            </ol>
          </section>

          <section v-if="msg.report.suggestions.length" class="report-section">
            <h3>后续建议</h3>
            <ol class="report-list suggestion-list">
              <li v-for="item in msg.report.suggestions" :key="item">{{ item }}</li>
            </ol>
          </section>
        </article>

        <div v-else class="msg-content">
          <template v-for="(block, blockIndex) in formatMessageBlocks(msg.text)" :key="blockIndex">
            <h3 v-if="block.type === 'heading'" class="message-heading">{{ block.text }}</h3>
            <ol v-else-if="block.type === 'ordered-list'" class="message-list">
              <li v-for="item in block.items" :key="item">{{ item }}</li>
            </ol>
            <ul v-else-if="block.type === 'unordered-list'" class="message-list">
              <li v-for="item in block.items" :key="item">{{ item }}</li>
            </ul>
            <p v-else>{{ block.text }}</p>
          </template>
        </div>
      </div>

      <div v-if="isAnalyzing" class="ai-loading">
        <div class="spinner"></div>
        <span class="loading-text">{{ loadingStatusText }}</span>
      </div>
      <details v-if="isAnalyzing && activeTrace.length" class="live-trace" open>
        <summary>执行轨迹 · 正在运行</summary>
        <ol class="trace-list">
          <li v-for="(step, traceIndex) in activeTrace" :key="`live-${traceIndex}-${step.title}`" :class="['trace-item', step.status]">
            <span class="trace-marker">{{ traceIndex + 1 }}</span>
            <div><strong>{{ step.title }}</strong><small>{{ step.detail }}</small></div>
          </li>
        </ol>
      </details>
    </div>

    <div class="context-status" :class="{ ready: contextStatus.hasAoi && contextStatus.hasBuildings }">
      <span>范围 {{ contextStatus.hasAoi ? '已就绪' : '未就绪' }}</span>
      <span>建筑 {{ contextStatus.hasBuildings ? `${contextStatus.buildingCount} 栋` : '未就绪' }}</span>
      <span>高程 {{ contextStatus.hasDem ? '已就绪' : '未就绪' }}</span>
      <button class="ground-dem-btn" @click="loadGroundDem" :disabled="isAnalyzing || !contextStatus.hasAoi">采样 World Elevation 高程</button>
      <button v-if="contextStatus.demoEnabled" class="demo-context-btn" @click="loadDemoContext" :disabled="isAnalyzing">加载验证样例</button>
    </div>
    <div class="input-container">
      <select v-model="uploadDataset" class="dataset-select" :disabled="isAnalyzing" aria-label="Spatial dataset type">
        <option value="aoi">范围</option>
        <option value="buildings">建筑</option>
        <option value="dem">DEM</option>
        <option value="drainage_network">排水管网</option>
        <option value="river_network">河网</option>
        <option value="candidates">候选地</option>
        <option value="facilities">设施</option>
      </select>
      <select v-model="uploadCrs" class="crs-select" :disabled="isAnalyzing" aria-label="Source coordinate reference system">
        <option value="EPSG:4326">WGS84</option>
        <option value="EPSG:3857">Web Mercator</option>
      </select>
      <input ref="spatialFileInput" class="spatial-file-input" type="file" accept=".geojson,.json,.zip,.shp,.gpkg,.asc,.tif,.tiff" @change="uploadSpatialFile" />
      <button class="upload-data-btn" title="Upload spatial data" @click="openSpatialFilePicker" :disabled="isAnalyzing"><el-icon><Upload /></el-icon></button>
      <input ref="graphFileInput" class="spatial-file-input" type="file" accept="application/json,.json" @change="uploadKnowledgeGraph" />
      <button class="graph-upload-btn" title="上传空间知识图谱 JSON（先校验，再确认发布）" @click="openGraphFilePicker" :disabled="isAnalyzing">图谱</button>
      <input
        v-model="currentInput"
        @keyup.enter="handleUserSend"
        placeholder="输入 GIS 分析任务..."
        :disabled="isAnalyzing"
      />
      <button @click="handleUserSend" :disabled="isAnalyzing || !currentInput.trim()">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, toRef, nextTick, onMounted, onUnmounted } from 'vue';
import { Compass, Upload } from '@element-plus/icons-vue';
import axios from 'axios';
import { useCommandExecutor } from '../useCommandExecutor';
import { getGisSessionId, setGisContextVersion } from '../gisSession';
import { loadGeoSceneModules } from '../map/geoSceneAdapter';

let geoSceneModulesPromise;

const getGeoSceneModules = () => {
  if (!geoSceneModulesPromise) {
    geoSceneModulesPromise = loadGeoSceneModules([
      'geoscene/geometry/support/webMercatorUtils',
      'geoscene/geometry/geometryEngine',
      'geoscene/geometry/Point',
      'geoscene/geometry/Polygon'
    ]).then(([webMercatorUtils, geometryEngine, Point, Polygon]) => ({
      webMercatorUtils, geometryEngine, Point, Polygon
    }));
  }
  return geoSceneModulesPromise;
};

const props = defineProps(['mapView']);
const scrollContainer = ref(null);
const { execute, executeWithReport } = useCommandExecutor(toRef(props, 'mapView'));

const messages = ref([
  {
    role: 'assistant',
    text: '你好，我是 GIS 分析 Agent。你可以让我计算容积率、分析红线/AOI，或查询指定地点周边建筑指标。'
  }
]);
const currentInput = ref('');
const isAnalyzing = ref(false);
const loadingStatusText = ref('正在分析...');
const memoryId = ref(getGisSessionId());
const activeTrace = ref([]);
const contextStatus = ref({ hasAoi: false, hasBuildings: false, buildingCount: 0, demoEnabled: false, aoi: null });
const spatialFileInput = ref(null);
const graphFileInput = ref(null);
const uploadDataset = ref('dem');
const uploadCrs = ref('EPSG:4326');

let isLoopLocked = false;

const handleScenarioSwitchRequest = event => {
  execute([{ action: 'switchPlanningScenario', params: event.detail || {} }]);
};
onMounted(async () => {
  window.addEventListener('gis-data-ready', handleDataReady);
  window.addEventListener('sketch-aoi-ready', handleSketchReady);
  window.addEventListener('request-planning-scenario-switch', handleScenarioSwitchRequest);
  await refreshContextStatus();
  // Older versions auto-loaded the demo. Remove only that persisted fixture
  // so the page always starts empty until the user clicks the demo button.
  if (contextStatus.value.demoId) {
    const { data } = await axios.post('/api/gis/clear-demo-context', { memoryId: memoryId.value });
    applyContextStatus(data);
  }
});

onUnmounted(() => {
  window.removeEventListener('gis-data-ready', handleDataReady);
  window.removeEventListener('sketch-aoi-ready', handleSketchReady);
  window.removeEventListener('request-planning-scenario-switch', handleScenarioSwitchRequest);
});

const handleDataReady = () => {
  if (isLoopLocked) return;
  isLoopLocked = true;
  processAiChat('请调用 analyzeCurrentView 计算当前已上传数据的建筑指标并展示结果。', true);
  setTimeout(() => {
    isLoopLocked = false;
  }, 10000);
};

const handleSketchReady = async event => {
  if (isLoopLocked || isAnalyzing.value) return;
  isLoopLocked = true;
  isAnalyzing.value = true;
  loadingStatusText.value = '正在提取红线内可见建筑...';
  try {
    const synced = await execute([{
      action: 'getScreenBuildings',
      params: { aoiGeometry: event?.detail?.aoiGeometry }
    }]);
    if (synced === false || window.lastGisResult?.status !== 'Success') {
      throw new Error(window.lastGisResult?.message || '未能同步该区域建筑数据（Overpass 无建筑或底图不可用）');
    }
  } catch (error) {
    console.error('红线建筑同步失败:', error);
    messages.value.push(createAssistantMessage(`红线建筑同步失败：${error?.message || '请稍后重试'}`));
    isAnalyzing.value = false;
    isLoopLocked = false;
    scrollToBottom();
    return;
  }
  isAnalyzing.value = false;
  isLoopLocked = false;
  await refreshContextStatus();
  await processAiChat('请调用 analyzeCurrentView 计算当前已上传红线内真实建筑的容积率和建筑指标并展示结果。');
};

const applyContextStatus = data => {
  contextStatus.value = {
    hasAoi: Boolean(data?.hasAoi),
    hasBuildings: Boolean(data?.hasBuildings),
    buildingCount: Number(data?.buildingCount || 0),
    demoEnabled: Boolean(data?.demoEnabled),
    demoId: data?.demoId || null,
    hasDem: Array.isArray(data?.availableData) && data.availableData.includes('dem'),
    contextVersion: Number(data?.contextVersion ?? -1),
    aoi: data?.aoi || null
  };
  if (data?.contextVersion !== undefined) setGisContextVersion(data.contextVersion);
};

const refreshContextStatus = async () => {
  try {
    const { data } = await axios.get('/api/gis/context', { params: { memoryId: memoryId.value } });
    applyContextStatus(data);
  } catch (error) {
    console.warn('GIS 上下文状态不可用:', error);
  }
};

const loadDemoContext = async () => {
  if (isAnalyzing.value) return;
  isAnalyzing.value = true;
  loadingStatusText.value = '正在加载验证样例...';
  try {
    const { data } = await axios.post('/api/gis/demo-context', { memoryId: memoryId.value });
    applyContextStatus(data);
    await execute([
      { action: 'addGeoJsonLayer', params: { data: data.aoi, layerId: 'analysis-demo-aoi', style: 'aoi', title: '验证 AOI' } },
      { action: 'addGeoJsonLayer', params: { data: data.buildings, layerId: 'analysis-demo-buildings', style: 'existing', title: '验证建筑' } }
    ]);
    await showDemoSummary(data);
    messages.value.push(createAssistantMessage('验证样例已加载：范围和 3 栋建筑已就绪，可直接执行天际线分析或日照与阴影筛查。'));
  } catch (error) {
    messages.value.push(createAssistantMessage(`验证样例加载失败：${error?.response?.data?.message || error?.message || '未知错误'}`));
  } finally {
    isAnalyzing.value = false;
    scrollToBottom();
  }
};

const polygonAreaSqm = geometry => {
  if (geometry?.type !== 'Polygon' || !Array.isArray(geometry.coordinates) || !geometry.coordinates.length) return 0;
  const ring = geometry.coordinates[0];
  if (!Array.isArray(ring) || ring.length < 4) return 0;
  const refLat = ring.reduce((sum, point) => sum + point[1], 0) / ring.length;
  const metersPerDegreeLon = 111320 * Math.cos((refLat * Math.PI) / 180);
  const metersPerDegreeLat = 110540;
  const projected = ring.map(([lon, lat]) => [lon * metersPerDegreeLon, lat * metersPerDegreeLat]);
  let area = 0;
  for (let index = 0; index < projected.length - 1; index += 1) {
    area += projected[index][0] * projected[index + 1][1] - projected[index + 1][0] * projected[index][1];
  }
  return Math.abs(area / 2);
};

const showDemoSummary = async data => {
  const buildings = Array.isArray(data?.buildings?.features) ? data.buildings.features : [];
  const siteArea = polygonAreaSqm(data?.aoi?.geometry);
  const buildingArea = buildings.reduce((total, feature) => {
    const footprint = polygonAreaSqm(feature?.geometry);
    const floors = Number(feature?.properties?.['building:levels'] || 0);
    return total + footprint * floors;
  }, 0);
  const footprintArea = buildings.reduce((total, feature) => total + polygonAreaSqm(feature?.geometry), 0);
  const heights = buildings.map(feature => Number(feature?.properties?.height || 0)).filter(value => value > 0);
  const floors = buildings.map(feature => Number(feature?.properties?.['building:levels'] || 0)).filter(value => value > 0);
  const mean = values => values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
  const metrics = {
    far: siteArea > 0 ? Number((buildingArea / siteArea).toFixed(3)) : 0,
    site_area: Number(siteArea.toFixed(0)),
    building_area: Number(buildingArea.toFixed(0)),
    building_density: siteArea > 0 ? Number(((footprintArea / siteArea) * 100).toFixed(2)) : 0,
    building_count: buildings.length,
    footprint_area_sqm: Number(footprintArea.toFixed(0)),
    height_stats: { max: heights.length ? Math.max(...heights) : 0, avg: Number(mean(heights).toFixed(1)), confidence: 'medium' },
    floor_stats: { avg: Number(mean(floors).toFixed(1)) },
    floor_confidence: 'high'
  };
  await execute([{ action: 'showAnalysisResult', params: metrics }]);
  window.dispatchEvent(new CustomEvent('show-analysis-provenance', {
    detail: { runId: `demo-${Date.now()}`, tool: '离线演示数据（内置样例）', contextVersion: data?.contextVersion, data_source: 'demo_fixture' }
  }));
};

const loadGroundDem = async (force = false) => {
  if (isAnalyzing.value && !force) return;
  const mapView = props.mapView?.value || props.mapView;
  const ground = mapView?.map?.ground;
  const aoi = window.lastManualAoiGeometry || window.lastBufferGeometry || await restoreAoiGeometry(contextStatus.value.aoi);
  if (!ground?.queryElevation || !aoi) {
    messages.value.push(createAssistantMessage('无法从底图获取高程：请先用右上角工具绘制 AOI；若地形服务不可用，请上传 DEM。'));
    scrollToBottom();
    return;
  }
  isAnalyzing.value = true;
  loadingStatusText.value = '正在从 GeoScene 地形面采样高程...';
  try {
    const { Point, geometryEngine, webMercatorUtils } = await getGeoSceneModules();
    const extent = aoi.extent;
    const side = 16;
    const points = [];
    for (let row = 0; row < side; row += 1) {
      for (let column = 0; column < side; column += 1) {
        const x = extent.xmin + (column + 0.5) / side * (extent.xmax - extent.xmin);
        const y = extent.ymin + (row + 0.5) / side * (extent.ymax - extent.ymin);
        const point = new Point({ x, y, spatialReference: aoi.spatialReference });
        if (geometryEngine.contains(aoi, point)) {
          points.push(point);
        }
      }
    }
    const results = await Promise.all(points.map(point => ground.queryElevation(point, { returnSampleInfo: false })
      .then(result => result?.geometry || result)
      .catch(() => null)));
    const features = results.filter(result => result && Number.isFinite(Number(result.z))).map((result, index) => {
      const geographic = result.spatialReference?.isWGS84 ? result : webMercatorUtils.webMercatorToGeographic(result);
      return {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [geographic.x, geographic.y] },
        properties: { id: `ground-dem-${index + 1}`, elevation_m: Number(geographic.z), source: 'geoscene_world_elevation' }
      };
    });
    if (features.length < 3) throw new Error('地形服务未返回足够的高程样本');
    const { data } = await axios.post('/api/gis/ground-dem', {
      memoryId: memoryId.value, contextVersion: contextStatus.value.contextVersion,
      sourceCrs: 'EPSG:4326', aoi: await toGeoJsonAoi(aoi),
      dem: { type: 'FeatureCollection', features }
    });
    applyContextStatus(data);
    messages.value.push(createAssistantMessage(`已从 GeoScene World Elevation 在当前 AOI 采样 ${data.sampleCount} 个高程点。这是规则网格高程样本，不是原始 DEM 栅格；输入“洪水分析”后，Agent 会检查还缺少的业务数据。`));
  } catch (error) {
    messages.value.push(createAssistantMessage(`从 GeoScene World Elevation 采样高程失败：${error?.response?.data?.message || error?.message || '请上传 DEM 文件。'}`));
  } finally {
    isAnalyzing.value = false;
    scrollToBottom();
  }
};

const loadPublicDem = async (resumeMessage = '') => {
  const aoi = window.lastManualAoiGeometry || window.lastBufferGeometry || await restoreAoiGeometry(contextStatus.value.aoi);
  if (!aoi) throw new Error('无法获取 DEM：当前没有可用 AOI。');
  isAnalyzing.value = true;
  loadingStatusText.value = '正在下载并裁剪公共 GeoTIFF DEM...';
  try {
    const { data } = await axios.post('/api/gis/public-dem', {
      memoryId: memoryId.value, contextVersion: contextStatus.value.contextVersion, aoi: await toGeoJsonAoi(aoi)
    });
    applyContextStatus(data);
    const metadata = data.metadata || {};
    messages.value.push(createAssistantMessage('已获取真实 GeoTIFF DEM：' + (metadata.source || 'public DEM') + '，分辨率约 ' + (metadata.resolutionMeters || '未知') + ' m，覆盖 ' + (metadata.tileCount || 0) + ' 个高程瓦片。'));
    if (resumeMessage) await processAiChat(resumeMessage, true);
  } catch (error) {
    const detail = error?.response?.data?.message || error?.message || '公共 GeoTIFF 服务不可用';
    // 直连公共 S3 DEM 可能被网络中途断开；用 GeoScene World Elevation
    // 规则网格采样兜底，保持现场演示可继续，不伪装为原始 GeoTIFF。
    messages.value.push(createAssistantMessage(`公共 GeoTIFF 直连失败（${detail}），正在切换 GeoScene World Elevation 高程采样...`));
    await loadGroundDem(true);
  } finally {
    isAnalyzing.value = false;
    scrollToBottom();
  }
};

const restoreAoiGeometry = async aoi => {
  const geometry = aoi?.geometry || aoi;
  if (!geometry || geometry.type !== 'Polygon' || !Array.isArray(geometry.coordinates)) return null;
  try {
    const { Polygon, webMercatorUtils } = await getGeoSceneModules();
    const polygon = new Polygon({ rings: geometry.coordinates, spatialReference: { wkid: 4326 } });
    return webMercatorUtils.geographicToWebMercator(polygon);
  } catch (error) {
    console.warn('无法恢复当前 AOI 几何:', error);
    return null;
  }
};

const toGeoJsonAoi = async aoi => {
  const { webMercatorUtils } = await getGeoSceneModules();
  const geographic = aoi?.spatialReference?.isWGS84 ? aoi : webMercatorUtils.webMercatorToGeographic(aoi);
  return {
    type: 'Feature',
    properties: { source: window.currentAoiSource || 'map_aoi' },
    geometry: { type: 'Polygon', coordinates: geographic.rings }
  };
};

const openSpatialFilePicker = () => spatialFileInput.value?.click();
const openGraphFilePicker = () => graphFileInput.value?.click();

const uploadKnowledgeGraph = async event => {
  const file = event.target?.files?.[0];
  if (!file || isAnalyzing.value) return;
  isAnalyzing.value = true;
  loadingStatusText.value = '正在校验知识图谱...';
  try {
    const graph = await file.text();
    const { data: preview } = await axios.post('/api/agent/capabilities/candidates/preview', { graph });
    const changes = preview?.preview?.changes || [];
    const version = preview?.preview?.version || '未命名版本';
    const summary = changes.length
      ? changes.map(change => change.capabilityId).join('、')
      : '无语义差异';
    if (!window.confirm(`图谱 ${version} 校验通过。\n变更能力：${summary}\n\n是否立即发布并激活？`)) {
      messages.value.push(createAssistantMessage(`知识图谱 ${version} 已通过校验，未发布。`));
      return;
    }
    loadingStatusText.value = '正在发布知识图谱...';
    const { data: published } = await axios.post('/api/agent/capabilities/publish', {
      graph, author: 'frontend-local-upload', note: `上传文件：${file.name}`
    });
    messages.value.push(createAssistantMessage(`知识图谱 ${published.revision?.version || version} 已发布并激活。后续分析将记录该图谱版本。`));
  } catch (error) {
    const detail = error?.response?.data?.message || error?.response?.data?.code || error?.message || '未知错误';
    messages.value.push(createAssistantMessage(`知识图谱上传失败：${detail}`));
  } finally {
    event.target.value = '';
    isAnalyzing.value = false;
    scrollToBottom();
  }
};

const uploadSpatialFile = async event => {
  const file = event.target?.files?.[0];
  if (!file || isAnalyzing.value) return;
  isAnalyzing.value = true;
  loadingStatusText.value = '正在导入空间数据...';
  try {
    const form = new FormData();
    form.append('file', file);
    form.append('dataset', uploadDataset.value);
    form.append('memoryId', memoryId.value);
    form.append('contextVersion', String(contextStatus.value.contextVersion || -1));
    form.append('sourceCrs', uploadCrs.value);
    const { data } = await axios.post('/api/gis/data-file', form);
    applyContextStatus(data);
    if (data.dataType === 'vector' && data.vectorData) {
      const styles = { aoi: 'aoi', buildings: 'existing', drainage_network: 'optimized', river_network: 'optimized', candidates: 'siteSelection', facilities: 'green', dem: 'warning' };
      await execute([{ action: 'addGeoJsonLayer', params: {
        data: data.vectorData, layerId: `uploaded-${uploadDataset.value}`,
        style: styles[uploadDataset.value] || 'optimized', title: data.fileName
      } }]);
    }
    const nextStep = data.dataType === 'raster'
      ? '。输入“洪水分析”后，Agent 会依据当前上下文说明仍缺少的数据。'
      : '';
    messages.value.push(createAssistantMessage(`${data.dataType === 'raster' ? '栅格' : '矢量'}数据已导入：${data.fileName}${nextStep}`));
  } catch (error) {
    messages.value.push(createAssistantMessage(`空间数据导入失败：${error?.response?.data?.message || error?.response?.data?.code || error?.message || '未知错误'}`));
  } finally {
    event.target.value = '';
    isAnalyzing.value = false;
    scrollToBottom();
  }
};

const handleUserSend = () => {
  if (!currentInput.value.trim() || isAnalyzing.value) return;
  if (/导出|下载|生成/.test(currentInput.value) && /分析报告|报告/.test(currentInput.value)) {
    const text = currentInput.value;
    messages.value.push({ role: 'user', text });
    currentInput.value = '';
    openLatestReport();
    return;
  }
  window.dispatchEvent(new Event('clear-gis-charts'));
  window.lastGisResult = {
    far: 0,
    site_area: 0,
    building_area: 0,
    building_count: 0,
    status: 'pending'
  };
  isLoopLocked = false;
  const text = currentInput.value;
  messages.value.push({ role: 'user', text });
  currentInput.value = '';
  scrollToBottom();
  processAiChat(text);
};

const openLatestReport = () => {
  window.open(`/api/reports/latest?memoryId=${encodeURIComponent(memoryId.value)}`, '_blank', 'noopener');
  messages.value.push(createAssistantMessage('已开始下载最近一次成功分析的可追溯报告（HTML）。打开后可使用浏览器“打印”保存为 PDF。'));
  scrollToBottom();
};

const processAiChat = async (userInput, isHidden = false) => {
  isAnalyzing.value = true;
  activeTrace.value = [];
  loadingStatusText.value = isHidden ? '正在同步分析...' : 'Agent 正在抓取有效数据...';

  try {
    const data = await agentChat(userInput);
    await refreshContextStatus();
    let responseTrace = Array.isArray(data.trace) ? [...data.trace] : [...activeTrace.value];
    activeTrace.value = responseTrace;
    if (data.resultEnvelope?.provenance?.runId) {
      window.dispatchEvent(new CustomEvent('show-analysis-provenance', {
        detail: data.resultEnvelope.provenance
      }));
    }
    if (data.metrics && Object.keys(data.metrics).length > 0) {
      // 是否可展示由指标面板统一校验：标准 FAR 指标与知识图谱
      // 动态能力（如 avg_height_analysis 的 buildingCount 等）均可展示。
      window.dispatchEvent(new CustomEvent('show-gis-charts', { detail: data.metrics }));
    }
    const cityEngineJobId = data.reply?.match(/CityEngine 作业[：:]\s*([\w-]+)/)?.[1];
    if (cityEngineJobId && data.reply?.includes('SLPK 下载')) {
      let sceneServiceUrl = data.reply?.match(/Scene Service[：:]\s*(https?:\/\/\S+)/)?.[1] || '';
      let cityEngineJob = {};
      try {
        const jobResponse = await axios.get(`/analysis/cityengine/jobs/${cityEngineJobId}`);
        cityEngineJob = jobResponse.data || {};
        sceneServiceUrl = jobResponse.data?.sceneServiceUrl || jobResponse.data?.publication?.sceneServiceUrl || sceneServiceUrl;
      } catch (error) {
        console.warn('无法获取 CityEngine 发布详情，将使用回复中的地址:', error);
      }
      const resultDetail = {
        jobId: cityEngineJobId,
        slpkUrl: `/analysis/cityengine/jobs/${cityEngineJobId}/download/slpk`,
        sceneServiceUrl,
        geometrySummary: cityEngineJob.geometrySummary,
        optimizationActions: cityEngineJob.optimizationActions
      };
      if (sceneServiceUrl) {
        responseTrace = await openCityEngineResult(resultDetail, responseTrace);
      } else {
        window.dispatchEvent(new CustomEvent('show-cityengine-result', { detail: resultDetail }));
      }
      activeTrace.value = responseTrace;
    }
    const commands = Array.isArray(data.commands) ? data.commands : [];
    const groundDemRequested = commands.some(command => command.action === 'requestGroundDem');
    const publicDemRequested = commands.some(command => command.action === 'requestPublicDem');
    const executableCommands = commands.filter(command => command.action !== 'requestGroundDem' && command.action !== 'requestPublicDem');
    const envelopeOutputs = Array.isArray(data.resultEnvelope?.outputs)
      ? data.resultEnvelope.outputs.filter(output => output?.kind && output.kind !== 'commands')
      : [];
    if (envelopeOutputs.length > 0) {
      await execute(envelopeOutputs.map(output => ({
        action: 'renderAnalysisOutput',
        params: output
      })));
    }
    const hasScreenFallback = commands.some(command => command.action === 'getScreenBuildings');
    const comparisonCommand = commands.find(command => command.action === 'comparePlanningScenarios');
    if (comparisonCommand?.params?.comparison) {
      window.dispatchEvent(new CustomEvent('show-planning-comparison', {
        detail: comparisonCommand.params.comparison
      }));
    }

    const screenSyncPromise = hasScreenFallback ? waitForGisDataReady() : null;
    if (hasScreenFallback) isLoopLocked = true;
    if (executableCommands.length > 0) {
      const commandReport = await executeWithReport(executableCommands);
      if (hasScreenFallback && (commandReport.lastResult === false || !commandReport.ok)) {
        throw new Error(window.lastGisResult?.message || commandReport.failed?.[0]?.reason || '前端建筑数据同步失败');
      }
      if (!hasScreenFallback && !commandReport.ok && commandReport.executed.length === 0) {
        throw new Error(commandReport.failed?.[0]?.reason || '前端分析命令全部执行失败');
      }
    }
    if (groundDemRequested) await loadGroundDem(true);
    if (publicDemRequested) {
      const resumeMessage = commands.find(command => command.action === 'requestPublicDem')?.params?.resumeMessage || '';
      await loadPublicDem(resumeMessage);
    }

    if (hasScreenFallback) {
      const synced = await screenSyncPromise;
      isLoopLocked = false;
      if (!synced) {
        throw new Error('前端建筑数据同步超时，未开始服务端复算');
      }
      loadingStatusText.value = '正在用同步后的建筑数据计算指标...';
      return await processAiChat('请调用 analyzeCurrentView 计算当前已上传数据的建筑指标并展示结果。', isHidden);
    }

    if (!isHidden || data.needClarification) {
      messages.value.push(createAssistantMessage(
        data.reply || data.clarification || '分析完成。',
        commands.length > 0,
        responseTrace
      ));
    }

    if (!isHidden && Array.isArray(data.suggestions) && data.suggestions.length > 0) {
      messages.value.push(createAssistantMessage(
        `建议：\n${data.suggestions.map((item, index) => `${index + 1}. ${item}`).join('\n')}`,
        false
      ));
    }
  } catch (err) {
    console.error('Agent request failed:', err);
    if (!isHidden) {
      const detail = err?.message || '未知错误';
      messages.value.push(createAssistantMessage('处理请求失败：' + detail));
    }
  } finally {
    isAnalyzing.value = false;
    activeTrace.value = [];
    scrollToBottom();
  }
};

const openCityEngineResult = (detail, trace) => {
  const loadingStep = {
    round: 8,
    phase: 'visualization',
    title: '前端加载 SceneServer',
    detail: detail.sceneServiceUrl,
    status: 'running'
  };
  const nextTrace = [...trace, loadingStep];
  activeTrace.value = nextTrace;
  loadingStatusText.value = '前端正在加载 SceneServer...';

  return new Promise(resolve => {
    let settled = false;
    const finish = (status, message) => {
      if (settled) return;
      settled = true;
      window.removeEventListener('cityengine-scene-load-status', handleStatus);
      clearTimeout(timeoutId);
      const completedTrace = nextTrace.map(step => step === loadingStep
        ? { ...step, status, detail: message || step.detail }
        : step);
      activeTrace.value = completedTrace;
      resolve(completedTrace);
    };
    const handleStatus = event => {
      const statusDetail = event.detail || {};
      if (statusDetail.jobId !== detail.jobId || statusDetail.status === 'running') return;
      finish(
        statusDetail.status === 'success' ? 'success' : 'error',
        statusDetail.message
      );
    };
    const timeoutId = setTimeout(
      () => finish('error', 'SceneServer 加载超时，请检查服务地址和访问权限'),
      90000
    );
    window.addEventListener('cityengine-scene-load-status', handleStatus);
    window.dispatchEvent(new CustomEvent('show-cityengine-result', { detail }));
  });
};

const agentChat = async (userInput) => {
  const response = await fetch('/api/agent/chat/agentic', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ message: userInput, memoryId: memoryId.value })
  });
  if (!response.ok) {
    throw new Error(`Agent 接口不可用（${response.status}）`);
  }
  return response.json();
};

const waitForGisDataReady = () => {
  loadingStatusText.value = '正在同步前端建筑数据...';
  return new Promise(resolve => {
    const handler = () => {
      window.removeEventListener('gis-data-ready', handler);
      resolve(true);
    };
    window.addEventListener('gis-data-ready', handler);
    setTimeout(() => {
      window.removeEventListener('gis-data-ready', handler);
      resolve(false);
    }, 30000);
  });
};

const scrollToBottom = () => {
  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight;
    }
  });
};

const createAssistantMessage = (text, hasCommand = false, trace = []) => ({
  role: 'assistant',
  text,
  hasCommand,
  trace,
  traceOpen: trace.length > 0,
  report: parseAnalysisReport(text)
});

const cleanMarkdown = (value = '') => value
  .replace(/\*\*/g, '')
  .replace(/`/g, '')
  .replace(/^#+\s*/, '')
  .trim();

const parseAnalysisReport = (text = '') => {
  if (!/建筑分析报告|建筑指标|容积率\(FAR\)|容积率/.test(text)) return null;

  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  const title = cleanMarkdown(lines.find(line => /^#{1,3}\s/.test(line)) || '建筑分析报告');
  const report = {
    title,
    info: [],
    metrics: [],
    conclusions: [],
    suggestions: [],
    far: null
  };

  let section = '';
  for (const line of lines) {
    const normalized = cleanMarkdown(line);
    if (!normalized || normalized === title) continue;

    if (/基本信息/.test(normalized)) {
      section = 'info';
      continue;
    }
    if (/建筑指标/.test(normalized)) {
      section = 'metrics';
      continue;
    }
    if (/分析结论/.test(normalized)) {
      section = 'conclusions';
      continue;
    }
    if (/^建议[:：]?$|后续建议/.test(normalized)) {
      section = 'suggestions';
      continue;
    }

    if (section === 'info' && /^[-*]\s*/.test(line)) {
      const item = cleanMarkdown(line.replace(/^[-*]\s*/, ''));
      const parts = item.split(/[：:]/);
      if (parts.length >= 2) {
        report.info.push({
          label: parts.shift().trim(),
          value: parts.join('：').trim()
        });
      }
      continue;
    }

    if (section === 'metrics' && /^\|/.test(line)) {
      const cells = line.split('|').map(cleanMarkdown).filter(Boolean);
      if (cells.length < 2 || /指标|---/.test(cells[0])) continue;
      const metric = {
        label: cells[0],
        value: cells[1],
        primary: /容积率|FAR/i.test(cells[0])
      };
      report.metrics.push(metric);
      if (metric.primary) report.far = metric;
      continue;
    }

    const numbered = normalized.match(/^\d+[.、]\s*(.+)$/);
    if (numbered && (section === 'conclusions' || section === 'suggestions')) {
      const item = numbered[1].replace(/^(.{2,12})[：:]\s*/, '$1：').trim();
      report[section].push(item);
    }
  }

  if (!report.metrics.length && !report.info.length && !report.conclusions.length) {
    return null;
  }
  return report;
};

const formatMessageBlocks = (text = '') => {
  const blocks = [];
  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);

  for (const line of lines) {
    if (/^#{1,4}\s+/.test(line)) {
      blocks.push({ type: 'heading', text: cleanMarkdown(line) });
      continue;
    }

    const ordered = line.match(/^(\d+)[.、]\s+(.+)$/);
    if (ordered) {
      const last = blocks[blocks.length - 1];
      if (last?.type === 'ordered-list') {
        last.items.push(cleanMarkdown(ordered[2]));
      } else {
        blocks.push({ type: 'ordered-list', items: [cleanMarkdown(ordered[2])] });
      }
      continue;
    }

    const unordered = line.match(/^[-*]\s+(.+)$/);
    if (unordered) {
      const last = blocks[blocks.length - 1];
      if (last?.type === 'unordered-list') {
        last.items.push(cleanMarkdown(unordered[1]));
      } else {
        blocks.push({ type: 'unordered-list', items: [cleanMarkdown(unordered[1])] });
      }
      continue;
    }

    blocks.push({ type: 'paragraph', text: cleanMarkdown(line) });
  }

  return blocks.length ? blocks : [{ type: 'paragraph', text }];
};
</script>

<style scoped>
.chat-wrapper { position: absolute; bottom: 30px; right: 30px; z-index: 1000; width: min(520px, calc(100vw - 36px)); height: min(650px, calc(100vh - 72px)); background: rgba(255, 255, 255, 0.95); border-radius: 12px; box-shadow: 0 12px 40px rgba(15,23,42,0.18); display: flex; flex-direction: column; overflow: hidden; backdrop-filter: blur(10px); border: 1px solid rgba(148, 163, 184, 0.28); }
.chat-window { flex: 1 1 auto; min-height: 0; padding: 16px; overflow-x: hidden; overflow-y: auto; background: #f7f9fb; display: flex; flex-direction: column; gap: 12px; overscroll-behavior: contain; }
.msg-bubble { box-sizing: border-box; min-width: 0; max-width: 86%; padding: 10px 14px; border-radius: 10px; font-size: 13.5px; line-height: 1.55; overflow-wrap: anywhere; word-break: break-word; }
.msg-bubble.report-bubble { width: 100%; max-width: 100%; padding: 0; overflow: hidden; }
.trace-panel, .live-trace { box-sizing: border-box; min-width: 0; max-width: 100%; margin: -2px 0 8px; padding: 7px 9px; border: 1px solid #d9e8ef; border-radius: 8px; background: #f7fbfc; overflow-wrap: anywhere; }
.trace-panel summary, .live-trace summary { cursor: pointer; color: #176b78; font-size: 11px; font-weight: 700; }
.trace-list { display: flex; flex-direction: column; gap: 7px; margin: 8px 0 0; padding: 0; list-style: none; }
.trace-panel .trace-list { max-height: 210px; padding-right: 4px; overflow-y: auto; overscroll-behavior: contain; }
.trace-item { display: grid; grid-template-columns: 20px minmax(0, 1fr); gap: 7px; align-items: start; }
.trace-marker { width: 18px; height: 18px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; color: #fff; background: #4a91a1; font-size: 9px; font-weight: 700; }
.trace-item.error .trace-marker { background: #c2414b; }
.trace-item.success .trace-marker { background: #2d8a68; }
.trace-item > div, .trace-item strong, .trace-item small { min-width: 0; display: block; overflow-wrap: anywhere; word-break: break-word; }
.trace-item strong { color: #254b58; font-size: 11px; line-height: 1.3; }
.trace-item small { margin-top: 2px; color: #6b8490; font-size: 10px; line-height: 1.35; overflow-wrap: anywhere; }
.ai-loading { flex: 0 0 auto; }
.live-trace { flex: 0 0 auto; min-height: 76px; max-height: min(250px, 42vh); margin: 0 10px 8px; overflow: hidden; }
.live-trace .trace-list { max-height: min(196px, 32vh); padding-right: 4px; overflow-y: auto; overscroll-behavior: contain; }
.user { align-self: flex-end; background: #005e95; color: white; }
.assistant { align-self: flex-start; background: white; color: #26313f; box-shadow: 0 1px 4px rgba(15,23,42,0.06); border: 1px solid rgba(226, 232, 240, 0.9); }
.command-tag { font-size: 11px; color: #005e95; font-weight: bold; margin-bottom: 5px; display: flex; align-items: center; gap: 4px; border-bottom: 1px dashed rgba(0,0,0,0.1); padding-bottom: 4px; }
.msg-content { min-width: 0; display: flex; flex-direction: column; gap: 6px; overflow-wrap: anywhere; word-break: break-word; }
.msg-content p { margin: 0; }
.message-heading { margin: 2px 0 4px; font-size: 14px; color: #0f3d5c; }
.message-list { margin: 0; padding-left: 18px; }
.message-list li { margin: 3px 0; }
.analysis-report { background: white; color: #1f2937; }
.report-header { display: flex; justify-content: space-between; gap: 12px; padding: 16px; background: linear-gradient(135deg, #f8fbff 0%, #eef7f6 100%); border-bottom: 1px solid #dde7ef; }
.report-kicker { margin: 0 0 4px; color: #3c6f7d; font-size: 11px; font-weight: 700; letter-spacing: 0; }
.report-header h2 { margin: 0; color: #102a43; font-size: 18px; line-height: 1.25; }
.far-badge { flex: 0 0 auto; min-width: 82px; padding: 8px 10px; border-radius: 8px; background: #0f766e; color: white; text-align: center; align-self: flex-start; }
.far-badge span { display: block; font-size: 11px; opacity: 0.85; }
.far-badge strong { display: block; font-size: 22px; line-height: 1.1; font-family: Consolas, monospace; }
.info-strip { display: grid; grid-template-columns: 1fr; gap: 8px; padding: 12px 16px; background: #fbfcfd; border-bottom: 1px solid #edf2f7; }
.info-item { display: grid; grid-template-columns: 78px 1fr; gap: 8px; align-items: baseline; }
.info-item span { color: #64748b; font-size: 12px; }
.info-item strong { color: #27364a; font-size: 12.5px; font-weight: 600; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 12px 16px; }
.metric-card { min-height: 62px; padding: 10px; border-radius: 8px; background: #f8fafc; border: 1px solid #e5edf3; display: flex; flex-direction: column; justify-content: space-between; }
.metric-card span { color: #667085; font-size: 12px; }
.metric-card strong { color: #162033; font-size: 15px; font-family: Consolas, monospace; line-height: 1.25; word-break: break-word; }
.metric-card.primary { background: #ecfdf5; border-color: #99d9c8; }
.metric-card.primary strong { color: #0f766e; font-size: 18px; }
.report-section { padding: 12px 16px; border-top: 1px solid #edf2f7; }
.report-section h3 { margin: 0 0 8px; font-size: 13px; color: #17324d; }
.report-list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 7px; }
.report-list li { color: #344054; }
.suggestion-list li { color: #475467; }
.context-status { flex: 0 0 auto; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 12px; color: #667085; background: #fbfcfd; border-top: 1px solid #e7edf2; font-size: 11px; }
.context-status span { padding: 3px 6px; border: 1px solid #d8e2e8; border-radius: 4px; }
.context-status.ready span { color: #116149; border-color: #a9d8c6; background: #f0faf5; }
.demo-context-btn,.ground-dem-btn { margin-left: auto; flex: 0 0 auto; padding: 5px 8px; color: #17536e; background: transparent; border: 1px solid #9ec2d1; border-radius: 4px; font-size: 11px; }.ground-dem-btn { margin-left: 0; }
.demo-context-btn:hover:not(:disabled) { color: #fff; background: #176b78; }
.input-container { flex: 0 0 auto; padding: 12px; display: flex; gap: 8px; background: white; border-top: 1px solid #eee; }
.dataset-select,.crs-select { flex: 0 0 72px; min-width: 0; border: 1px solid #ddd; border-radius: 8px; padding: 0 6px; color: #475569; background: #fff; font-size: 11px; }.crs-select { flex-basis: 76px; }.spatial-file-input { display: none; }.upload-data-btn { flex: 0 0 34px; padding: 0; display: inline-flex; align-items: center; justify-content: center; }.graph-upload-btn{flex:0 0 42px;padding:0;font-size:11px;background:#eff8ff;color:#176b87;border:1px solid #b9e1ef}
input { flex: 1; border: 1px solid #ddd; border-radius: 8px; padding: 8px; outline: none; min-width: 0; }
button { background: #005e95; color: white; border: none; padding: 0 15px; border-radius: 8px; cursor: pointer; white-space: nowrap; }
button:disabled { opacity: 0.55; cursor: not-allowed; }
.ai-loading { display: flex; align-items: center; gap: 8px; padding: 10px; color: #475569; font-size: 13px; }
.spinner { width: 14px; height: 14px; border: 2px solid #ddd; border-top-color: #005e95; border-radius: 50%; animation: spin 0.8s infinite linear; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) {
  .chat-wrapper { right: 12px; bottom: 12px; width: calc(100vw - 24px); height: min(680px, calc(100vh - 24px)); }
  .context-status .demo-context-btn { margin-left: 0; }
  .metric-grid { grid-template-columns: 1fr; }
  .report-header { flex-direction: column; }
  .far-badge { width: 100%; }
}
</style>


