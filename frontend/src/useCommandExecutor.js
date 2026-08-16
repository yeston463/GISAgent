import axios from "axios";
import { getGisContextVersion, getGisSessionId, setGisContextVersion } from "./gisSession";
import { loadGeoSceneModules } from "./map/geoSceneAdapter";

let geoSceneModulesPromise;

const getGeoSceneModules = () => {
  if (!geoSceneModulesPromise) {
    geoSceneModulesPromise = loadGeoSceneModules([
      "geoscene/Graphic",
      "geoscene/geometry/Point",
      "geoscene/layers/GeoJSONLayer",
      "geoscene/geometry/Polygon",
      "geoscene/geometry/support/webMercatorUtils",
      "geoscene/geometry/geometryEngine"
    ]).then(([Graphic, Point, GeoJSONLayer, Polygon, webMercatorUtils, geometryEngine]) => ({
      Graphic, Point, GeoJSONLayer, Polygon, webMercatorUtils, geometryEngine
    }));
  }
  return geoSceneModulesPromise;
};

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
window.lastManualAoiGeometry = null;
window.currentAoiSource = null;

// 将 GeoScene 几何转为 WGS84 GeoJSON Polygon（AOI 上传与 Overpass 拉取共用）。
const aoiGeoJsonFromGeometry = (geometry, webMercatorUtils) => {
  const fix = (num) => parseFloat(num.toFixed(6));
  if (geometry?.rings) {
    const geographic = geometry.spatialReference?.isWGS84
      ? geometry
      : webMercatorUtils.webMercatorToGeographic(geometry);
    if (!geographic?.rings) throw new Error("无法将 AOI 转换为 WGS84");
    return {
      type: "Polygon",
      coordinates: geographic.rings.map(ring => ring.map(p => [fix(p[0]), fix(p[1])]))
    };
  }
  // 降级：用 extent 矩形
  const ext = geometry?.extent || geometry;
  const geographicExtent = Math.abs(ext.xmin) > 180
    ? webMercatorUtils.webMercatorToGeographic(ext)
    : ext;
  return {
    type: "Polygon",
    coordinates: [[
      [fix(geographicExtent.xmin), fix(geographicExtent.ymin)],
      [fix(geographicExtent.xmax), fix(geographicExtent.ymin)],
      [fix(geographicExtent.xmax), fix(geographicExtent.ymax)],
      [fix(geographicExtent.xmin), fix(geographicExtent.ymax)],
      [fix(geographicExtent.xmin), fix(geographicExtent.ymin)]
    ]]
  };
};

// 把 Overpass 建筑轮廓渲染为地图图层。天地图底图没有 osm-3d 建筑图层，
// 建筑面必须显式叠加才能被点击感知（spatial-click）与目视核验。
const renderOverpassBuildingsLayer = async (view, features) => {
  const { GeoJSONLayer } = await getGeoSceneModules();
  const existing = view.map.findLayerById("overpass_buildings_layer");
  if (existing) view.map.remove(existing);
  const blobUrl = URL.createObjectURL(new Blob(
    [JSON.stringify({ type: "FeatureCollection", features })],
    { type: "application/json" }
  ));
  const layer = new GeoJSONLayer({
    id: "overpass_buildings_layer",
    title: "OSM 建筑轮廓",
    url: blobUrl,
    outFields: ["*"],
    elevationInfo: { mode: "on-the-ground" },
    renderer: {
      type: "simple",
      symbol: {
        type: "polygon-3d",
        symbolLayers: [{
          type: "fill",
          material: { color: [0, 167, 225, 0.55] },
          outline: { color: [255, 255, 255, 0.9], size: 1.5 }
        }]
      }
    }
  });
  layer.load().then(() => URL.revokeObjectURL(blobUrl)).catch(() => {});
  view.map.add(layer);
  return layer;
};

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

      // GeoScene SceneView 跳转
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
        const { webMercatorUtils } = await getGeoSceneModules();

        // 共享上传：两条建筑获取路径最终都落到同一个上下文同步协议。
        const uploadContext = async (features, aoiGeometry, decisions, inputCount) => {
          console.log("📤 上传数据...");
          const res = await axios.post("/api/gis/upload-context", {
            memoryId: getGisSessionId(),
            contextVersion: getGisContextVersion(),
            buildings: { type: "FeatureCollection", features: features },
            aoi: { type: "Feature", geometry: aoiGeometry },
            geometryTransfer: {
              policy: "preserve_original_footprint",
              inputCount,
              preservedCount: features.length,
              approximatedCount: features.filter(feature => feature.properties?.geometryApproximation).length,
              skippedCount: decisions.filter(item => item.decision.startsWith("skip_")).length,
              decisions: decisions
            }
          });
          setGisContextVersion(res.data?.contextVersion);
          window.lastGisResult = {
            ...res.data,
            far: parseFloat(res.data.far) || 0,
            site_area: parseFloat(res.data.site_area) || 0,
            building_area: parseFloat(res.data.building_area) || 0,
            building_count: parseInt(res.data.building_count) || 0,
            status: "Success", timestamp: Date.now()
          };
          console.log("✅ 结果:", window.lastGisResult);
          window.dispatchEvent(new CustomEvent("gis-data-ready"));
          return true;
        };

        // ---- 主路径：Overpass 2D 建筑轮廓 ----
        // 天地图底图没有 osm-3d 建筑 SceneLayer，真实建筑面统一从
        // Python /analysis/fetch_buildings（Overpass）按 AOI 拉取并裁剪。
        // queryGeometry 在此解析一次并贯穿全流程：异步查询期间不重读全局，
        // 避免后续 flyTo/buffer 指令改变指标与 CityEngine 使用的多边形。
        const queryGeometry = params?.aoiGeometry || window.lastBufferGeometry;
        if (!queryGeometry) {
          console.error("❌ 无缓冲区几何");
          return false;
        }
        const geometryDecisions = [];
        try {
          const aoiGeometry = aoiGeoJsonFromGeometry(queryGeometry, webMercatorUtils);
          const footprintResponse = await axios.post("/analysis/fetch_buildings", {
            aoi: { type: "Feature", geometry: aoiGeometry, properties: {} }
          });
          const fetchedFeatures = footprintResponse.data?.buildings?.features;
          if (!Array.isArray(fetchedFeatures) || fetchedFeatures.length < 3) {
            throw new Error(footprintResponse.data?.message
              || `Overpass 仅返回 ${Array.isArray(fetchedFeatures) ? fetchedFeatures.length : 0} 栋建筑`);
          }
          const features = fetchedFeatures.map((feature, index) => {
            const properties = { ...(feature.properties || {}) };
            const coordinates = feature.geometry?.coordinates || [];
            const polygons = feature.geometry?.type === "MultiPolygon" ? coordinates : [coordinates];
            const originalVertexCount = polygons.reduce((polygonTotal, polygon) =>
              polygonTotal + polygon.reduce((ringTotal, ring) => ringTotal + Math.max(0, ring.length - 1), 0), 0
            );
            return {
              ...feature,
              properties: {
                ...properties,
                id: properties.id ?? properties.osm_id ?? `osm-${index + 1}`,
                geometrySource: "openstreetmap_overpass_aoi_clipped",
                geometryApproximation: false,
                originalVertexCount,
                geometryChangeReason: "Overpass 提供的 OSM 真实建筑面，已按当前 AOI 裁剪。"
              }
            };
          });
          geometryDecisions.push({
            buildingId: "OSM-primary",
            decision: "overpass_primary_fetch",
            reason: `已从 Overpass 获取 AOI 全量 ${features.length} 栋真实建筑面（主数据源）。`
          });
          await renderOverpassBuildingsLayer(view, features);
          return await uploadContext(features, aoiGeometry, geometryDecisions, features.length);
        } catch (error) {
          geometryDecisions.push({
            buildingId: "OSM-primary",
            decision: "overpass_primary_failed",
            reason: `Overpass 主路径失败，回退 SceneLayer Mesh 提取：${error?.response?.data?.message || error?.message || "未知错误"}`
          });
          console.warn("⚠️ Overpass 主路径失败，回退 SceneLayer Mesh 提取:", error);
        }

        // ---- 回退路径：SceneLayer Mesh 提取（仅在底图提供 osm-3d 建筑图层时可用）----
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
          window.lastGisResult.status = "error";
          window.lastGisResult.message = "该区域没有可用的建筑数据：Overpass 未返回建筑面，且当前天地图底图没有三维建筑图层可回退。请在有建筑的区域重试。";
          return false;
        }
        console.log("✅ 找到建筑图层:", osmLayer.title);

        // 等待图层加载
        await osmLayer.load();
        console.log("✅ 图层加载完成");

        const layerView = await view.whenLayerView(osmLayer);
        await layerView.when();
        console.log("✅ 图层视图就绪");

        // SceneLayerView 为渲染性能可能只返回 extent；优先查询图层源数据以取得真实 Polygon rings。
        const querySourceFeatures = async query => {
          try {
            const sourceResult = await osmLayer.queryFeatures(query);
            if (sourceResult?.features?.length) return sourceResult;
          } catch (error) {
            console.warn("⚠️ 建筑图层源查询失败，回退到已渲染图层:", error);
          }
          return layerView.queryFeatures(query);
        };

        // 先用视图范围测试（最多重试 3 次，等待远距离瓦片加载）
        console.log("🔍 使用视图范围测试...");
        let testResult;
        for (let attempt = 1; attempt <= 3; attempt++) {
          const testQuery = osmLayer.createQuery();
          testQuery.geometry = view.extent;
          testQuery.spatialRelationship = "intersects";
          testQuery.returnGeometry = false;
          testQuery.outFields = ["objectid"];
          testResult = await querySourceFeatures(testQuery);
          if (testResult.features.length > 0) break;
          console.warn(`⏳ 第${attempt}次视图测试无建筑，等待瓦片加载...`);
          await new Promise(r => setTimeout(r, 2000));
        }
        console.log("✅ 视图范围测试结果:", testResult.features.length, "栋建筑");

        if (testResult.features.length === 0) {
          console.warn("⚠️ 视图范围内无建筑，建筑图层可能未加载");
          return false;
        }

        // 使用缓冲区查询（queryGeometry 已在 Overpass 主路径解析为 const 快照）
        console.log("📐 缓冲区范围:", queryGeometry.extent.xmin, queryGeometry.extent.ymin, queryGeometry.extent.xmax, queryGeometry.extent.ymax);

        console.log("🔍 执行缓冲区查询（圆形几何）...");
        let bufferResult;
        for (let attempt = 1; attempt <= 3; attempt++) {
          const q = osmLayer.createQuery();
          q.geometry = queryGeometry;
          q.spatialRelationship = "intersects";
          q.returnGeometry = true;
          q.outFields = ["*"];
          bufferResult = await querySourceFeatures(q);
          if (bufferResult.features.length > 0) break;
          console.warn(`⏳ 第${attempt}次缓冲区查询无建筑，等待瓦片加载...`);
          await new Promise(r => setTimeout(r, 2000));
        }
        console.log("✅ 缓冲区查询结果:", bufferResult.features.length, "栋建筑");

        if (bufferResult.features.length === 0) {
          console.warn("⚠️ 缓冲区内无建筑");
          return false;
        }

        // 处理建筑数据。CityEngine 必须接收真实面轮廓，不能用 extent 冒充建筑 footprint。
        // fix 与 geometryDecisions 已在 Overpass 主路径声明（geometryDecisions 贯穿两条路径）。
        const meshToFootprint = async (mesh) => {
          if (mesh?.type !== "mesh") return null;

          // SceneLayer 查询返回的 Mesh 初始只含引用；必须显式加载才能读取顶点与三角面。
          await mesh.load();
          const positions = mesh.vertexAttributes?.position;
          if (!positions || positions.length < 12 || positions.length % 3 !== 0) return null;

          // 仅使用已地理配准的顶点，避免把局部坐标误当作经纬度或投影坐标。
          const vertexSpace = mesh.vertexSpace;
          if (vertexSpace?.type && vertexSpace.type !== "georeferenced") return null;
          const origin = vertexSpace?.origin;
          const vertices = [];
          for (let offset = 0; offset < positions.length; offset += 3) {
            vertices.push({
              x: positions[offset] + (origin?.x || 0),
              y: positions[offset + 1] + (origin?.y || 0),
              z: positions[offset + 2] + (origin?.z || 0)
            });
          }

          const minimumZ = Math.min(...vertices.map(vertex => vertex.z));
          const maximumZ = Math.max(...vertices.map(vertex => vertex.z));
          const rawHeightMeters = maximumZ - minimumZ;
          const meshInfo = {
            rings: null,
            heightMeters: Number.isFinite(rawHeightMeters) && rawHeightMeters >= 2 && rawHeightMeters <= 500
              ? Number(rawHeightMeters.toFixed(1))
              : null
          };
          const groundTolerance = Math.max(0.05, (maximumZ - minimumZ) * 0.001);
          const isGroundVertex = index => vertices[index]?.z <= minimumZ + groundTolerance;
          const triangles = [];
          const visitFaces = faces => {
            for (let offset = 0; offset + 2 < faces.length; offset += 3) {
              const triangle = [faces[offset], faces[offset + 1], faces[offset + 2]].map(Number);
              if (triangle.every(index => Number.isInteger(index) && vertices[index])) triangles.push(triangle);
            }
          };
          const components = mesh.components || [];
          const componentsWithFaces = components.filter(component => component.faces?.length);
          if (componentsWithFaces.length) {
            componentsWithFaces.forEach(component => visitFaces(component.faces));
          } else {
            visitFaces(Array.from({ length: vertices.length }, (_, index) => index));
          }

          const edgeKey = (first, second) => first < second ? `${first}:${second}` : `${second}:${first}`;
          const ringArea = ringIndexes => {
            let area = 0;
            for (let index = 0; index < ringIndexes.length; index += 1) {
              const current = vertices[ringIndexes[index]];
              const next = vertices[ringIndexes[(index + 1) % ringIndexes.length]];
              area += current.x * next.y - next.x * current.y;
            }
            return Math.abs(area / 2);
          };
          const ringFromEdges = edgePairs => {
            const adjacency = new Map();
            const edgeSet = new Set();
            const addPair = (first, second) => {
              if (first === second || !vertices[first] || !vertices[second]) return;
              if (vertices[first].x === vertices[second].x && vertices[first].y === vertices[second].y) return;
              const key = edgeKey(first, second);
              if (edgeSet.has(key)) return;
              edgeSet.add(key);
              if (!adjacency.has(first)) adjacency.set(first, new Set());
              if (!adjacency.has(second)) adjacency.set(second, new Set());
              adjacency.get(first).add(second);
              adjacency.get(second).add(first);
            };
            edgePairs.forEach(([first, second]) => addPair(first, second));
            if (edgeSet.size < 3) return null;

            const visited = new Set();
            const loops = [];
            for (const key of edgeSet) {
              if (visited.has(key)) continue;
              const [start, second] = key.split(":").map(Number);
              const path = [start, second];
              visited.add(key);
              let previous = start;
              let current = second;
              let closed = false;
              for (let guard = 0; guard <= edgeSet.size + 2; guard += 1) {
                const neighbors = [...(adjacency.get(current) || [])].filter(candidate => candidate !== previous);
                if (!neighbors.length) break;
                const next = neighbors.find(candidate => candidate === start && path.length >= 3)
                  || neighbors.find(candidate => !visited.has(edgeKey(current, candidate)))
                  || neighbors[0];
                if (next === undefined) break;
                visited.add(edgeKey(current, next));
                if (next === start) {
                  closed = path.length >= 3;
                  break;
                }
                if (path.includes(next)) break;
                path.push(next);
                previous = current;
                current = next;
              }
              if (closed) loops.push(path);
            }
            if (!loops.length) return null;
            loops.sort((left, right) => ringArea(right) - ringArea(left));
            return loops[0];
          };
          const footprintRingFromTriangles = predicate => {
            const edges = new Map();
            const addEdge = (first, second) => {
              if (first === second) return;
              const key = edgeKey(first, second);
              edges.set(key, (edges.get(key) || 0) + 1);
            };
            for (const triangle of triangles) {
              if (!predicate(triangle)) continue;
              addEdge(triangle[0], triangle[1]);
              addEdge(triangle[1], triangle[2]);
              addEdge(triangle[2], triangle[0]);
            }
            // Triangulation interiors appear twice; the mesh surface boundary appears once.
            return ringFromEdges([...edges.entries()]
              .filter(([, count]) => count === 1)
              .map(([key]) => key.split(":").map(Number)));
          };
          const xyTolerance = mesh.spatialReference?.isWGS84 ? 1e-7 : 0.02;
          const xyKey = vertex => `${Math.round(vertex.x / xyTolerance)}:${Math.round(vertex.y / xyTolerance)}`;
          const minimumZByXy = new Map();
          vertices.forEach(vertex => {
            const key = xyKey(vertex);
            minimumZByXy.set(key, Math.min(minimumZByXy.get(key) ?? Infinity, vertex.z));
          });
          const columnBottomTolerance = Math.max(0.15, Math.min(1.0, rawHeightMeters * 0.01));
          const isColumnBottomVertex = index => {
            const vertex = vertices[index];
            if (!vertex) return false;
            return vertex.z <= (minimumZByXy.get(xyKey(vertex)) ?? vertex.z) + columnBottomTolerance;
          };
          const footprintRingFromWallBottomEdges = () => {
            const bottomEdges = [];
            for (const triangle of triangles) {
              const bottomIndexes = triangle.filter(isColumnBottomVertex);
              if (bottomIndexes.length === 2) bottomEdges.push(bottomIndexes);
            }
            return ringFromEdges(bottomEdges);
          };
          const convexHullIndexes = indexes => {
            const unique = new Map();
            indexes.forEach(index => {
              const vertex = vertices[index];
              if (vertex) unique.set(xyKey(vertex), index);
            });
            const points = [...unique.values()]
              .map(index => ({ index, vertex: vertices[index] }))
              .sort((left, right) => left.vertex.x === right.vertex.x
                ? left.vertex.y - right.vertex.y
                : left.vertex.x - right.vertex.x);
            if (points.length < 3) return null;
            const cross = (origin, a, b) =>
              (a.vertex.x - origin.vertex.x) * (b.vertex.y - origin.vertex.y)
              - (a.vertex.y - origin.vertex.y) * (b.vertex.x - origin.vertex.x);
            const lower = [];
            for (const point of points) {
              while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], point) <= 0) lower.pop();
              lower.push(point);
            }
            const upper = [];
            for (let index = points.length - 1; index >= 0; index -= 1) {
              const point = points[index];
              while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], point) <= 0) upper.pop();
              upper.push(point);
            }
            return lower.slice(0, -1).concat(upper.slice(0, -1)).map(point => point.index);
          };

          // Exact lowest faces work for level ground. On terrain, the base
          // vertices are not identical in Z, so fall back to lower-facing
          // mesh triangles while preserving only a verified closed boundary.
          let ringIndexes = footprintRingFromTriangles(triangle => triangle.every(isGroundVertex));
          let recoveryMethod = ringIndexes ? "mesh_exact_ground_faces" : null;
          if (!ringIndexes) {
            ringIndexes = footprintRingFromWallBottomEdges();
            if (ringIndexes) recoveryMethod = "mesh_wall_bottom_edges";
          }
          if (!ringIndexes && rawHeightMeters > 0) {
            const lowerBandZ = minimumZ + rawHeightMeters * 0.45;
            ringIndexes = footprintRingFromTriangles(triangle => {
              const [a, b, c] = triangle.map(index => vertices[index]);
              const ux = b.x - a.x;
              const uy = b.y - a.y;
              const uz = b.z - a.z;
              const vx = c.x - a.x;
              const vy = c.y - a.y;
              const vz = c.z - a.z;
              const normalZ = ux * vy - uy * vx;
              const normalLength = Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz, normalZ);
              const horizontalness = normalLength > 0 ? Math.abs(normalZ) / normalLength : 0;
              return (a.z + b.z + c.z) / 3 <= lowerBandZ && horizontalness >= 0.35;
            });
            if (ringIndexes) recoveryMethod = "mesh_lower_surface_boundary";
          }
          // SceneLayer meshes frequently omit a closed ground face. Their
          // roof triangles remain closed and vertically project onto the same
          // footprint. Recover that boundary before using a convex hull, which
          // fills concavities and creates oversized diagonal model faces.
          if (!ringIndexes && rawHeightMeters > 0) {
            const upperBandZ = maximumZ - rawHeightMeters * 0.45;
            ringIndexes = footprintRingFromTriangles(triangle => {
              const [a, b, c] = triangle.map(index => vertices[index]);
              const ux = b.x - a.x;
              const uy = b.y - a.y;
              const uz = b.z - a.z;
              const vx = c.x - a.x;
              const vy = c.y - a.y;
              const vz = c.z - a.z;
              const normalZ = ux * vy - uy * vx;
              const normalLength = Math.hypot(uy * vz - uz * vx, normalZ, ux * vz - uz * vx);
              const horizontalness = normalLength > 0 ? Math.abs(normalZ) / normalLength : 0;
              return (a.z + b.z + c.z) / 3 >= upperBandZ && horizontalness >= 0.35;
            });
            if (ringIndexes) recoveryMethod = "mesh_roof_surface_boundary";
          }
          // A single roof triangle is not a building footprint. Some
          // SceneLayer meshes expose only one roof face for a building; using
          // that face directly creates the triangular wedges seen in the
          // exported CityEngine models. Reject it when the measured bottom
          // vertices provide a richer projected hull, then let the verified
          // hull fallback recover a displayable footprint.
          if (ringIndexes && recoveryMethod === "mesh_roof_surface_boundary") {
            const roofBottomIndexes = vertices
              .map((_vertex, index) => index)
              .filter(isColumnBottomVertex);
            const roofHullIndexes = convexHullIndexes(
              roofBottomIndexes.length >= 3 ? roofBottomIndexes : vertices.map((_vertex, index) => index)
            );
            if (roofHullIndexes && roofHullIndexes.length >= 4) {
              const roofArea = ringArea(ringIndexes);
              const hullArea = ringArea(roofHullIndexes);
              if (ringIndexes.length <= 3 || (hullArea > 0 && roofArea < hullArea * 0.55)) {
                ringIndexes = null;
                recoveryMethod = null;
              }
            }
          }
          let hullApproximation = false;
          if (!ringIndexes) {
            const bottomIndexes = vertices
              .map((_vertex, index) => index)
              .filter(isColumnBottomVertex);
            ringIndexes = convexHullIndexes(bottomIndexes.length >= 3 ? bottomIndexes : vertices.map((_vertex, index) => index));
            if (ringIndexes) {
              recoveryMethod = "mesh_projected_convex_hull";
              hullApproximation = true;
            }
          }
          if (!ringIndexes) return meshInfo;

          const spatialReference = mesh.spatialReference;
          const toGeographic = vertex => spatialReference?.isWGS84
            ? [vertex.x, vertex.y]
            : webMercatorUtils.xyToLngLat(vertex.x, vertex.y);
          const ring = ringIndexes.map(index => toGeographic(vertices[index]));
          ring.push([...ring[0]]);
          // The SceneLayer mesh is the same geometry shown in the map.
          // Retain its Z range even if the footprint later needs an extent fallback.
          meshInfo.rings = [ring];
          meshInfo.footprintRecovery = recoveryMethod;
          meshInfo.geometryApproximation = hullApproximation;
          return meshInfo;
        };

        let features = (await Promise.all(bufferResult.features.map(async (f, index) => {
          const g = f.geometry;
          const attrs = f.attributes || {};
          const idVal = attrs.objectid || attrs.OBJECTID || attrs.fid || index;
          const parsePositiveNumber = value => {
            const parsed = Number.parseFloat(value);
            return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
          };
          const attributeHeight = [attrs.height, attrs.HEIGHT, attrs.H_AVG, attrs.render_height]
            .map(parsePositiveNumber)
            .find(value => value !== null) ?? null;
          const attributeFloors = [attrs.floors, attrs.levels, attrs["building:levels"]]
            .map(parsePositiveNumber)
            .find(value => value !== null) ?? null;

          const extentToGeographicRing = (extent) => {
            if (!extent) return null;
            try {
              const geographicExtent = extent.spatialReference?.isWGS84
                ? extent
                : webMercatorUtils.webMercatorToGeographic(extent);
              const { xmin, ymin, xmax, ymax } = geographicExtent || {};
              if (![xmin, ymin, xmax, ymax].every(Number.isFinite) || xmin === xmax || ymin === ymax) return null;
              return [[
                [Number(xmin), Number(ymin)],
                [Number(xmax), Number(ymin)],
                [Number(xmax), Number(ymax)],
                [Number(xmin), Number(ymax)],
                [Number(xmin), Number(ymin)]
              ]];
            } catch (error) {
              console.warn("⚠️ 建筑 extent 无法转换为 WGS84:", error);
              return null;
            }
          };

          if (!g) {
            geometryDecisions.push({
              buildingId: idVal,
              decision: "skip_missing_footprint",
              reason: "建筑要素没有可用几何或 extent，无法生成矩形近似。"
            });
            return null;
          }

          let sourceRings = g.rings;
          let ringsAreGeographic = g.type === "mesh";
          let geometrySource = "geoscene_polygon_rings";
          let geometryApproximation = false;
          let geometryChangeReason = "保留原始建筑轮廓；CityEngine 仅沿该轮廓生成体量。";
          let meshInfo = null;
          if (g.type === "mesh") {
            try {
              meshInfo = await meshToFootprint(g);
              if ((!Array.isArray(sourceRings) || sourceRings.length === 0) && meshInfo?.rings) {
                sourceRings = meshInfo.rings;
                if (meshInfo.footprintRecovery === "mesh_exact_ground_faces") {
                  geometrySource = "geoscene_scene_mesh_ground_boundary";
                  geometryChangeReason = "从 SceneLayer 已加载 Mesh 的真实底面边界还原建筑轮廓；未使用 extent。";
                } else if (meshInfo.footprintRecovery === "mesh_wall_bottom_edges") {
                  geometrySource = "geoscene_scene_mesh_wall_bottom_edges";
                  geometryChangeReason = "从 SceneLayer Mesh 墙面底边还原建筑轮廓；适用于无底面但地图可见的三维建筑，未使用 extent。";
                } else if (meshInfo.footprintRecovery === "mesh_projected_convex_hull") {
                  geometrySource = "geoscene_scene_mesh_projected_hull";
                  geometryApproximation = true;
                  geometryChangeReason = "SceneLayer Mesh 无法恢复闭合底边，已用 Mesh 投影凸包近似；精度高于 extent 外包矩形。";
                  geometryDecisions.push({
                    buildingId: idVal,
                    decision: "approximate_mesh_projected_hull",
                    reason: geometryChangeReason
                  });
                } else if (meshInfo.footprintRecovery === "mesh_roof_surface_boundary") {
                  geometrySource = "geoscene_scene_mesh_roof_projection";
                  geometryChangeReason = "SceneLayer Mesh 缺少闭合底面，已由真实屋顶三角网垂直投影恢复建筑轮廓；未使用凸包或 extent。";
                } else {
                  geometrySource = "geoscene_scene_mesh_lower_surface_boundary";
                  geometryChangeReason = "从 SceneLayer Mesh 的低位底面三角网恢复建筑边界，适用于存在地形高差的建筑；未使用 extent。";
                }
              }
            } catch (error) {
              console.warn("⚠️ SceneLayer Mesh 轮廓加载失败:", error);
            }
          }

          if (!Array.isArray(sourceRings) || sourceRings.length === 0) {
            sourceRings = extentToGeographicRing(g.extent);
            if (sourceRings) {
              ringsAreGeographic = true;
              geometrySource = "geoscene_geometry_extent_rectangle";
              geometryApproximation = true;
              geometryChangeReason = "未取得真实建筑轮廓，按 GeoScene 几何 extent 生成外包矩形近似体。";
              geometryDecisions.push({
                buildingId: idVal,
                decision: "approximate_extent_rectangle",
                reason: geometryChangeReason
              });
            } else {
              geometryDecisions.push({
                buildingId: idVal,
                decision: "skip_unsupported_footprint",
                reason: "未取得 Polygon rings、Mesh 底面边界或有效 extent，无法生成矩形近似。"
              });
              return null;
            }
          }

          const geographicRings = ringsAreGeographic
            ? sourceRings
            : (g.spatialReference?.isWGS84 ? sourceRings : webMercatorUtils.webMercatorToGeographic(g).rings);
          if (!Array.isArray(geographicRings)) {
            sourceRings = extentToGeographicRing(g.extent);
            if (sourceRings) {
              ringsAreGeographic = true;
              geometrySource = "geoscene_geometry_extent_rectangle";
              geometryApproximation = true;
              geometryChangeReason = "真实建筑轮廓无法转换至 WGS84，按 GeoScene 几何 extent 生成外包矩形近似体。";
              geometryDecisions.push({ buildingId: idVal, decision: "approximate_extent_rectangle", reason: geometryChangeReason });
            } else {
              geometryDecisions.push({
                buildingId: idVal,
                decision: "skip_projection_failure",
                reason: "建筑轮廓无法转换到 WGS84，且无有效 extent 可生成矩形近似。"
              });
              return null;
            }
          }

          const resolvedRings = ringsAreGeographic ? sourceRings : geographicRings;
          const coords = resolvedRings.map(ring =>
            ring.map(point => [Number(point[0]), Number(point[1])])
          );
          const ringsAreValid = coords.every(ring =>
            ring.length >= 4
            && ring.every(point => point.length >= 2 && point.every(Number.isFinite))
            && ring[0][0] === ring[ring.length - 1][0]
            && ring[0][1] === ring[ring.length - 1][1]
          );
          if (!ringsAreValid) {
            const rectangleRings = extentToGeographicRing(g.extent);
            if (rectangleRings) {
              geometrySource = "geoscene_geometry_extent_rectangle";
              geometryApproximation = true;
              geometryChangeReason = "建筑轮廓无效，按 GeoScene 几何 extent 生成外包矩形近似体。";
              geometryDecisions.push({ buildingId: idVal, decision: "approximate_extent_rectangle", reason: geometryChangeReason });
              coords.splice(0, coords.length, ...rectangleRings);
            } else {
              geometryDecisions.push({
                buildingId: idVal,
                decision: "skip_invalid_footprint",
                reason: "建筑 rings 无效，且无有效 extent 可生成矩形近似。"
              });
              return null;
            }
          }

          const originalVertexCount = coords.reduce((total, ring) => total + Math.max(0, ring.length - 1), 0);
          let height = null;
          let heightSource = "missing";
          let heightEstimated = true;
          if (meshInfo?.heightMeters) {
            height = meshInfo.heightMeters;
            heightSource = "scene_mesh_z_range";
            heightEstimated = false;
          } else if (attributeHeight) {
            height = Number(attributeHeight.toFixed(1));
            heightSource = "scene_attribute_height";
            heightEstimated = false;
          } else if (attributeFloors) {
            height = Number((attributeFloors * 3.2).toFixed(1));
            heightSource = "levels_inferred";
          }
          const floors = attributeFloors
            ? Math.max(1, Math.round(attributeFloors))
            : (height ? Math.max(1, Math.round(height / 3.2)) : null);
          if (!height) {
            height = 3;
            heightSource = "default_display_height";
            heightEstimated = true;
          }
          const displayFloors = floors || 1;
          const floorSource = attributeFloors
            ? "scene_attribute_levels"
            : (heightSource === "scene_mesh_z_range" ? "mesh_height_inferred" : "default_display_floor");

          return {
            type: "Feature",
            geometry: { type: "Polygon", coordinates: coords },
            properties: {
              id: idVal,
              height,
              floors: displayFloors,
              heightSource,
              floorSource,
              heightEstimated,
              geometrySource,
              geometryApproximation,
              originalVertexCount,
              geometryChangeReason
            }
          };
        }))).filter(Boolean);

        console.log("✅ 处理完成:", features.length, "栋建筑");
        if (geometryDecisions.length > 0) {
          console.warn(`⚠️ ${geometryDecisions.length} 项建筑几何处理决策:`, geometryDecisions);
        }

        // 构建 AOI：使用实际缓冲区圆形几何，而非外接矩形（与 Overpass 主路径共用转换）
        const aoiGeometry = aoiGeoJsonFromGeometry(queryGeometry, webMercatorUtils);

        // SceneLayer 查询受瓦片加载和当前视图影响。少量返回值即使几何完整，
        // 也可能只是已加载的一小部分。Overpass 主路径已在函数开头尝试并失败，
        // 此处不再重复外网调用，仅核验数量并如实记录近似轮廓。
        if (features.some(feature => feature.properties?.geometryApproximation)) {
          geometryDecisions.push({
            buildingId: "OSM-primary",
            decision: "keep_approximate_scene_footprints",
            reason: "Overpass 不可用且 SceneLayer 含近似轮廓，保留近似几何以维持分析可用。"
          });
        }
        if (features.length < 3) {
          throw new Error(`建筑上下文不完整：SceneLayer 仅返回 ${features.length} 栋，且 Overpass 主路径未取得 AOI 全量真实建筑面。已停止同步以避免生成退化的少量建筑成果。`);
        }

        return await uploadContext(features, aoiGeometry, geometryDecisions, bufferResult.features.length);

      } catch (err) {
        console.error("❌ 错误:", err);
        window.lastGisResult.status = "error";
        window.lastGisResult.message = err?.message || "建筑轮廓同步失败";
        return false;
      }
    },

    openAnalysisDashboard: async (params) => {
      console.log("📊 [仪表盘控制] 正在进行真值校验...");

      // 1. 获取真值（来自后端计算）
      const realFar = window.lastGisResult.far;

      const hasComputedMetrics = window.lastGisResult.status === "Success"
        && Number.isFinite(Number(realFar))
        && Number.isFinite(Number(window.lastGisResult.site_area))
        && Number(window.lastGisResult.site_area) > 0
        && Number.isFinite(Number(window.lastGisResult.building_count))
        && Number(window.lastGisResult.building_count) > 0;
      if (!hasComputedMetrics) {
        // Never render a pending upload/import as an apparently completed report.
        console.warn("⚠️ 指标尚未计算完成，已阻止打开空分析面板。");
        window.dispatchEvent(new Event("clear-gis-charts"));
        return false;
      }

      // 2. 只展示服务端已完成计算的指标，忽略模型传入的数值。
      const finalFar = Number(realFar);

      const finalData = {
        ...window.lastGisResult,
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
        const { GeoJSONLayer } = await getGeoSceneModules();

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
      const { GeoJSONLayer } = await getGeoSceneModules();
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
        existingGreen: { color: [74, 130, 104, 0.42], outline: [45, 90, 70, 1], width: 1 },
        shadow: { color: [71, 85, 105, 0.45], outline: [30, 41, 59, 0.85], width: 1 }
      };
      const style = styleMap[params.style] || styleMap.optimized;
      const floodRenderer = {
        type: "unique-value", field: "riskLevel", defaultSymbol: {
          type: "simple-fill", color: [34, 197, 94, 0.10], outline: { color: [22, 163, 74, 0.45], width: 0.5 }
        }, uniqueValueInfos: [
          { value: "high", label: "高风险", symbol: { type: "simple-fill", color: [220, 38, 38, 0.65], outline: { color: [127, 29, 29, 0.95], width: 1.2 } } },
          { value: "medium", label: "中风险", symbol: { type: "simple-fill", color: [245, 158, 11, 0.42], outline: { color: [180, 83, 9, 0.8], width: 0.8 } } },
          { value: "low", label: "低风险", symbol: { type: "simple-fill", color: [34, 197, 94, 0.12], outline: { color: [22, 163, 74, 0.5], width: 0.5 } } }
        ]
      };
      const floodExposureRenderer = {
        type: "unique-value", field: "floodExposure", defaultSymbol: {
          type: "simple-fill", color: [251, 146, 60, 0.40], outline: { color: [194, 65, 12, 1], width: 2 }
        }, uniqueValueInfos: [
          { value: "high", label: "高暴露", symbol: { type: "simple-fill", color: [185, 28, 28, 0.58], outline: { color: [127, 29, 29, 1], width: 2.5 } } },
          { value: "medium", label: "中暴露", symbol: { type: "simple-fill", color: [251, 146, 60, 0.46], outline: { color: [194, 65, 12, 1], width: 2 } } }
        ]
      };
      const siteSelectionUsesPointSymbols = (data.features || []).some(feature => feature?.geometry?.type === "Point");
      const siteSelectionSymbol = (color, outline) => siteSelectionUsesPointSymbols
        ? { type: "simple-marker", style: "circle", color, size: 15, outline: { color: outline, width: 1.8 } }
        : { type: "simple-fill", color, outline: { color: outline, width: 1.2 } };
      const siteSelectionRenderer = {
        type: "class-breaks", field: "siteScore", defaultSymbol: {
          ...siteSelectionSymbol([148, 163, 184, 0.35], [71, 85, 105, 0.9])
        }, classBreakInfos: [
          { minValue: 0, maxValue: 39.999, label: "适宜性低", symbol: siteSelectionSymbol([239, 68, 68, 0.45], [153, 27, 27, 0.9]) },
          { minValue: 40, maxValue: 69.999, label: "适宜性中", symbol: siteSelectionSymbol([245, 158, 11, 0.45], [180, 83, 9, 0.9]) },
          { minValue: 70, maxValue: 100, label: "适宜性高", symbol: siteSelectionSymbol([34, 197, 94, 0.5], [21, 128, 61, 0.95]) }
        ]
      };
      const nearestFacilityRenderer = {
        type: "class-breaks", field: "nearestFacilityDistanceM", defaultSymbol: {
          ...siteSelectionSymbol([148, 163, 184, 0.35], [71, 85, 105, 0.9])
        }, classBreakInfos: [
          { minValue: 0, maxValue: 250, label: "250 米以内", symbol: siteSelectionSymbol([34, 197, 94, 0.55], [21, 128, 61, 0.95]) },
          { minValue: 250, maxValue: 1000, label: "250 米至 1 公里", symbol: siteSelectionSymbol([245, 158, 11, 0.5], [180, 83, 9, 0.9]) },
          { minValue: 1000, maxValue: 100000000, label: "1 公里以上", symbol: siteSelectionSymbol([239, 68, 68, 0.48], [153, 27, 27, 0.9]) }
        ]
      };
      const blob = new Blob([JSON.stringify(data)], { type: "application/geo+json" });
      const url = URL.createObjectURL(blob);
      const layer = new GeoJSONLayer({
        id: layerId,
        title: params.title || "Agent 分析图层",
        url,
        elevationInfo: { mode: params.style === "warning" || params.style === "optimized" ? "on-the-ground" : "on-the-ground" },
        renderer: params.style === "floodRisk" ? floodRenderer : params.style === "floodExposure" ? floodExposureRenderer : params.style === "siteSelection" ? siteSelectionRenderer : params.style === "nearestFacility" ? nearestFacilityRenderer : {
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
            { fieldName: "problemReasons", label: "问题原因" },
            { fieldName: "riskLevel", label: "洪水风险" },
            { fieldName: "floodExposure", label: "建筑暴露等级" },
            { fieldName: "estimatedDepthM", label: "相对积水深度 (m)" },
            { fieldName: "riskScore", label: "风险分数" }
            ,{ fieldName: "siteRank", label: "选址排名" }
            ,{ fieldName: "siteScore", label: "适宜性评分" }
            ,{ fieldName: "screeningStatus", label: "筛查状态" }
            ,{ fieldName: "nearestFacilityM", label: "最近设施距离 (m)" }
            ,{ fieldName: "nearestFacilityDistanceM", label: "最近设施距离 (m)" }
            ,{ fieldName: "nearestFacilityBearingDeg", label: "最近设施方位 (°)" }
            ,{ fieldName: "nearestConstraintM", label: "最近约束距离 (m)" }
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
    setAoi: async (params) => {
      const feature = params?.aoi;
      const geometry = feature?.geometry || feature;
      if (!geometry || !["Polygon", "MultiPolygon"].includes(geometry.type)) {
        throw new Error("行政区 AOI 必须是 GeoJSON Polygon 或 MultiPolygon");
      }
      const view = await getReadyView();
      const { Polygon, Graphic } = await getGeoSceneModules();
      const rings = geometry.type === "Polygon" ? geometry.coordinates : geometry.coordinates.flat();
      const aoi = new Polygon({ rings, spatialReference: { wkid: 4326 } });
      window.lastBufferGeometry = aoi;
      window.lastManualAoiGeometry = null;
      window.currentAoiSource = "administrative_boundary";
      view.graphics.removeAll();
      view.graphics.add(new Graphic({
        geometry: aoi,
        attributes: { source: "administrative_boundary", title: params?.title || "行政区范围" },
        symbol: { type: "simple-fill", color: [0, 144, 255, 0.12], outline: { color: [0, 144, 255, 0.95], width: 2 } }
      }));
      await view.goTo(aoi.extent.expand(1.12), { duration: 1200 }).catch(error => {
        if (error?.name !== "AbortError") throw error;
      });
      return { aoiGeometry: aoi };
    },
    showAdvancedAnalysis: async (params) => {
      window.dispatchEvent(new CustomEvent("show-advanced-analysis", {
        detail: params || {}
      }));
    },
    // 展示规划三视图（现状/问题诊断/优化方案）：接收 demo 案例或规划任务的
    // 现状建筑、问题建筑、优化建筑与优化绿地，创建独立图层供切换。
    showPlanningScenario: async (params) => {
      const view = await getReadyView();
      const existing = params?.existingBuildings || params?.buildings;
      const problems = params?.problemBuildings;
      const optimized = params?.optimizedBuildings;
      const green = params?.proposedGreenSpace;

      const removeLayer = id => {
        const layer = view.map.findLayerById(id);
        if (layer) view.map.remove(layer);
      };
      const addLayer = (id, title, data, color, outlineColor) => {
        if (!data?.features?.length) return;
        removeLayer(id);
        const blob = new Blob([JSON.stringify(data)], { type: "application/geo+json" });
        const layer = new GeoJSONLayer({
          id,
          title,
          url: URL.createObjectURL(blob),
          outFields: ["*"],
          elevationInfo: { mode: "on-the-ground" },
          renderer: {
            type: "simple",
            symbol: {
              type: "polygon-3d",
              symbolLayers: [{
                type: "fill",
                material: { color },
                outline: { color: outlineColor, size: 1.2 }
              }]
            }
          }
        });
        layer.load().then(() => URL.revokeObjectURL(layer.url)).catch(() => {});
        view.map.add(layer);
      };

      if (existing) addLayer("existing-scenario", "现状建筑", existing, [148, 163, 184, 0.55], [71, 85, 105, 0.9]);
      if (problems) addLayer("problem-buildings", "问题建筑", problems, [220, 38, 38, 0.75], [127, 29, 29, 1]);
      if (optimized) addLayer("optimized-scenario", "优化建筑", optimized, [37, 99, 235, 0.65], [30, 64, 175, 0.95]);
      if (green) addLayer("optimized-green", "优化绿地", green, [34, 197, 94, 0.55], [21, 128, 61, 0.9]);

      // 默认展示现状；若有优化数据则提示用户可切换
      const scenario = params?.scenario || "existing";
      await actions.switchPlanningScenario({ scenario });
      return true;
    },
    // 5. 清除红线
    clearAOI: async () => {
      const view = await getReadyView();
      const aoiLayer = view.map.findLayerById("manual_aoi_layer");
      if (aoiLayer) aoiLayer.removeAll();
      view.graphics.removeAll();
      window.lastBufferGeometry = null;
      window.lastManualAoiGeometry = null;
      window.currentAoiSource = null;
      window.lastGisResult = { far:0, site_area:0, building_area:0, count:0 }; // 清空缓存
    },
    addBuffer: async (params) => {
      console.log("🎯 [原子指令] 启动前端几何引擎绘制缓冲区...", params);
      try {
        // A hand-drawn redline is an explicit user constraint.  Agent output
        // may contain an addBuffer command while analysing it, but that must
        // never replace the drawing with a second (orange/cyan) AOI.
        if (window.currentAoiSource === "manual_draw" && params?.replaceManualAoi !== true) {
          console.warn("ℹ️ 已保留手绘 AOI，忽略自动缓冲区指令。");
          return { skipped: true, reason: "manual_aoi_locked" };
        }
        const view = await getReadyView();
        const { geometryEngine, Graphic, Point, Polygon } = await getGeoSceneModules();

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
          bufferGeom = new Polygon({
            rings: [coords],
            spatialReference: { wkid: 4326 }
          });
          console.log("🔄 使用手动生成的圆形缓冲区");
        }

        // 【新增】保存缓冲区几何到全局变量，供 getScreenBuildings 使用
        window.lastBufferGeometry = bufferGeom;
        window.currentAoiSource = "buffer";
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
      if (Object.prototype.hasOwnProperty.call(params || {}, "far") || params?.metrics) {
        window.dispatchEvent(new CustomEvent("show-gis-charts", { detail: params }));
      }
      // 如果有地图数据，渲染到地图
      if (params.geoJson || params.geojson) {
        await actions.renderAnalysisResult({ geoJson: params.geoJson || params.geojson });
      }
    },

    // Extensible result renderer. New analysis algorithms select an output
    // kind; frontend code stays stable until a genuinely new visual primitive
    // is introduced.
    renderAnalysisOutput: async (output) => {
      const kind = output?.kind;
      const data = output?.data;
      if (kind === "vector") {
        return actions.addGeoJsonLayer({
          data,
          layerId: output.layerId || `analysis-${Date.now()}`,
          title: output.title || "空间分析结果",
          style: output.style || "optimized",
          visible: output.visible !== false
        });
      }
      if (kind === "metric") {
        window.dispatchEvent(new CustomEvent("show-gis-charts", { detail: data || {} }));
        return true;
      }
      if (kind === "chart" || kind === "table" || kind === "raster") {
        window.dispatchEvent(new CustomEvent("show-analysis-output", {
          detail: { kind, data, meta: output.meta || {}, title: output.title }
        }));
        return true;
      }
      throw new Error(`Unsupported analysis output kind: ${kind || "empty"}`);
    },
  };

  // Typed command execution boundary. `execute` keeps the legacy return value;
  // callers that need reliability can use `executeWithReport`.
  const executeWithReport = async (commandArray) => {
    const report = {
      protocolVersion: "1.0",
      ok: true,
      executed: [],
      failed: [],
      skipped: [],
      lastResult: undefined
    };
    if (!Array.isArray(commandArray)) {
      report.ok = false;
      report.failed.push({ reason: "commands_not_array" });
      return report;
    }

    for (const cmd of commandArray) {
      const commandId = cmd?.commandId || `cmd-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const actionName = cmd?.action;
      if (!actionName || typeof actionName !== "string") {
        report.ok = false;
        report.failed.push({ commandId, reason: "invalid_action" });
        continue;
      }
      if (!actions[actionName]) {
        report.ok = false;
        const item = { commandId, action: actionName, reason: "unknown_action" };
        report.skipped.push(item);
        window.dispatchEvent(new CustomEvent("agent-command-ack", {
          detail: { ...item, status: "skipped" }
        }));
        continue;
      }
      try {
        console.log(`[智能体指令] 执行动作: ${actionName}`, cmd.params);
        const value = await actions[actionName](cmd.params || cmd);
        report.lastResult = value;
        const item = { commandId, action: actionName, status: value === false ? "failed" : "success" };
        if (value === false) {
          report.ok = false;
          report.failed.push(item);
        } else {
          report.executed.push(item);
        }
        window.dispatchEvent(new CustomEvent("agent-command-ack", {
          detail: { ...item, result: value }
        }));
      } catch (error) {
        report.ok = false;
        const item = {
          commandId,
          action: actionName,
          status: "failed",
          reason: error?.message || "command_execution_failed"
        };
        report.failed.push(item);
        window.dispatchEvent(new CustomEvent("agent-command-ack", { detail: item }));
      }
    }
    window.dispatchEvent(new CustomEvent("agent-command-batch-ack", { detail: report }));
    return report;
  };

  const execute = async (commandArray) => {
    const report = await executeWithReport(commandArray);
    return report.lastResult;
  };

  return { execute, executeWithReport };
}

