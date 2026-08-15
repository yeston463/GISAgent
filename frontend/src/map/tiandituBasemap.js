import { loadGeoSceneModules } from './geoSceneAdapter';

let basemapCache = null;

const TIANDITU_SUBDOMAINS = ['0', '1', '2', '3', '4', '5', '6', '7'];

const TIANDITU_LAYERS = {
  img: { base: 'img_w', label: 'cia_w', title: '天地图影像' },
  vec: { base: 'vec_w', label: 'cva_w', title: '天地图矢量' },
};

function token() {
  return import.meta.env.VITE_TIANDITU_TOKEN || '';
}

function tileUrl(service) {
  return `https://t{subDomain}.tianditu.gov.cn/DataServer?T=${service}&x={col}&y={row}&l={level}&tk=${token()}`;
}

export async function createTiandituBasemap({ type = 'img' } = {}) {
  if (basemapCache) return basemapCache;

  const tk = token();
  if (!tk) {
    console.warn('天地图 Token 未配置（VITE_TIANDITU_TOKEN），底图瓦片将无法加载。');
  }

  const [WebTileLayer, Basemap] = await loadGeoSceneModules([
    'geoscene/layers/WebTileLayer',
    'geoscene/Basemap',
  ]);

  const layerDef = TIANDITU_LAYERS[type] || TIANDITU_LAYERS.img;

  const baseLayer = new WebTileLayer({
    urlTemplate: tileUrl(layerDef.base),
    subDomains: TIANDITU_SUBDOMAINS,
    title: layerDef.title,
    copyright: '天地图',
  });

  const labelLayer = new WebTileLayer({
    urlTemplate: tileUrl(layerDef.label),
    subDomains: TIANDITU_SUBDOMAINS,
    title: `${layerDef.title}注记`,
    copyright: '天地图',
  });

  basemapCache = new Basemap({
    baseLayers: [baseLayer, labelLayer],
    title: layerDef.title,
    thumbnailUrl: undefined,
  });

  return basemapCache;
}

export function resetTiandituBasemapCache() {
  basemapCache = null;
}
