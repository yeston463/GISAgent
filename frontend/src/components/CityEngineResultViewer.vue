<template>
  <div v-if="visible" class="result-overlay">
    <section class="result-window">
      <header class="result-header">
        <div><strong>CityEngine 三维成果</strong><span v-if="jobId">作业 {{ jobId }}</span></div>
        <button class="close-button" type="button" @click="close">×</button>
      </header>
      <div class="result-toolbar">
        <input v-model.trim="sceneServiceUrl" type="url" placeholder="粘贴发布后的 SceneServer URL" @keyup.enter="loadSceneService" />
        <button type="button" :disabled="!sceneServiceUrl || loading" @click="loadSceneService">{{ loading ? '加载中…' : '加载三维成果' }}</button>
        <button v-if="jobId" type="button" :disabled="retrying" @click="retryPublication">{{ retrying ? '发布中…' : '重新发布' }}</button>
        <a v-if="slpkUrl" :href="slpkUrl" target="_blank" rel="noopener">下载 SLPK</a>
      </div>
      <div v-if="hasModelDecisions" class="decision-summary">
        <div class="decision-stats">
          <strong>建模决策</strong>
          <span>原样保留 {{ geometrySummary.preservedCount || 0 }}</span>
          <span>明确调整 {{ geometrySummary.changedGeometryCount || 0 }}</span>
          <span>跳过 {{ geometrySummary.skippedCount || 0 }}</span>
          <span>近似替代 {{ geometrySummary.approximatedCount || 0 }}</span>
          <span>实测/属性高度 {{ geometrySummary.trustedHeightCount || 0 }}</span>
          <span>估算高度 {{ geometrySummary.estimatedHeightCount || 0 }}</span>
        </div>
        <p v-if="heightSourceText" class="height-provenance">高度来源：{{ heightSourceText }}</p>
        <p>{{ geometrySummary.message }}</p>
        <details v-if="decisionRows.length">
          <summary>查看逐栋决策与原因（{{ decisionRows.length }}）</summary>
          <ul>
            <li v-for="(row, index) in decisionRows" :key="`${row.buildingId || 'building'}-${row.kind}-${index}`">
              <strong>{{ row.name || row.buildingId || '未命名建筑' }}</strong>
              <span>{{ row.label }}{{ row.originalVertexCount ? ` · ${row.originalVertexCount} 个原始顶点` : '' }}{{ row.geometrySource ? ` · 来源 ${row.geometrySource}` : '' }}</span>
              <small>{{ row.reason }}</small>
            </li>
          </ul>
        </details>
      </div>
      <div class="viewer-body">
        <div ref="sceneElement" class="scene-view"></div>
        <div v-if="!activeSceneUrl" class="empty-state">
          <strong>{{ publicationMessage || 'SLPK 已生成，正在等待 GeoScene SceneServer' }}</strong>
          <p>浏览器展示的是发布后的 Scene Service，本地 SLPK 不能直接加载。</p>
          <p>成果会在这个独立窗口展示，不会叠加到原始地图图层。</p>
        </div>
        <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { loadGeoSceneModules } from '../map/geoSceneAdapter';

let geoSceneModulesPromise;

const getGeoSceneModules = () => {
  if (!geoSceneModulesPromise) {
    geoSceneModulesPromise = loadGeoSceneModules([
      'geoscene/Map',
      'geoscene/views/SceneView',
      'geoscene/layers/SceneLayer'
    ]);
  }
  return geoSceneModulesPromise;
};

const visible = ref(false);
const jobId = ref('');
const slpkUrl = ref('');
const sceneServiceUrl = ref('');
const activeSceneUrl = ref('');
const loading = ref(false);
const retrying = ref(false);
const publicationMessage = ref('');
const errorMessage = ref('');
const geometrySummary = ref({});
const optimizationActions = ref([]);
const sceneElement = ref(null);
const sceneView = shallowRef(null);

const geometryDecisionLabel = {
  preserve_footprint: '保留原始轮廓',
  apply_explicit_setback: '按明确要求调整轮廓',
  skip_approximate_footprint: '拒绝包围盒近似',
  skip_invalid_footprint: '跳过无效轮廓'
};
const actionLabel = {
  reduce_height: '调整建筑高度',
  apply_setback: '执行建筑退界'
};
const decisionRows = computed(() => [
  ...((geometrySummary.value.decisions || []).map(item => ({
    ...item,
    kind: 'geometry',
    label: geometryDecisionLabel[item.decision] || item.decision || '轮廓决策'
  }))),
  ...(optimizationActions.value.map(item => ({
    ...item,
    kind: 'optimization',
    label: actionLabel[item.action] || item.action || '指标调整'
  })))
]);
const hasModelDecisions = computed(() =>
  Boolean(geometrySummary.value.message || decisionRows.value.length)
);
const heightSourceLabels = {
  scene_mesh_z_range: 'SceneLayer Mesh 高差',
  scene_attribute_height: 'SceneLayer 高度属性',
  input_height: '输入高度属性',
  height: '输入高度属性',
  render_height: '渲染高度属性',
  HEIGHT: '高度字段',
  H_AVG: '平均高度字段',
  levels_inferred: '楼层推算',
  building_type_estimated: '类型/面积估算',
  unknown: '未知来源'
};
const heightSourceText = computed(() => Object.entries(geometrySummary.value.heightSourceCounts || {})
  .map(([source, count]) => `${heightSourceLabels[source] || source} ${count} 栋`)
  .join('；'));

const reportLoadStatus = (status, message) => {
  window.dispatchEvent(new CustomEvent('cityengine-scene-load-status', {
    detail: {
      jobId: jobId.value,
      sceneServiceUrl: sceneServiceUrl.value,
      status,
      message
    }
  }));
};

const destroyView = () => {
  if (sceneView.value) {
    sceneView.value.destroy();
    sceneView.value = null;
  }
};

// 浏览器不信任 GeoScene 自签名证书，SceneLayer 直接访问 https 域名会
// Failed to fetch。把托管 SceneServer 映射为同源 /geoscene-server 代理路径，
// 由 Vite/nginx 服务端完成 TLS 握手后再转发。
const GEOSCENE_SERVER_ORIGIN = 'https://product.geosceneenterprise.cn';
const proxySceneUrl = (url) => {
  if (!url) return '';
  const trimmed = url.trim();
  if (trimmed.startsWith(GEOSCENE_SERVER_ORIGIN)) {
    return trimmed.replace(GEOSCENE_SERVER_ORIGIN, '/geoscene-server');
  }
  return trimmed;
};

const createView = async (spatialReference) => {
  destroyView();
  await nextTick();
  if (!sceneElement.value) return null;
  const [Map, SceneView] = await getGeoSceneModules();
  const map = new Map({ basemap: null, ground: null });
  const view = new SceneView({
    container: sceneElement.value,
    map,
    viewingMode: 'local',
    spatialReference,
    qualityProfile: 'high',
    environment: { atmosphereEnabled: false, starsEnabled: false }
  });
  sceneView.value = view;
  await view.when();
  return view;
};

const loadSceneService = async () => {
  const targetUrl = proxySceneUrl(sceneServiceUrl.value);
  if (!targetUrl) return;
  loading.value = true;
  errorMessage.value = '';
  reportLoadStatus('running', '正在连接 GeoScene SceneServer');
  try {
    const [, , SceneLayer] = await getGeoSceneModules();
    const layer = new SceneLayer({ url: targetUrl, title: 'CityEngine 优化成果' });
    await layer.load();
    const spatialReference = layer.spatialReference || layer.fullExtent?.spatialReference;
    if (!spatialReference) throw new Error('Scene Service 未返回空间参考');
    const view = await createView(spatialReference);
    view.map.add(layer);
    activeSceneUrl.value = targetUrl;
    await view.goTo(layer.fullExtent || layer, { duration: 1200 });
    reportLoadStatus('success', 'SceneServer 已加载，CityEngine 三维成果展示完成');
  } catch (error) {
    activeSceneUrl.value = '';
    errorMessage.value = `三维成果加载失败：${error?.message || '请检查 SceneServer 地址和访问权限'}`;
    reportLoadStatus('error', errorMessage.value);
  } finally {
    loading.value = false;
  }
};

const sleep = (milliseconds) => new Promise(resolve => window.setTimeout(resolve, milliseconds));

const readJob = async () => {
  const response = await fetch(`/analysis/cityengine/jobs/${encodeURIComponent(jobId.value)}`);
  if (!response.ok) throw new Error(`读取作业失败（HTTP ${response.status}）`);
  return response.json();
};

const applyJobStatus = async (job) => {
  const serviceUrl = job?.sceneServiceUrl || job?.publication?.sceneServiceUrl || '';
  const progress = job?.publicationProgress || {};
  geometrySummary.value = job?.geometrySummary || geometrySummary.value;
  optimizationActions.value = Array.isArray(job?.optimizationActions)
    ? job.optimizationActions
    : optimizationActions.value;
  publicationMessage.value = progress.message || (serviceUrl ? 'Scene Service 已发布' : '等待 GeoScene 发布');
  if (serviceUrl) {
    sceneServiceUrl.value = serviceUrl;
    errorMessage.value = '';
    await loadSceneService();
    return true;
  }
  if (progress.status === 'error' || job?.publication?.status === 'failed') {
    errorMessage.value = progress.message || job?.publication?.message || 'Portal 项目已创建，但 SceneServer 服务未生成';
  }
  return false;
};

const refreshPublicationStatus = async () => {
  if (!jobId.value) return false;
  return applyJobStatus(await readJob());
};

const retryPublication = async () => {
  if (!jobId.value || retrying.value) return;
  retrying.value = true;
  errorMessage.value = '';
  publicationMessage.value = '正在重新发布到 GeoScene Portal…';
  reportLoadStatus('running', publicationMessage.value);
  try {
    const response = await fetch(`/analysis/cityengine/jobs/${encodeURIComponent(jobId.value)}/publish`, {
      method: 'POST'
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.detail || `重新发布失败（HTTP ${response.status}）`);
    }
    for (let attempt = 0; attempt < 200 && visible.value; attempt += 1) {
      await sleep(3000);
      const job = await readJob();
      if (await applyJobStatus(job)) return;
      const progress = job?.publicationProgress || {};
      if (progress.status === 'error' || job?.publication?.status === 'failed') {
        throw new Error(progress.message || job?.publication?.message || 'GeoScene 发布失败');
      }
    }
    if (visible.value) throw new Error('等待 SceneServer 发布超时，请查看 Python 服务日志');
  } catch (error) {
    errorMessage.value = `重新发布失败：${error?.message || '未知错误'}`;
    publicationMessage.value = 'Portal 项目已创建，但 SceneServer 服务未生成';
    reportLoadStatus('error', errorMessage.value);
  } finally {
    retrying.value = false;
  }
};

const openResult = async (event) => {
  const detail = event.detail || {};
  jobId.value = detail.jobId || '';
  slpkUrl.value = detail.slpkUrl || '';
  sceneServiceUrl.value = detail.sceneServiceUrl || '';
  activeSceneUrl.value = '';
  publicationMessage.value = detail.publicationMessage || '';
  errorMessage.value = '';
  geometrySummary.value = detail.geometrySummary || {};
  optimizationActions.value = Array.isArray(detail.optimizationActions) ? detail.optimizationActions : [];
  visible.value = true;
  if (jobId.value) {
    try {
      if (await refreshPublicationStatus()) return;
    } catch (error) {
      errorMessage.value = error?.message || '无法读取 GeoScene 发布状态';
    }
  }
  if (sceneServiceUrl.value) await loadSceneService();
};

const close = () => {
  visible.value = false;
  destroyView();
};

onMounted(() => window.addEventListener('show-cityengine-result', openResult));
onUnmounted(() => {
  window.removeEventListener('show-cityengine-result', openResult);
  destroyView();
});
</script>

<style scoped>
/* GeoScene 组件同款浅色风格 */
.result-overlay { position: fixed; top: 24px; right: 24px; bottom: 24px; left: min(650px, 46vw); z-index: 1200; pointer-events: none; font-family: "Avenir Next","Segoe UI",Arial,sans-serif; }
.result-window { width: 100%; height: 100%; display: flex; flex-direction: column; overflow: hidden; pointer-events: auto; border: 1px solid #d4d4d4; border-radius: 4px; background: #ffffff; box-shadow: 0 2px 8px rgba(0,0,0,.3); }
.result-header, .result-toolbar { display: flex; align-items: center; gap: 12px; padding: 12px 16px; }
.result-header { justify-content: space-between; color: #2b2b2b; border-bottom: 1px solid #e0e0e0; }
.result-header div { display: flex; align-items: baseline; gap: 12px; }
.result-header span { color: #6e6e6e; font-size: 12px; }
.close-button { border: 0; background: transparent; color: #6e6e6e; font-size: 22px; cursor: pointer; }
.close-button:hover { color: #0079c1; }
.result-toolbar { border-bottom: 1px solid #e0e0e0; background: #f8f8f8; }
.result-toolbar input { min-width: 0; flex: 1; padding: 6px 7px; border: 1px solid #a8a8a8; border-radius: 3px; color: #2b2b2b; background: #ffffff; font: 12px Consolas,monospace; outline: none; }
.result-toolbar input:focus { border-color: #0079c1; box-shadow: 0 0 0 1px #0079c1; }
.result-toolbar button, .result-toolbar a { padding: 6px 12px; border: 1px solid #a8a8a8; border-radius: 3px; color: #2b2b2b; background: #f8f8f8; text-decoration: none; cursor: pointer; font-size: 12px; }
.result-toolbar button:hover, .result-toolbar a:hover { border-color: #0079c1; color: #0079c1; }
.result-toolbar button:disabled { opacity: .45; cursor: not-allowed; }
.decision-summary { padding: 10px 16px; color: #4c4c4c; border-bottom: 1px solid #e0e0e0; background: #f8f8f8; font-size: 12px; }
.decision-stats { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; }
.decision-stats strong { color: #2b2b2b; }
.decision-stats span { color: #6e6e6e; font-size: 12px; }
.decision-summary p { margin: 7px 0; color: #6e6e6e; font-size: 12px; }
.decision-summary details { font-size: 12px; }
.decision-summary summary { width: fit-content; color: #0079c1; cursor: pointer; }
.decision-summary ul { max-height: 150px; margin: 8px 0 0; padding: 0; overflow: auto; list-style: none; }
.decision-summary li { display: grid; grid-template-columns: minmax(100px, .7fr) minmax(130px, 1fr) minmax(220px, 2fr); gap: 10px; padding: 7px 0; border-top: 1px solid #e0e0e0; }
.decision-summary li strong { overflow-wrap: anywhere; color: #2b2b2b; }
.decision-summary li span { color: #0079c1; }
.decision-summary li small { color: #6e6e6e; }
.viewer-body { position: relative; flex: 1; min-height: 0; }
.scene-view { position: absolute; inset: 0; }
.empty-state { position: absolute; inset: 0; z-index: 2; display: grid; place-content: center; gap: 10px; padding: 32px; text-align: center; color: #4c4c4c; pointer-events: none; background: #ffffff; }
.empty-state strong { color: #2b2b2b; font-size: 17px; }
.empty-state p { margin: 0; color: #6e6e6e; }
.error-message { position: absolute; left: 50%; bottom: 20px; z-index: 4; transform: translateX(-50%); padding: 10px 14px; border: 1px solid #d75d68; border-radius: 4px; color: #c62828; background: #fff5f5; }
@media (max-width: 1050px) {
  .result-overlay { top: 16px; right: 16px; bottom: 16px; left: 38vw; }
}
@media (max-width: 760px) {
  .result-overlay { top: 12px; right: 12px; bottom: 12px; left: 12px; }
  .decision-summary li { grid-template-columns: 1fr; gap: 3px; }
}
</style>

