<template>
  <div class="gis-container">
    <!-- 地图容器 -->
    <div id="viewDiv" ref="mapElement"></div>



    <!-- 加载遮罩 -->
    <div v-if="loading" class="map-loader">
      <div class="spinner"></div>
      <span>正在构建数字孪生空间...</span>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, shallowRef, ref, reactive } from 'vue';
import Map from "@arcgis/core/Map";
import SceneView from "@arcgis/core/views/SceneView";
import GeoJSONLayer from "@arcgis/core/layers/GeoJSONLayer";
import esriConfig from "@arcgis/core/config";
import GraphicsLayer from "@arcgis/core/layers/GraphicsLayer";
import Sketch from "@arcgis/core/widgets/Sketch";
import axios from "axios";
import * as webMercatorUtils from "@arcgis/core/geometry/support/webMercatorUtils.js";
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

  // 1. 更加“工业风”的渲染器定义
  const buildingRenderer = {
    type: "simple",
    symbol: {
      type: "polygon-3d",
      symbolLayers: [{
        type: "extrude",
        material: { color: [45, 120, 255, 0.7] }, // 半透明科技蓝
        edges: {
          type: "solid",
          color: [255, 255, 255, 0.5],
          size: 1
        }
      }]
    },
    visualVariables: [
      {
        type: "color",
        field: "height",
        stops: [
          { value: 20, color: [100, 200, 255, 0.7] },
          { value: 100, color: [40, 80, 200, 0.8] }
        ]
      }
    ]
  };

  // 2. 模拟数据注入 (延续你的 Blob URL 神技)
  const myGeojsonData = {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        id: 1,
        properties: { ObjectID: 1, height: 55, floors: 18, name: "数字贸易中心", type: "Commercial" },
        geometry: { type: "Polygon", coordinates: [[[116.388, 39.918], [116.389, 39.918], [116.389, 39.919], [116.388, 39.919], [116.388, 39.918]]] }
      },
      {
        type: "Feature",
        id: 2,
        properties: { ObjectID: 2, height: 135, floors: 42, name: "智算总部大厦", type: "Office" },
        geometry: { type: "Polygon", coordinates: [[[116.390, 39.918], [116.391, 39.918], [116.391, 39.919], [116.390, 39.919], [116.390, 39.918]]] }
      }
    ]
  };

  const blob = new Blob([JSON.stringify(myGeojsonData)], { type: "application/json" });
  const url = URL.createObjectURL(blob);

  const buildingsLayer = new GeoJSONLayer({
    url: url,
    id: "buildings_layer",
    outFields: ["*"],
    renderer: buildingRenderer,
    popupTemplate: {
      title: "{name}",
      content: "高度: {height}m | 层数: {floors}"
    },
    visible: false
  });

  // 3. 构建 3D 场景
  const map = new Map({
    basemap: "osm-3d",
    ground: "world-elevation",
    layers: [buildingsLayer]
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
    camera: {
      position: [116.391, 39.890, 1500],
      tilt: 45,
      heading: 0
    },
    // 关键：开启高质量视觉配置
    environment: {
      lighting: {
        directShadowsEnabled: true, // 开启阴影
        date: new Date("Sun Mar 15 2024 10:00:00 GMT+0800") // 设定光照角度
      },
      atmosphere: { quality: "high" } // 高质量大气效果
    }
  });
  const sketch = new Sketch({
    layer: aoiGraphicsLayer,
    view: sceneView,
    creationMode: "update", // 绘制完后立即进入编辑模式
    availableCreateTools: ["polygon", "rectangle"] // 只保留面工具
  });
  // 将绘图工具添加到右上角
  sceneView.ui.add(sketch, "top-right");
  sketch.on("create", async (event) => {
    // 1. 当用户点击地图准备画新线的一瞬间，立刻清空图层里的旧红线
    if (event.state === "start") {
      aoiGraphicsLayer.removeAll();
      console.log("🧹 检测到新绘图任务，已自动清理旧红线");
    }

    if (event.state === "complete") {
      const geometry = event.graphic.geometry;

      // 保存红线几何到全局，供 getScreenBuildings 使用
      window.lastBufferGeometry = geometry;
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
        await axios.post("http://localhost:8080/api/gis/upload-context", {
          aoi: aoiGeoJson
        });
        // 红线就绪，通知智能体分析此区域
        window.dispatchEvent(new CustomEvent("sketch-aoi-ready"));
      } catch (e) {
        console.error("同步红线失败", e);
      }
    }
  });
  // 4. 开启 SSAO (环境光遮蔽)，增加 3D 立体感和缝隙阴影
  sceneView.when(async () => {
    const postProcessing = sceneView.environment.atmosphere;
    // 开启 SSAO 增加工业质感
    sceneView.environment.ambientOcclusionEnabled = true;

    loading.value = false;
    emit('map-ready', sceneView);

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
        const context = `[空间位置感知] 检测到用户关注建筑: ${graphic.attributes.name}。
                         技术参数: 高度 ${graphic.attributes.height}米, 楼层 ${graphic.attributes.floors}。
                         当前请求: 启动该区域的城市能耗与容积率综合分析。`;
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
  background-color: #050a10; /* 深色底色 */
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

