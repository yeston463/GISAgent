import Graphic from "@arcgis/core/Graphic";
import Point from "@arcgis/core/geometry/Point";
import GeoJSONLayer from "@arcgis/core/layers/GeoJSONLayer";
import axios from "axios";

// 决赛级全局状态缓存：锁定最近一次 Python 计算的真实结果
window.lastGisResult = {
  far: 0,
  site_area: 0,
  building_area: 0,
  building_count: 0,
  status: "pending"
};

// 全局保存缓冲区几何，供 getScreenBuildings 使用
window.lastBufferGeometry = null;

export function useCommandExecutor(viewRef) {

  const getReadyView = async () => {
    if (!viewRef.value) throw new Error("地图实例尚未注入");
    await viewRef.value.when();
    return viewRef.value;
  };

  const actions = {
    // 1. 平滑视角跳转
    flyTo: async (params) => {
      console.log("✈️ [原子指令] 准备飞行至:", params);

      const lon = parseFloat(params.longitude);
      const lat = parseFloat(params.latitude);

      if (isNaN(lon) || isNaN(lat) || (Math.abs(lon) < 0.1 && Math.abs(lat) < 0.1)) {
        console.error("❌ 飞行失败：经纬度无效或为(0,0)", params);
        return;
      }

      const view = await getReadyView();

      // ArcGIS SceneView 跳转
      await view.goTo({
        center: [lon, lat], // 顺序：[经度, 纬度]
        zoom: params.zoom || 17,
        tilt: 45,
        heading: 0
      }, {
        duration: 2500,
        easing: "in-out-expo"
      }).catch(err => {
        if (err.name === "AbortError") {
          console.log("ℹ️ 飞行任务被后续指令中断（正常现象）");
        } else {
          console.error("❌ 飞行发生真实异常:", err);
        }
      });

      console.log("✅ 已到达目标区域");
    },

    getScreenBuildings: async (params) => {
      console.log("🚀 [原子指令] 开始提取区域建筑...", params);

      // 重置缓存
      window.lastGisResult = {
        far: 0, site_area: 0, building_area: 0, building_count: 0,
        status: "syncing", timestamp: Date.now()
      };

      try {
        const view = await getReadyView();
        const webMercatorUtils = await import("@arcgis/core/geometry/support/webMercatorUtils.js");

        // 找建筑图层
        console.log("🔍 查找建筑图层...");
        console.log("📋 所有图层:");
        view.map.allLayers.forEach(layer => {
          console.log(`  - ${layer.title} (${layer.type}, id: ${layer.id})`);
        });

        // 优先用底图自带的 OSM Buildings
        let osmLayer = view.map.findLayerById("osm-3d-buildings");
        if (!osmLayer) {
          osmLayer = view.map.findLayerById("osm_buildings");
        }
        if (!osmLayer) {
          osmLayer = view.map.allLayers.find(l =>
              l.type === "scene" && (l.title === "Buildings" || l.title.includes("Buildings"))
          );
        }
        
        if (!osmLayer) {
          console.error("❌ 未找到建筑图层");
          console.log("💡 提示：请确保地图中有 Buildings 图层");
          return false;
        }
        console.log("✅ 找到建筑图层:", osmLayer.title);

        // 等待图层加载
        await osmLayer.load();
        console.log("✅ 图层加载完成");

        const layerView = await view.whenLayerView(osmLayer);
        await layerView.when();
        console.log("✅ 图层视图就绪");

        // 先用视图范围测试（最多重试 3 次，等待远距离瓦片加载）
        console.log("🔍 使用视图范围测试...");
        let testResult;
        for (let attempt = 1; attempt <= 3; attempt++) {
          const testQuery = osmLayer.createQuery();
          testQuery.geometry = view.extent;
          testQuery.spatialRelationship = "intersects";
          testQuery.returnGeometry = false;
          testQuery.outFields = ["objectid"];
          testResult = await layerView.queryFeatures(testQuery);
          if (testResult.features.length > 0) break;
          console.warn(`⏳ 第${attempt}次视图测试无建筑，等待瓦片加载...`);
          await new Promise(r => setTimeout(r, 2000));
        }
        console.log("✅ 视图范围测试结果:", testResult.features.length, "栋建筑");

        if (testResult.features.length === 0) {
          console.warn("⚠️ 视图范围内无建筑，建筑图层可能未加载");
          return false;
        }

        // 使用缓冲区查询
        let queryGeometry = window.lastBufferGeometry;
        if (!queryGeometry) {
          console.log("❌ 无缓冲区几何");
          return false;
        }

        const ext = queryGeometry.extent;
        console.log("📐 缓冲区范围:", ext.xmin, ext.ymin, ext.xmax, ext.ymax);

        console.log("🔍 执行缓冲区查询（圆形几何）...");
        let bufferResult;
        for (let attempt = 1; attempt <= 3; attempt++) {
          const q = osmLayer.createQuery();
          q.geometry = queryGeometry;
          q.spatialRelationship = "intersects";
          q.returnGeometry = true;
          q.outFields = ["*"];
          bufferResult = await layerView.queryFeatures(q);
          if (bufferResult.features.length > 0) break;
          console.warn(`⏳ 第${attempt}次缓冲区查询无建筑，等待瓦片加载...`);
          await new Promise(r => setTimeout(r, 2000));
        }
        console.log("✅ 缓冲区查询结果:", bufferResult.features.length, "栋建筑");

        if (bufferResult.features.length === 0) {
          console.warn("⚠️ 缓冲区内无建筑");
          return false;
        }

        // 处理建筑数据
        const fix = (num) => parseFloat(num.toFixed(6));
        const features = bufferResult.features.map((f, index) => {
          const g = f.geometry;
          if (!g) return null;

          const attrs = f.attributes || {};
          const height = parseFloat(attrs.height || attrs.HEIGHT || attrs.H_AVG || attrs.render_height || 15);
          const idVal = attrs.objectid || attrs.OBJECTID || attrs.fid || index;

          // 优先使用实际几何 rings（精准 footprint）
          let coords;
          if (g.rings) {
            const geographicGeometry = g.spatialReference?.isWGS84
              ? g
              : webMercatorUtils.webMercatorToGeographic(g);
            if (!geographicGeometry?.rings) return null;
            coords = geographicGeometry.rings.map(ring => ring.map(p => [fix(p[0]), fix(p[1])]));
          } else if (g.extent) {
            // 降级：用 extent 构建包围盒
            const geoExt = g.extent;
            let xmin, ymin, xmax, ymax;
            if (Math.abs(geoExt.xmin) > 180) {
              const geographic = webMercatorUtils.webMercatorToGeographic(geoExt);
              xmin = geographic.xmin; ymin = geographic.ymin;
              xmax = geographic.xmax; ymax = geographic.ymax;
            } else {
              xmin = geoExt.xmin; ymin = geoExt.ymin;
              xmax = geoExt.xmax; ymax = geoExt.ymax;
            }
            coords = [[
              [fix(xmin), fix(ymin)], [fix(xmax), fix(ymin)],
              [fix(xmax), fix(ymax)], [fix(xmin), fix(ymax)],
              [fix(xmin), fix(ymin)]
            ]];
          } else {
            return null;
          }

          return {
            type: "Feature",
            geometry: { type: "Polygon", coordinates: coords },
            properties: {
              id: idVal,
              height: height,
              floors: Math.max(1, Math.round(height / 3.5))
            }
          };
        }).filter(Boolean);

        console.log("✅ 处理完成:", features.length, "栋建筑");

        // 构建 AOI：使用实际缓冲区圆形几何，而非外接矩形
        const bufferGeom = window.lastBufferGeometry;
        let aoiGeometry;
        if (bufferGeom && bufferGeom.rings) {
          const geographicAoi = bufferGeom.spatialReference?.isWGS84
            ? bufferGeom
            : webMercatorUtils.webMercatorToGeographic(bufferGeom);
          if (!geographicAoi?.rings) throw new Error("无法将 AOI 转换为 WGS84");
          aoiGeometry = {
            type: "Polygon",
            coordinates: geographicAoi.rings.map(ring => ring.map(p => [fix(p[0]), fix(p[1])]))
          };
        } else {
          // 降级：用 extent 矩形
          aoiGeometry = {
            type: "Polygon",
            coordinates: [[
              [fix(ext.xmin), fix(ext.ymin)], [fix(ext.xmax), fix(ext.ymin)],
              [fix(ext.xmax), fix(ext.ymax)], [fix(ext.xmin), fix(ext.ymax)],
              [fix(ext.xmin), fix(ext.ymin)]
            ]]
          };
        }

        // 上传到后端
        console.log("📤 上传数据...");
        const res = await axios.post("http://localhost:8080/api/gis/upload-context", {
          buildings: { type: "FeatureCollection", features: features },
          aoi: { type: "Feature", geometry: aoiGeometry }
        });

        const freshData = {
          far: parseFloat(res.data.far) || 0,
          site_area: parseFloat(res.data.site_area) || 0,
          building_area: parseFloat(res.data.building_area) || 0,
          building_count: parseInt(res.data.building_count) || 0,
          status: "Success", timestamp: Date.now()
        };

        window.lastGisResult = freshData;
        console.log("✅ 结果:", freshData);
        window.dispatchEvent(new CustomEvent("gis-data-ready"));
        return true;

      } catch (err) {
        console.error("❌ 错误:", err);
        window.lastGisResult.status = "error";
        return false;
      }
    },

    openAnalysisDashboard: async (params) => {
      console.log("📊 [仪表盘控制] 正在进行真值校验...");

      // 1. 获取真值（来自后端计算）
      const realFar = window.lastGisResult.far;

      // 2. 获取 AI 传过来的值（可能包含幻觉）
      const aiFar = params.far || params.FAR || (params.metrics && (params.metrics.far || params.metrics.FAR));

      // 3. 【核心防伪逻辑】
      // 如果真值是 0，说明 Python 还没算出来或者该地块确实没建筑
      // 此时如果 AI 传了 2.18 或 3.32 等非零值，判定为幻觉，强制纠正回 0
      let finalFar = 0;
      if (realFar > 0) {
        finalFar = realFar; // 优先信 Python
      } else {
        console.warn("⚠️ Python 没算出来或为 0，AI 可能在瞎编。");
        finalFar = 0;
      }

      const finalData = {
        far: finalFar,
        site_area: window.lastGisResult.site_area || 0,
        building_area: window.lastGisResult.building_area || 0,
        building_count: window.lastGisResult.building_count || 0,
        status: "Success"
      };

      console.log("📈 ECharts 最终接收到的【脱敏真值】:", finalData);
      window.dispatchEvent(new CustomEvent("show-gis-charts", { detail: finalData }));
    },

    // 4. 渲染分析结果几何体
    renderAnalysisResult: async (params) => {
      console.log("🎨 [原子指令] 收到几何数据，准备渲染至 3D 空间...", params);
      try {
        const view = await getReadyView();

        // 1. 解析数据 (处理字符串或对象)
        let geojson = params.geoJson;
        if (typeof geojson === 'string') {
          try {
            geojson = JSON.parse(geojson);
          } catch(e) {
            // 处理 AI 可能生成的非法转义
            geojson = JSON.parse(geojson.replace(/\\"/g, '"'));
          }
        }

        if (!geojson) {
          console.warn("❌ 渲染失败：GeoJSON 数据为空");
          return;
        }

        // 2. 清理旧图层
        const layerId = "analysis_result_layer";
        const oldLayer = view.map.findLayerById(layerId);
        if (oldLayer) view.map.remove(oldLayer);

        // 3. 创建图层
        const blob = new Blob([JSON.stringify(geojson)], { type: "application/json" });
        const url = URL.createObjectURL(blob);

        const analysisLayer = new GeoJSONLayer({
          url: url,
          id: layerId,
          title: "空间分析结果",
          // 【核心修复 1】：必须设置高程信息，让多边形“贴地”显示
          elevationInfo: {
            mode: "on-the-ground"
          },
          renderer: {
            type: "simple",
            symbol: {
              type: "simple-fill",
              color: [0, 255, 255, 0.4], // 青色半透明
              outline: {
                color: [0, 255, 255, 1],
                width: 2
              }
            }
          }
        });

        view.map.add(analysisLayer);

        // 【核心修复 2】：渲染后自动缩放到缓冲区完整范围
        console.log("📡 正在缩放至分析结果范围...");
        analysisLayer.when(() => {
          view.goTo(analysisLayer.fullExtent.expand(1.2), {
            duration: 1500,
            easing: "in-out-expo"
          });
        });

        console.log("✅ 缓冲区渲染成功");
      } catch (err) {
        console.error("❌ 渲染过程发生崩溃:", err);
      }
    },

    addGeoJsonLayer: async (params) => {
      const view = await getReadyView();
      const data = params?.data;
      if (!data) return;

      const layerId = params.layerId || `agent-geojson-${Date.now()}`;
      const oldLayer = view.map.findLayerById(layerId);
      if (oldLayer) view.map.remove(oldLayer);

      const styleMap = {
        aoi: { color: [37, 99, 235, 0.12], outline: [37, 99, 235, 1], width: 3 },
        warning: { color: [239, 68, 68, 0.72], outline: [127, 29, 29, 1], width: 1.5 },
        optimized: { color: [14, 165, 233, 0.58], outline: [3, 105, 161, 1], width: 1.5 },
        green: { color: [34, 197, 94, 0.62], outline: [21, 128, 61, 1], width: 1.5 },
        existing: { color: [148, 163, 184, 0.48], outline: [71, 85, 105, 1], width: 1 },
        existingGreen: { color: [74, 130, 104, 0.42], outline: [45, 90, 70, 1], width: 1 }
      };
      const style = styleMap[params.style] || styleMap.optimized;
      const blob = new Blob([JSON.stringify(data)], { type: "application/geo+json" });
      const url = URL.createObjectURL(blob);
      const layer = new GeoJSONLayer({
        id: layerId,
        title: params.title || "Agent 分析图层",
        url,
        elevationInfo: { mode: params.style === "warning" || params.style === "optimized" ? "on-the-ground" : "on-the-ground" },
        renderer: {
          type: "simple",
          symbol: {
            type: "simple-fill",
            color: style.color,
            outline: { color: style.outline, width: style.width }
          }
        },
        visible: params.visible !== false,
        popupTemplate: {
          title: "{name}",
          content: [{ type: "fields", fieldInfos: [
            { fieldName: "id", label: "编号" },
            { fieldName: "height", label: "高度" },
            { fieldName: "building:levels", label: "层数" },
            { fieldName: "problemReasons", label: "问题原因" }
          ] }]
        }
      });
      view.map.add(layer);
      await layer.load();
      if (layer.fullExtent) {
        await view.goTo(layer.fullExtent.expand(1.35), { duration: 1000 }).catch(() => {});
      }
    },

    switchPlanningScenario: async (params) => {
      const view = await getReadyView();
      const scenario = params?.scenario || "existing";
      const visibility = {
        existing: {
          "existing-scenario": true,
          "existing-green": true,
          "problem-buildings": false,
          "optimized-scenario": false,
          "optimized-green": false
        },
        diagnosis: {
          "existing-scenario": true,
          "existing-green": true,
          "problem-buildings": true,
          "optimized-scenario": false,
          "optimized-green": false
        },
        optimized: {
          "existing-scenario": false,
          "existing-green": false,
          "problem-buildings": false,
          "optimized-scenario": true,
          "optimized-green": true
        }
      }[scenario];
      if (!visibility) return;
      Object.entries(visibility).forEach(([layerId, visible]) => {
        const layer = view.map.findLayerById(layerId);
        if (layer) layer.visible = visible;
      });
      window.dispatchEvent(new CustomEvent("planning-scenario-changed", {
        detail: { scenario }
      }));
    },
    comparePlanningScenarios: async (params) => {
      window.dispatchEvent(new CustomEvent("show-planning-comparison", {
        detail: params?.comparison || {}
      }));
    },
    // 5. 清除红线
    clearAOI: async () => {
      const view = await getReadyView();
      const aoiLayer = view.map.findLayerById("manual_aoi_layer");
      if (aoiLayer) aoiLayer.removeAll();
      window.lastGisResult = { far:0, site_area:0, building_area:0, count:0 }; // 清空缓存
    },
    addBuffer: async (params) => {
      console.log("🎯 [原子指令] 启动前端几何引擎绘制缓冲区...", params);
      try {
        const view = await getReadyView();
        // 动态引入 ArcGIS 前端几何引擎
        const geometryEngine = await import("@arcgis/core/geometry/geometryEngine.js");
        const Graphic = (await import("@arcgis/core/Graphic.js")).default;
        const Point = (await import("@arcgis/core/geometry/Point.js")).default;

        // 解析 AI 传来的参数
        const lon = parseFloat(params.longitude);
        const lat = parseFloat(params.latitude);
        const radius = parseFloat(params.radius);

        if (isNaN(lon) || isNaN(lat) || isNaN(radius)) {
          console.error("❌ 缓冲区参数错误");
          return;
        }
        if (Math.abs(lon) < 0.1 && Math.abs(lat) < 0.1) {
          console.error("❌ 缓冲区坐标无效(0,0)，跳过");
          return;
        }

        // 创建中心点
        const centerPoint = new Point({
          x: lon,
          y: lat,
          spatialReference: { wkid: 4326 }
        });

        // 调用前端引擎直接生成缓冲多边形
        let bufferGeom;
        try {
          bufferGeom = geometryEngine.geodesicBuffer(centerPoint, radius, "meters");
        } catch (e) {
          console.warn("⚠️ geodesicBuffer 失败，尝试 buffer:", e.message);
        }
        if (!bufferGeom) {
          // fallback: 手动生成圆形缓冲区（32边形近似）
          const numPoints = 32;
          const coords = [];
          for (let i = 0; i <= numPoints; i++) {
            const angle = (i / numPoints) * Math.PI * 2;
            const dx = radius * Math.cos(angle);
            const dy = radius * Math.sin(angle);
            // 经纬度近似: 1° ≈ 111320m
            const dLon = dx / (111320 * Math.cos(lat * Math.PI / 180));
            const dLat = dy / 111320;
            coords.push([lon + dLon, lat + dLat]);
          }
          const Polygon = (await import("@arcgis/core/geometry/Polygon.js")).default;
          bufferGeom = new Polygon({
            rings: [coords],
            spatialReference: { wkid: 4326 }
          });
          console.log("🔄 使用手动生成的圆形缓冲区");
        }

        // 【新增】保存缓冲区几何到全局变量，供 getScreenBuildings 使用
        window.lastBufferGeometry = bufferGeom;
        console.log("💾 缓冲区几何已保存到全局变量");

        // 清理旧缓冲区图形（只清 view.graphics 中的 buffer，保留 sketch 红线）
        view.graphics.removeAll();

        const bufferGraphic = new Graphic({
          geometry: bufferGeom,
          symbol: {
            type: "simple-fill",
            color:[0, 255, 255, 0.3], // 青色半透明
            outline: { color: [0, 255, 255, 1], width: 2 }
          }
        });

        view.graphics.add(bufferGraphic);

        // 自动缩放到缓冲区范围
        try {
          await view.goTo(bufferGeom.extent.expand(1.5), { duration: 1000 });
        } catch (err) {
          if (err.name !== "AbortError") throw err;
        }
        console.log("✅ 缓冲区渲染完成！");

      } catch (e) {
        console.error("❌ 缓冲区渲染崩溃:", e);
      }
    },
    layerControl: async (params) => {
      const view = await getReadyView();
      const { id, visible } = params;
      console.log(`[原子指令] 调整图层状态: ${id}, 显示: ${visible}`);

      // 1. 处理标准图层 (GeoJSONLayer / FeatureLayer)
      const layer = view.map.findLayerById(id);
      if (layer) {
        layer.visible = visible;
        console.log(`✅ 图层 ${id} 已${visible ? '显示' : '隐藏'}`);
      }

      // 2. 特殊处理：如果关闭的是分析结果或缓冲区
      if (!visible && (id === "analysis_result_layer" || id.toLowerCase().includes("buffer"))) {
        // 清除通过 view.graphics.add 添加的缓冲区
        view.graphics.removeAll();

        // 尝试彻底移除分析图层
        const analysisLayer = view.map.findLayerById("analysis_result_layer");
        if (analysisLayer) view.map.remove(analysisLayer);

        console.log("🧹 已清空地图所有分析几何体");
      }
    },

    // 6. 显示任务计划（新增）
    showTaskPlan: async (params) => {
      console.log("📋 [任务计划] 显示任务规划:", params);
      // 这个命令主要用于触发 UI 显示，不需要实际的地图操作
      // 任务计划的显示由 ChatAgent.vue 组件处理
      window.dispatchEvent(new CustomEvent("show-task-plan", { detail: params }));
    },

    // 7. 显示执行日志（新增）
    showExecutionLog: async (params) => {
      console.log("📝 [执行日志] 显示执行过程:", params);
      window.dispatchEvent(new CustomEvent("show-execution-log", { detail: params }));
    },

    // 8. 显示分析结果（新增，用于 Agent 模式的结果展示）
    showAnalysisResult: async (params) => {
      console.log("📊 [分析结果] 显示分析结果:", params);
      // 如果有图表数据，触发图表显示
      if (params.far || params.metrics) {
        window.dispatchEvent(new CustomEvent("show-gis-charts", { detail: params }));
      }
      // 如果有地图数据，渲染到地图
      if (params.geoJson || params.geojson) {
        await actions.renderAnalysisResult({ geoJson: params.geoJson || params.geojson });
      }
    },
  };

  // 执行器主循环
  const execute = async (commandArray) => {
    if (!commandArray || !Array.isArray(commandArray)) return undefined;
    let lastResult;
    for (const cmd of commandArray) {
      const actionName = cmd.action;
      if (actions[actionName]) {
        console.log(`[智能体指令] 执行动作: ${actionName}`, cmd.params);
        lastResult = await actions[actionName](cmd.params || cmd);
      }
    }
    return lastResult;
  };

  return { execute };
}

