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
        <a v-if="slpkUrl" :href="slpkUrl" target="_blank" rel="noopener">下载 SLPK</a>
      </div>
      <div class="viewer-body">
        <div ref="sceneElement" class="scene-view"></div>
        <div v-if="!activeSceneUrl" class="empty-state">
          <strong>SLPK 已生成，但浏览器不能直接读取本地 SLPK 文件</strong>
          <p>请先将 SLPK 发布到 GeoScene Enterprise，然后把 SceneServer 地址粘贴到上方。</p>
          <p>成果会在这个独立窗口展示，不会叠加到原始地图图层。</p>
        </div>
        <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref, shallowRef } from 'vue';
import Map from '@arcgis/core/Map';
import SceneView from '@arcgis/core/views/SceneView';
import SceneLayer from '@arcgis/core/layers/SceneLayer';

const visible = ref(false);
const jobId = ref('');
const slpkUrl = ref('');
const sceneServiceUrl = ref('');
const activeSceneUrl = ref('');
const loading = ref(false);
const errorMessage = ref('');
const sceneElement = ref(null);
const sceneView = shallowRef(null);

const destroyView = () => {
  if (sceneView.value) {
    sceneView.value.destroy();
    sceneView.value = null;
  }
};

const createView = async (spatialReference) => {
  destroyView();
  await nextTick();
  if (!sceneElement.value) return null;
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
  if (!sceneServiceUrl.value) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    const layer = new SceneLayer({ url: sceneServiceUrl.value, title: 'CityEngine 优化成果' });
    await layer.load();
    const spatialReference = layer.spatialReference || layer.fullExtent?.spatialReference;
    if (!spatialReference) throw new Error('Scene Service 未返回空间参考');
    const view = await createView(spatialReference);
    view.map.add(layer);
    activeSceneUrl.value = sceneServiceUrl.value;
    await view.goTo(layer.fullExtent || layer, { duration: 1200 });
  } catch (error) {
    activeSceneUrl.value = '';
    errorMessage.value = `三维成果加载失败：${error?.message || '请检查 SceneServer 地址和访问权限'}`;
  } finally {
    loading.value = false;
  }
};

const openResult = async (event) => {
  const detail = event.detail || {};
  jobId.value = detail.jobId || '';
  slpkUrl.value = detail.slpkUrl || '';
  sceneServiceUrl.value = detail.sceneServiceUrl || '';
  activeSceneUrl.value = '';
  errorMessage.value = '';
  visible.value = true;
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
.result-overlay { position: fixed; top: 24px; right: 24px; bottom: 24px; left: min(650px, 46vw); z-index: 1200; pointer-events: none; }
.result-window { width: 100%; height: 100%; display: flex; flex-direction: column; overflow: hidden; pointer-events: auto; border: 1px solid rgba(64, 216, 255, .45); border-radius: 10px; background: #07111f; box-shadow: 0 24px 80px rgba(0, 0, 0, .55); }
.result-header, .result-toolbar { display: flex; align-items: center; gap: 12px; padding: 12px 16px; }
.result-header { justify-content: space-between; color: #eafaff; border-bottom: 1px solid rgba(255, 255, 255, .1); }
.result-header div { display: flex; align-items: baseline; gap: 12px; }
.result-header span { color: #86a8b8; font-size: 12px; }
.close-button { border: 0; background: transparent; color: #fff; font-size: 28px; cursor: pointer; }
.result-toolbar { border-bottom: 1px solid rgba(255, 255, 255, .08); background: rgba(10, 28, 45, .9); }
.result-toolbar input { min-width: 0; flex: 1; padding: 9px 11px; border: 1px solid #31516a; border-radius: 4px; color: #eafaff; background: #071522; outline: none; }
.result-toolbar button, .result-toolbar a { padding: 9px 14px; border: 1px solid #1fb9d8; border-radius: 4px; color: #dffaff; background: #08677a; text-decoration: none; cursor: pointer; }
.result-toolbar button:disabled { opacity: .45; cursor: not-allowed; }
.viewer-body { position: relative; flex: 1; min-height: 0; }
.scene-view { position: absolute; inset: 0; }
.empty-state { position: absolute; inset: 0; z-index: 2; display: grid; place-content: center; gap: 10px; padding: 32px; text-align: center; color: #b7d5df; pointer-events: none; background: radial-gradient(circle, rgba(13, 55, 77, .82), rgba(4, 13, 24, .96)); }
.empty-state strong { color: #62e5ff; font-size: 18px; }
.empty-state p { margin: 0; }
.error-message { position: absolute; left: 50%; bottom: 20px; z-index: 4; transform: translateX(-50%); padding: 10px 14px; border: 1px solid #d75d68; border-radius: 4px; color: #ffd9dc; background: rgba(92, 22, 30, .92); }
@media (max-width: 1050px) {
  .result-overlay { top: 16px; right: 16px; bottom: 16px; left: 38vw; }
}
@media (max-width: 760px) {
  .result-overlay { top: 12px; right: 12px; bottom: 12px; left: 12px; }
}
</style>

