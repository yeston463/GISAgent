// GeoScene Maps SDK for JavaScript 4.29, loaded from the @geoscene/core npm
// package as ESM modules. The AMD CDN runtime (js.geoscene.cn) is no longer
// used, so no script injection or VITE_GEOSCENE_SDK_URL is needed.
//
// All SDK modules used by the app are statically imported below so Vite can
// bundle them; loadGeoSceneModules() keeps the old AMD-style name interface
// ('geoscene/xxx') so callers are unchanged.
import GeoSceneConfig from '@geoscene/core/config';
import Map from '@geoscene/core/Map';
import Basemap from '@geoscene/core/Basemap';
import Graphic from '@geoscene/core/Graphic';
import SceneView from '@geoscene/core/views/SceneView';
import GraphicsLayer from '@geoscene/core/layers/GraphicsLayer';
import GeoJSONLayer from '@geoscene/core/layers/GeoJSONLayer';
import SceneLayer from '@geoscene/core/layers/SceneLayer';
import WebTileLayer from '@geoscene/core/layers/WebTileLayer';
import ImageryLayer from '@geoscene/core/layers/ImageryLayer';
import ElevationLayer from '@geoscene/core/layers/ElevationLayer';
import Point from '@geoscene/core/geometry/Point';
import Polygon from '@geoscene/core/geometry/Polygon';
// 这两个工具模块是纯命名导出（无 default），需要兼容两种风格。
import * as _webMercatorUtils from '@geoscene/core/geometry/support/webMercatorUtils';
import * as _geometryEngine from '@geoscene/core/geometry/geometryEngine';

const webMercatorUtils = _webMercatorUtils.default ?? _webMercatorUtils;
const geometryEngine = _geometryEngine.default ?? _geometryEngine;
import Sketch from '@geoscene/core/widgets/Sketch';
import SketchViewModel from '@geoscene/core/widgets/Sketch/SketchViewModel';
import Zoom from '@geoscene/core/widgets/Zoom';
import Compass from '@geoscene/core/widgets/Compass';
import NavigationToggle from '@geoscene/core/widgets/NavigationToggle';
import '@geoscene/core/assets/geoscene/themes/light/main.css';

// 让 SDK 内部的 workers / wasm / 样式资产走应用自身的 public 目录
// （由 vite.config.js 在构建/开发时从 node_modules 拷贝）。
// defaultAssetsPath 一并覆盖，确保任何内部路径都不会回落到 CDN 兜底。
GeoSceneConfig.assetsPath = `${import.meta.env.BASE_URL || '/'}assets/`;
GeoSceneConfig.defaultAssetsPath = GeoSceneConfig.assetsPath;

const MODULE_MAP = {
  'geoscene/config': GeoSceneConfig,
  'geoscene/Map': Map,
  'geoscene/Basemap': Basemap,
  'geoscene/Graphic': Graphic,
  'geoscene/views/SceneView': SceneView,
  'geoscene/layers/GraphicsLayer': GraphicsLayer,
  'geoscene/layers/GeoJSONLayer': GeoJSONLayer,
  'geoscene/layers/SceneLayer': SceneLayer,
  'geoscene/layers/WebTileLayer': WebTileLayer,
  'geoscene/layers/ImageryLayer': ImageryLayer,
  'geoscene/layers/ElevationLayer': ElevationLayer,
  'geoscene/geometry/Point': Point,
  'geoscene/geometry/Polygon': Polygon,
  'geoscene/geometry/support/webMercatorUtils': webMercatorUtils,
  'geoscene/geometry/geometryEngine': geometryEngine,
  'geoscene/widgets/Sketch': Sketch,
  'geoscene/widgets/Sketch/SketchViewModel': SketchViewModel,
  'geoscene/widgets/Zoom': Zoom,
  'geoscene/widgets/Compass': Compass,
  'geoscene/widgets/NavigationToggle': NavigationToggle,
};

export function ensureGeoSceneRuntime() {
  return Promise.resolve(true);
}

export async function loadGeoSceneModules(names) {
  return names.map((name) => {
    const mod = MODULE_MAP[name];
    if (!mod) {
      throw new Error(`GeoScene module not mapped in geoSceneAdapter: ${name}`);
    }
    return mod;
  });
}
