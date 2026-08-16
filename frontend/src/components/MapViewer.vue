<template>
  <div class="gis-container">
    <!-- 地图容器 -->
    <div id="viewDiv" ref="mapElement"></div>

    <!-- 建筑数据源切换 -->
    <div class="building-source-panel" v-if="!loading">
      <label for="building-source">建筑数据源</label>
      <select id="building-source" v-model="buildingSource" @change="onBuildingSourceChange" :disabled="loadingSource">
        <option value="osm">OSM 在线（默认）</option>
        <option value="beijing">北京数据包</option>
        <option value="guangzhou">广州数据包</option>
      </select>
      <span v-if="loadingSource" class="source-hint">加载中…</span>
      <span v-else-if="sourceMessage" class="source-hint">{{ sourceMessage }}</span>
      <button class="basemap-toggle" @click="toggleBasemap" title="切换天地图瓦片显示/隐藏">
        {{ basemapVisible ? '隐藏底图' : '显示底图' }}
      </button>
    </div>

    <!-- 加载遮罩 -->
    <div v-if="loading" class="map-loader">
      <div class="spinner"></div>
      <span>正在加载地图...</span>
    </div>
    <div v-else-if="loadError" class="map-error" role="alert">{{ loadError }}</div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, shallowRef, ref } from 'vue';
import axios from "axios";
import { getGisContextVersion, getGisSessionId, setGisContextVersion } from "../gisSession";
import spatialClickTemplate from "../prompts/spatial-click.txt?raw";
import { loadGeoSceneModules } from "../map/geoSceneAdapter";
import { createTiandituBasemap } from "../map/tiandituBasemap";

const emit = defineEmits(['map-ready', 'spatial-click']);

const view = shallowRef(null);
const mapElement = ref(null);
const loading = ref(true);
const loadError = ref('');
// GeoScene Graphic.attributes 可能包含 Accessor 子类实例，不能深代理。
const buildingInfo = shallowRef(null);
let sketchViewModel = null;
let highlightHandle = null; // 用于管理高亮状态
  let GeoJSONLayerCtor = null; // onMounted 动态导入后赋值，供建筑图层函数使用
  let ElevationLayerCtor = null; // 地形图层

// 建筑数据源切换：OSM 在线（默认）/ 北京数据包 / 广州数据包
const buildingSource = ref('osm');
const loadingSource = ref(false);
const sourceMessage = ref('');
let buildingGraphicsLayer = null;
// 底图（天地图）显隐：方便单独查看 3D 建筑体块效果
let basemapRef = null;
const basemapVisible = ref(true);

function toggleBasemap() {
  if (!basemapRef) return;
  basemapVisible.value = !basemapVisible.value;
  basemapRef.layers.forEach((layer) => { layer.visible = basemapVisible.value; });
}
let buildingLayerHandle = null;

async function onBuildingSourceChange() {
  const city = buildingSource.value;
  sourceMessage.value = '';
  if (city === 'osm') {
    // 回到在线模式：清除本地加载的建筑图层与上下文中的 buildings
    if (buildingGraphicsLayer) buildingGraphicsLayer.removeAll();
    sourceMessage.value = '使用 OSM Overpass 在线建筑数据';
    return;
  }
  loadingSource.value = true;
  try {
    const { data } = await axios.get('/api/gis/demo-buildings', { params: { city } });
    if (data?.status !== 'Success' || !data?.data) {
      sourceMessage.value = '数据包加载失败：' + (data?.message || '未知错误');
      return;
    }
    const fc = data.data;
    // 上传到当前会话上下文（供 Agent 分析使用）
    const upload = await axios.post('/api/gis/upload-context', {
      memoryId: getGisSessionId(),
      contextVersion: getGisContextVersion(),
      buildings: fc
    });
    setGisContextVersion(upload.data?.contextVersion);
    // 地图上显示建筑轮廓
    await showBuildingFootprints(fc, city);
    const count = fc?.features?.length ?? 0;
    sourceMessage.value = `已加载 ${city === 'beijing' ? '北京' : '广州'} 数据包（${count} 栋），可对话分析`;
  } catch (e) {
    console.error('加载建筑数据包失败', e);
    sourceMessage.value = '加载失败：' + (e?.message || '网络错误');
  } finally {
    loadingSource.value = false;
  }
}

async function showBuildingFootprints(featureCollection, city) {
  // 用 GeoJSONLayer（GPU 批量渲染）替代逐 Graphic 添加，万级建筑不卡顿
  if (buildingGraphicsLayer) {
    if (view.value) view.value.map.remove(buildingGraphicsLayer);
    buildingGraphicsLayer = null;
  }
  if (!featureCollection?.features?.length) return;
  // 缺失 height 的建筑补默认 15m：SizeVariable 把 null 当 0 会渲染成
  // 几乎贴地的矮块，统一默认高度更符合建筑体量观感。
  const prepared = {
    ...featureCollection,
    features: (featureCollection.features || []).map(feature => {
      const props = feature.properties || {};
      if (props.height === null || props.height === undefined) {
        return { ...feature, properties: { ...props, height: 15 } };
      }
      return feature;
    })
  };
  const blob = new Blob([JSON.stringify(prepared)], { type: 'application/geo+json' });
  const url = URL.createObjectURL(blob);
  const layer = new GeoJSONLayerCtor({
    title: 'city-buildings',
    url,
    // 必须显式请求字段：SizeVariable 按 height 字段驱动拉伸高度时，
    // 缺少 outFields 会取不到值，3D 拉伸退化为统一高度。
    outFields: ["*"],
    // 3D 拉伸：用 SizeVariable（size visual variable）按 height 字段
    // 映射拉伸高度（米），建筑按真实高度呈现立体体块。实测确认
    // GeoJSONLayer 的 SimpleRenderer.valueExpression 不驱动 extrude size，
    // SizeVariable 是 GeoScene 4.29 的有效方案。
    renderer: {
      type: 'simple',
      symbol: {
        type: 'polygon-3d',
        symbolLayers: [{
          type: 'extrude',
          size: 15,
          material: { color: [255, 170, 0, 0.65] },
          edges: { type: 'solid', color: [200, 120, 0, 0.8] }
        }]
      },
      visualVariables: [{
        type: 'size',
        field: 'height',
        minDataValue: 0,
        maxDataValue: 72,
        minSize: 2,
        maxSize: 72
      }]
    },
    elevationInfo: { mode: 'on-the-ground' }
  });
  buildingGraphicsLayer = layer;
  if (view.value) view.value.map.add(layer);
  // 视角定位到数据包范围（北京/广州城区）
  if (view.value) {
    const center = city === 'beijing' ? [116.39, 39.905] : [113.275, 23.125];
    view.value.goTo({ center, zoom: 13 }, { duration: 1000 }).catch(() => {});
  }
}

onMounted(async () => {
  let GeoSceneConfig;
  let Map;
  let SceneView;
  let GraphicsLayer;
  let GeoJSONLayer;
  let ElevationLayer;
  let Sketch;
  let SketchViewModel;
  let Zoom;
  let Compass;
  let NavigationToggle;
  let webMercatorUtils;
  try {
    [
      GeoSceneConfig,
      Map,
      SceneView,
      GraphicsLayer,
      GeoJSONLayer,
      ElevationLayer,
      Sketch,
      SketchViewModel,
      Zoom,
      Compass,
      NavigationToggle,
      webMercatorUtils,
    ] = await loadGeoSceneModules([
      "geoscene/config",
      "geoscene/Map",
      "geoscene/views/SceneView",
      "geoscene/layers/GraphicsLayer",
      "geoscene/layers/GeoJSONLayer",
      "geoscene/layers/ElevationLayer",
      "geoscene/widgets/Sketch",
      "geoscene/widgets/Sketch/SketchViewModel",
      "geoscene/widgets/Zoom",
      "geoscene/widgets/Compass",
      "geoscene/widgets/NavigationToggle",
      "geoscene/geometry/support/webMercatorUtils",
    ]);
  } catch (error) {
    loading.value = false;
    console.error("GeoScene SDK 加载失败", error);
    loadError.value = "GeoScene Maps SDK 未能加载，请确认 @geoscene/core npm 包已安装。";
    return;
  }
  GeoJSONLayerCtor = GeoJSONLayer;
  ElevationLayerCtor = ElevationLayer;
  GeoSceneConfig.locale = "zh-CN";

  // 天地图 2D 矢量底图（vec_w + cva_w 注记，导航风格含 POI）；建筑轮廓由
  // Overpass 主路径提供，osm-3d SceneLayer 底图在 GeoScene 环境不可用。
  // 地形仍用 GeoScene 高程服务。
  const basemap = await createTiandituBasemap({ type: "vec" });
  basemapRef = basemap;
  basemapVisible.value = true;
  const map = new Map({
    basemap: basemap
  });
  // 显式指定 ArcGIS World Elevation 服务（SDK 默认服务在部分网络不可达，
  // 会导致 queryElevation 采样失败/返回空）。ground.layers 需要 ElevationLayer 实例。
  const terrainLayer = new ElevationLayerCtor({
    url: "https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer"
  });
  map.ground.layers.add(terrainLayer);
  // 注意：不要再把 Terrain3D 影像作为叠加图层加入地图——其默认渲染是暗色
  // 地形着色，会让整图变暗（山体亮面尤其明显）。地形起伏由 ground 提供，
  // 高程采样也走 ground，叠加层不必要。
  const aoiGraphicsLayer = new GraphicsLayer({
    id: "manual_aoi_layer",
    title: "手动红线图层",
    elevationInfo: { mode: "on-the-ground" } // 让红线贴地显示
  });
  map.add(aoiGraphicsLayer);



  const sceneView = new SceneView({
    container: "viewDiv",
    map: map,
    // 虚拟光源跟随相机；显式白色环境光避免初始视角偏暗
    environment: {
      lighting: {
        type: "virtual",
        ambient: { color: [255, 255, 255, 1] },
        directShadowsEnabled: false
      },
      atmosphereEnabled: true,
      starsEnabled: false,
      background: {
        type: "color",
        color: [205, 232, 250, 1]
      }
    }
  });
  // GeoScene 原生控件：Sketch 提供可见绘制入口，ViewModel 同时供页面按钮调用。
  sketchViewModel = new SketchViewModel({ layer: aoiGraphicsLayer, view: sceneView });
  const sketch = new Sketch({
    view: sceneView,
    layer: aoiGraphicsLayer,
    viewModel: sketchViewModel,
    creationMode: "single",
    availableCreateTools: ["polygon", "rectangle"],
  });
  // GeoScene 4.29 的 SceneView 默认 UI 已自带 Zoom/Compass/NavigationToggle
  // （top-left），先清空该位置再按需重放，避免控件重复叠加。
  sceneView.ui.empty("top-left");
  sceneView.ui.add(new Zoom({ view: sceneView }), "top-left");
  sceneView.ui.add(new Compass({ view: sceneView }), "top-left");
  // 导航模式切换（平移 / 旋转倾斜相机）：与 Zoom/Compass 同列竖排（GeoScene 标准布局）
  sceneView.ui.add(new NavigationToggle({ view: sceneView }), "top-left");
  sceneView.ui.add(sketch, "top-right");
  sketchViewModel.on("create", async (event) => {
    // 1. 当用户点击地图准备画新线的一瞬间，立刻清空图层里的旧红线
    if (event.state === "start") {
      aoiGraphicsLayer.removeAll();
      // A new drawing replaces the old manual boundary only after it is
      // completed.  Clear the client-side lock here so a cancelled drawing
      // cannot accidentally keep an invisible AOI active.
      window.lastManualAoiGeometry = null;
      window.currentAoiSource = null;
      window.lastBufferGeometry = null;
      console.log("🧹 检测到新绘图任务，已自动清理旧红线");
    }

    if (event.state === "complete") {
      const geometry = event.graphic.geometry;

      // 保存红线几何到全局，供 getScreenBuildings 使用
      window.lastBufferGeometry = geometry;
      window.lastManualAoiGeometry = geometry;
      window.currentAoiSource = "manual_draw";
      console.log("💾 红线几何已保存到全局变量");

      // GeoJSON 必须使用 WGS84 经纬度，不能直接上传 Web Mercator 米制坐标
      const wgs84Geometry = geometry.spatialReference?.isWGS84
        ? geometry
        : webMercatorUtils.webMercatorToGeographic(geometry);
      if (!wgs84Geometry?.rings) throw new Error("无法将绘制 AOI 转换为 WGS84 GeoJSON");
      const aoiGeoJson = {
        type: "Feature",
        geometry: {
          type: "Polygon",
          coordinates: wgs84Geometry.rings
        },
        properties: { source: "manual_draw", timestamp: Date.now(), spatialReference: "EPSG:4326" }
      };

      console.log("📍 手动红线已就绪，正在同步至智能体上下文...");

      // 【关键动作】：立刻同步给后端
      try {
        const response = await axios.post("/api/gis/upload-context", {
          memoryId: getGisSessionId(),
          contextVersion: getGisContextVersion(),
          aoi: aoiGeoJson
        });
        setGisContextVersion(response.data?.contextVersion);
        // 红线就绪，通知智能体分析此区域
        window.dispatchEvent(new CustomEvent("sketch-aoi-ready", {
          detail: {
            aoiGeometry: geometry,
            contextVersion: response.data?.contextVersion
          }
        }));
      } catch (e) {
        console.error("同步红线失败", e);
      }
    }
  });
  sceneView.when(async () => {
    loading.value = false;
    emit('map-ready', sceneView);
    // 暴露给外部调试/自动化（与 window.lastBufferGeometry 等既有模式一致）
    window.__mapView = sceneView;

    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        ({ coords }) => sceneView.goTo({ center: [coords.longitude, coords.latitude], zoom: 14 }, { duration: 1200 }).catch(() => {}),
        () => {},
        { enableHighAccuracy: false, timeout: 3000, maximumAge: 300000 }
      );
    }

    // 监听点击实现“感知-运镜-分析”闭环
    sceneView.on("click", async (event) => {
      const response = await sceneView.hitTest(event);
      const results = response.results.filter(r => r.graphic && r.graphic.layer);

      if (results.length > 0) {
        const graphic = results[0].graphic;

        // 1. UI 状态更新
        buildingInfo.value = graphic.attributes;

        // 2. 智能运镜：平滑飞向目标建筑
        sceneView.goTo({
          target: graphic,
          tilt: 60,
          scale: 2000
        }, { duration: 1500, easing: "in-out-expo" });

        // 3. 视觉高亮
        if (highlightHandle) highlightHandle.remove();
        const layerView = await sceneView.whenLayerView(graphic.layer);
        highlightHandle = layerView.highlight(graphic);

        // 4. 构建 AI 上下文（兼容 Overpass OSM tags 与 SceneLayer 属性两种来源）
        const attributes = graphic.attributes || {};
        const context = spatialClickTemplate
          .replaceAll("{{NAME}}", String(
            attributes.name || attributes.Name || attributes.OBJECTID
            || (attributes.osm_id ? `未命名建筑 #${attributes.osm_id}` : "未命名建筑")
          ))
          .replaceAll("{{HEIGHT}}", String(attributes.height || attributes.HEIGHT || attributes.H_AVG || attributes.render_height || "未知"))
          .replaceAll("{{FLOORS}}", String(attributes.floors || attributes.levels || attributes["building:levels"] || "未知"));
        emit('spatial-click', context);
      }
    });
  });

  view.value = sceneView;
});

onUnmounted(() => {
  if (view.value) view.value.destroy();
});
</script>

<style scoped>
.gis-container {
  position: relative;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
}

#viewDiv {
  padding: 0;
  margin: 0;
  height: 100%;
  width: 100%;
  background-color: #cde8fa;
}

/* 工业感信息面板：玻璃拟态效果 */
.info-overlay {
  position: absolute;
  top: 20px;
  right: 20px;
  z-index: 100;
  pointer-events: none;
}

.glass-card {
  padding: 15px 25px;
  background: rgba(10, 25, 45, 0.7);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(0, 255, 255, 0.3);
  border-radius: 4px;
  color: #fff;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.5);
}

.glass-card h4 {
  margin: 0 0 10px 0;
  color: #00f2ff;
  font-size: 14px;
  letter-spacing: 1px;
}

.glass-card p {
  margin: 5px 0;
  font-size: 13px;
  opacity: 0.9;
}

/* 建筑数据源切换面板：GeoScene 组件同款浅色风格 */
.building-source-panel {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 500;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: #ffffff;
  border: 1px solid #d4d4d4;
  border-radius: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
  font-size: 12px;
  font-family: "Avenir Next", "Segoe UI", Arial, sans-serif;
  color: #4c4c4c;
}
.building-source-panel label {
  white-space: nowrap;
  font-weight: 600;
  color: #4c4c4c;
}
.building-source-panel select {
  background: #ffffff;
  color: #2b2b2b;
  border: 1px solid #a8a8a8;
  border-radius: 3px;
  padding: 3px 6px;
  font-size: 12px;
  font-family: inherit;
  outline: none;
}
.building-source-panel select:hover { border-color: #0079c1; }
.building-source-panel select:focus { border-color: #0079c1; box-shadow: 0 0 0 1px #0079c1; }
.basemap-toggle {
  border: 1px solid #a8a8a8;
  border-radius: 3px;
  background: #f8f8f8;
  color: #2b2b2b;
  padding: 3px 8px;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.basemap-toggle:hover { border-color: #0079c1; color: #0079c1; }
.source-hint {
  max-width: 240px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #6e6e6e;
}


.map-error {
  position: absolute;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 24px;
  color: #f3f8fb;
  background: #050a10;
  text-align: center;
}

.spinner {
  width: 50px;
  height: 50px;
  border: 3px solid rgba(0, 242, 255, 0.2);
  border-top-color: #00f2ff;
  border-radius: 50%;
  animation: spin 1s infinite linear;
  margin-bottom: 15px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 移除 GeoScene 地图画布默认聚焦框 */
:deep(.geoscene-view-surface--inset-outline:focus) {
  outline: none !important;
}
</style>

