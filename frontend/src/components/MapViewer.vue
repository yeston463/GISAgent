<template>
  <div class="gis-container">
    <!-- 地图容器 -->
    <div id="viewDiv" ref="mapElement"></div>



    <!-- 加载遮罩 -->
    <div v-if="loading" class="map-loader">
      <div class="spinner"></div>
      <span>正在加载三维空间...</span>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, shallowRef, ref } from 'vue';
import Map from "@arcgis/core/Map";
import SceneView from "@arcgis/core/views/SceneView";
import esriConfig from "@arcgis/core/config";
import GraphicsLayer from "@arcgis/core/layers/GraphicsLayer";
import Sketch from "@arcgis/core/widgets/Sketch";
import axios from "axios";
import { getGisContextVersion, getGisSessionId, setGisContextVersion } from "../gisSession";
import * as webMercatorUtils from "@arcgis/core/geometry/support/webMercatorUtils.js";
import spatialClickTemplate from "../prompts/spatial-click.txt?raw";
// 修改这一行
import * as intl from "@arcgis/core/intl";

const emit = defineEmits(['map-ready', 'spatial-click']);

const view = shallowRef(null);
const mapElement = ref(null);
const loading = ref(true);
const buildingInfo = ref(null);
let highlightHandle = null; // 用于管理高亮状态

onMounted(async () => {
  esriConfig.assetsPath = "./assets";
  intl.setLocale("zh-CN");

  // 仅使用 ArcGIS 底图与用户/服务端返回的图层，禁止注入演示建筑。
  const map = new Map({
    basemap: "osm-3d",
    ground: "world-elevation"
  });
  const aoiGraphicsLayer = new GraphicsLayer({
    id: "manual_aoi_layer",
    title: "手动红线图层",
    elevationInfo: { mode: "on-the-ground" } // 让红线贴地显示
  });
  map.add(aoiGraphicsLayer);



  const sceneView = new SceneView({
    container: "viewDiv",
    map: map,
    // 使用跟随相机的虚拟光源，避免固定太阳角度让建筑背光过暗。
    environment: {
      lighting: {
        type: "virtual",
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
  const sketch = new Sketch({
    layer: aoiGraphicsLayer,
    view: sceneView,
    // Keep the completed redline visible without selecting it. "update"
    // draws an orange editing envelope which looks like a second AOI.
    creationMode: "single",
    availableCreateTools: ["polygon", "rectangle"] // 只保留面工具
  });
  // 将绘图工具添加到右上角
  sceneView.ui.add(sketch, "top-right");
  sketch.on("create", async (event) => {
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

        // 4. 构建 AI 上下文
        const attributes = graphic.attributes || {};
        const context = spatialClickTemplate
          .replaceAll("{{NAME}}", String(attributes.name || attributes.Name || attributes.OBJECTID || "未命名建筑"))
          .replaceAll("{{HEIGHT}}", String(attributes.height || attributes.HEIGHT || attributes.H_AVG || "未知"))
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

/* 加载动画 */
.map-loader {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  background: #050a10;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  z-index: 1000;
  color: #00f2ff;
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

/* 移除 ArcGIS 默认聚焦框 */
:deep(.esri-view-surface--inset-outline:focus) {
  outline: none !important;
}
</style>

