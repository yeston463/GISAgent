<template>
  <Transition name="slide-fade">
    <section v-if="visible" class="analysis-panel">
      <header class="panel-header">
        <div>
          <span class="eyebrow">{{ panelEyebrow }}</span>
          <h3>{{ panelTitle }}</h3>
        </div>
        <button class="close-btn" @click="visible = false">×</button>
      </header>

      <div v-if="!isScenarioMode && !isAdvanced && !isComparison" class="tab-bar">
        <button :class="{ active: activeTab === 'standard' }" @click="activeTab = 'standard'">现状分析</button>
        <button :class="{ active: activeTab === 'scenario' }" @click="activeTab = 'scenario'">方案对比</button>
      </div>

      <template v-if="isScenarioMode">
        <div class="status-strip">
          <span class="status-dot scenario-dot"></span><span>多方案比选评估</span>
          <strong>{{ scenarioResults.length }} 个方案已评估</strong>
        </div>
        <div class="scenario-columns">
          <article
            v-for="result in scenarioResults"
            :key="result.scenario.id"
            :class="['scenario-card', { recommended: result.score === bestScore }]"
            @click="selectScenario(result.scenario.id)"
          >
            <div class="scenario-header">
              <span class="scenario-name">{{ result.scenario.name }}</span>
              <span v-if="result.score === bestScore" class="recommend-badge">推荐</span>
            </div>
            <p class="scenario-desc">{{ result.scenario.description }}</p>
            <div class="scenario-metrics">
              <div class="s-metric"><span>FAR</span><strong>{{ format(result.metrics.far, 3) }}</strong></div>
              <div class="s-metric"><span>密度</span><strong>{{ format(result.metrics.building_density, 2, '%') }}</strong></div>
              <div class="s-metric"><span>高度</span><strong>{{ format(result.metrics.buildingHeight, 1, ' m') }}</strong></div>
              <div class="s-metric"><span>违规</span><strong :class="{ 'has-violations': result.violations.length > 0 }">{{ result.violations.length }}</strong></div>
            </div>
            <div class="scenario-score">
              <span>综合评分</span>
              <strong>{{ (result.score * 100).toFixed(1) }}</strong>
            </div>
            <div v-if="result.violations.length" class="violations-list">
              <small v-for="(v, vi) in result.violations" :key="vi" class="violation-item">{{ v.metric }}={{ v.value }} > {{ v.max }}</small>
            </div>
          </article>
        </div>
        <p class="disclaimer">评分权重：合规 50% · 经济 30% · 可行性 20%。评分越高方案越优。点击方案卡片可切换地图中的方案图层。</p>
      </template>

      <template v-else-if="isComparison">
        <div class="status-strip">
          <span class="status-dot"></span><span>本地确定性比赛案例</span>
          <strong>{{ improvedCount }}/4 项指标改善</strong>
        </div>
        <nav class="scenario-switch" aria-label="规划方案切换">
          <button :class="{ active: activeScenario === 'existing' }" @click="switchScenario('existing')">查看现状</button>
          <button :class="{ active: activeScenario === 'diagnosis' }" @click="switchScenario('diagnosis')">问题诊断</button>
          <button :class="{ active: activeScenario === 'optimized' }" @click="switchScenario('optimized')">优化方案</button>
        </nav>        <div class="comparison-grid">
          <article v-for="item in comparisonRows" :key="item.key" class="metric-card">
            <div class="metric-title"><span>{{ item.label }}</span><span :class="['trend', item.improved ? 'good' : 'neutral']">{{ item.improved ? '已改善' : '保持' }}</span></div>
            <div class="values"><div><small>现状</small><strong>{{ item.before }}</strong></div><span class="arrow">→</span><div><small>优化后</small><strong class="after">{{ item.after }}</strong></div></div>
            <div class="delta">{{ item.delta }}</div>
          </article>
        </div>
        <footer class="panel-footer"><span>红色：问题建筑</span><span>蓝色：优化建筑</span><span>绿色：优化绿地</span></footer>
        <p class="disclaimer">本项目用于城市尺度快速筛查与辅助决策，非高精度日照模拟、非严格物理仿真、非法定规划审批依据。分析依赖数据精度与估算，结果标注来源、精度与适用边界。</p>
      </template>

      <template v-else-if="isAdvanced">
        <div class="status-strip">
          <span class="status-dot"></span><span>方案筛查模型计算完成</span>
          <strong>{{ advancedBuildingStatus }}</strong>
        </div>
        <div v-if="advancedData.analysisType === 'skyline'" class="skyline-wrap">
          <div class="advanced-summary">
            <span>最高 {{ format(advancedData.maxHeight, 1, ' m') }}</span>
            <span>平均 {{ format(advancedData.meanHeight, 1, ' m') }}</span>
          </div>
          <div class="skyline-chart" aria-label="天际线方向高度剖面">
            <div v-for="point in advancedProfile" :key="point.angle" class="skyline-column">
              <div class="skyline-bar" :style="{ height: `${skylineHeight(point.height)}%` }" :title="`${point.angle}° / ${point.height} m`"></div>
              <small>{{ Math.round(point.angle) }}°</small>
            </div>
          </div>
        </div>
        <div v-else-if="advancedData.analysisType === 'flood'" class="flood-grid">
          <article class="flood-card danger"><span>高风险格网</span><strong>{{ advancedData.highRiskCellCount || 0 }}</strong></article>
          <article class="flood-card"><span>中风险格网</span><strong>{{ advancedData.mediumRiskCellCount || 0 }}</strong></article>
          <article class="flood-card"><span>受影响建筑</span><strong>{{ floodExposureStatus }}</strong></article>
          <article class="flood-card"><span>最大相对水深</span><strong>{{ format(advancedData.maxEstimatedDepthM, 3, ' m') }}</strong></article>
          <div class="advanced-summary full"><span>降雨 {{ format(advancedData.rainfallMm, 0, ' mm') }}</span><span>{{ advancedData.returnPeriodYears || 20 }} 年重现期</span></div>
          <div v-if="advancedData.demQuality" class="dem-quality">DEM 采样 {{ advancedData.demQuality.sample_count }} 个 · 高程 {{ format(advancedData.demQuality.minimum_elevation_m, 2, ' m') }}–{{ format(advancedData.demQuality.maximum_elevation_m, 2, ' m') }} · 高差 {{ format(advancedData.demQuality.elevation_span_m, 3, ' m') }}<span v-if="advancedData.demQuality.warning"> · {{ advancedData.demQuality.warning }}</span></div>
        </div>
        <div v-else class="sunlight-grid">
          <article v-for="sample in advancedData.samples || []" :key="sample.hour" class="sunlight-sample">
            <strong>{{ String(sample.hour).padStart(2, '0') }}:00</strong>
            <span>太阳高度 {{ format(sample.sun_altitude, 1, '°') }}</span>
            <small>平均阴影 {{ format(sample.average_shadow_length_m, 1, ' m') }}</small>
          </article>
          <div class="advanced-summary full">
            <span>有效采样时段 {{ format(advancedData.sunlightWindowPercent, 0, '%') }}</span>
            <span>最长估算阴影 {{ format(advancedData.maxShadowLengthM, 1, ' m') }}</span>
          </div>
        </div>
        <p class="disclaimer">{{ advancedData.limitations || '本结果用于城市尺度快速筛查与辅助决策，非高精度日照模拟、非严格物理仿真、非法定规划审批依据。' }}</p>
      </template>

      <template v-else>
        <div class="status-strip">
          <span class="status-dot"></span><span>GIS 引擎计算完成</span>
          <strong>{{ confidenceText }}</strong>
        </div>
        <div class="metrics-grid">
          <article v-for="item in (isStandardMetrics ? standardRows : dynamicRows)" :key="item.key" class="standard-card">
            <span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small>
          </article>
        </div>
        <div class="quality-row">
          <span>楼层数据可信度：{{ confidenceText }}</span>
          <span>高度数据可信度：{{ heightConfidenceText }}</span>
          <span v-if="standardData.gis_backend">GIS 后端：{{ gisBackendLabel(standardData.gis_backend) }}</span>
        </div>
      </template>
      <div v-if="provenance.runId" class="provenance-row">
        <span>运行 {{ provenance.runId.slice(-8) }}</span>
        <span>{{ provenance.tool || provenance.capabilityId }}</span>
        <span v-if="provenance.contextVersion !== undefined">上下文 v{{ provenance.contextVersion }}</span>
        <span v-if="provenance.data_source">{{ provenance.data_source }}</span>
        <span v-if="provenance.gis_backend">GIS 后端：{{ gisBackendLabel(provenance.gis_backend) }}</span>
      </div>
    </section>
  </Transition>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
const props = defineProps({ data: { type: Object, default: () => ({}) } });
const visible = ref(false);
const mode = ref('standard');
const activeTab = ref('standard');
const comparison = ref({});
const activeScenario = ref('existing');
const standardData = ref({});
const advancedData = ref({});
const provenance = ref({});
const scenarioResults = ref([]);
const number = value => Number.isFinite(Number(value)) ? Number(value) : null;
const format = (value, digits = 2, unit = '') => number(value) === null ? '未计算' : `${Number(value).toLocaleString('zh-CN',{minimumFractionDigits:digits,maximumFractionDigits:digits})}${unit}`;
const gisBackendLabel = backend => ({
  open_source_geopandas: '开源 GeoPandas/Shapely',
  standard_library_metrics: '标准库（离线兜底）',
  standard_library_bbox: '标准库（bbox 裁剪）',
  geoscene_arcpy: 'ArcPy（专业后端・优先）',
  geoscene_server: 'GeoScene 服务端',
}[backend] || backend || '未知');
const isComparison = computed(() => mode.value === 'comparison');
const isAdvanced = computed(() => mode.value === 'advanced');
const isScenarioMode = computed(() => mode.value === 'scenario' || (mode.value === 'standard' && activeTab.value === 'scenario' && scenarioResults.value.length > 0));
const bestScore = computed(() => {
  if (!scenarioResults.value.length) return 0;
  return Math.max(...scenarioResults.value.map(r => r.score));
});
const panelEyebrow = computed(() => isComparison.value ? '城市更新 · 辅助决策' : isAdvanced.value ? 'GIS 快速评估 · 方案筛查' : 'GIS 空间分析 · 快速评估');
const panelTitle = computed(() => isComparison.value ? '现状与优化方案对比' : isAdvanced.value ? (advancedData.value.title || '空间筛查评估') : '建筑指标快速评估');
const confidenceText = computed(() => ({ high:'高', medium:'中', low:'低' }[standardData.value.floor_confidence] || '待评估'));
const heightConfidenceText = computed(() => ({ high:'高', medium:'中', low:'低' }[standardData.value.height_stats?.confidence] || '待评估'));
const heightNote = stats => {
  if (stats?.max == null) return '未取得可用高度数据';
  if (Number(stats.estimated_ratio || 0) > 0) return '缺失高度按用途与面积估算，已标注来源';
  if (Number(stats.levels_inferred_ratio || 0) > 0) return '由实测楼层按标准层高推导';
  return '建筑属性高度统计';
};
const standardRows = computed(() => {
  const data = standardData.value || {};
  return [
    { key:'far', label:'容积率 FAR', value:format(data.far,3), note:'总建筑面积 ÷ 用地面积' },
    { key:'density', label:'建筑密度', value:format(data.building_density,2,'%'), note:'建筑基底面积 ÷ 用地面积' },
    { key:'site', label:'用地面积', value:format(data.site_area,0,' m²'), note:'分析范围投影面积' },
    { key:'building', label:'建筑总面积', value:format(data.building_area,0,' m²'), note:'建筑基底 × 楼层汇总' },
    { key:'count', label:'建筑数量', value:format(data.building_count,0,' 栋'), note:'范围内有效建筑' },
    { key:'height', label:'最高建筑', value:format(data.height_stats?.max,1,' m'), note:heightNote(data.height_stats) },
    { key:'floors', label:'平均楼层', value:format(data.floor_stats?.avg,1,' 层'), note:'含缺失楼层估算' },
    { key:'footprint', label:'建筑基底面积', value:format(data.footprint_area_sqm,0,' m²'), note:'建筑轮廓投影面积' }
  ];
});
const comparisonRows = computed(() => {
  const configs=[['far','容积率 FAR','',true],['buildingDensity','建筑密度','%',true],['buildingHeight','最高建筑','m',true],['greenRate','绿地率','%',false]];
  return configs.map(([key,label,unit,lowerBetter])=>{ const metric=comparison.value[key]||{}; const before=number(metric.before); const after=number(metric.after); const valid=before!==null&&after!==null; const diff=valid?after-before:0; const improved=valid&&(lowerBetter?diff<0:diff>0); return {key,label,improved,before:format(before,2,unit),after:format(after,2,unit),delta:valid?`变化 ${diff>=0?'+':''}${diff.toFixed(2)}${unit}`:'等待方案结果'}; });
});
const improvedCount = computed(() => comparisonRows.value.filter(item=>item.improved).length);
const advancedProfile = computed(() => Array.isArray(advancedData.value.profile) ? advancedData.value.profile : []);
const hasComputedMetrics = payload => {
  const data = payload?.metrics || payload || {};
  const status = String(data.status || '').toLowerCase();
  if (status === 'error' || status === 'fail' || status === 'nodata') return false;
  const standardValid = Number.isFinite(Number(data.far))
    && Number(data.site_area ?? data.site_area_sqm) > 0
    && Number(data.building_count) > 0;
  if (standardValid) return true;
  // 知识图谱动态能力（如 avg_height_analysis）的指标字段不固定，
  // 只要至少有一个非控制字段可展示即视为有效结果。
  return Object.entries(data).some(([key, value]) =>
    !CONTROLLED_FIELDS.has(key)
    && (typeof value === 'number' || typeof value === 'boolean'
      || (typeof value === 'string' && value.length <= 40 && !/^[\[{]/.test(value)))
  );
};
const isStandardMetrics = computed(() => Number.isFinite(Number(standardData.value.far)));
const CONTROLLED_FIELDS = new Set([
  'status','stage','analysis_type','analysisType','commands','trace','provenance',
  'quality','message','missing_data','missingData','method','limitations','center',
  'source','data_source','dataSource','context_version','contextVersion','plan',
  'aoi','buildings','samples','shadows','skyline_profile','profile'
]);
const FIELD_LABELS = {
  buildingCount:'建筑数量', building_count:'建筑数量',
  avgHeightM:'平均建筑高度', avg_height_m:'平均建筑高度', mean_height:'平均建筑高度', meanHeight:'平均建筑高度',
  maxHeightM:'最高建筑', max_height:'最高建筑', maxHeight:'最高建筑',
  totalAreaSqm:'建筑总面积', total_area_sqm:'建筑总面积', building_area:'建筑总面积',
  siteAreaSqm:'用地面积', site_area_sqm:'用地面积',
  buildingDensity:'建筑密度', building_density:'建筑密度',
  footprintAreaSqm:'建筑基底面积', footprint_area_sqm:'建筑基底面积'
};
const humanizeKey = key => key
  .replace(/([a-z\d])([A-Z])/g, '$1 $2')
  .replace(/[_-]+/g, ' ')
  .trim()
  .replace(/\b\w/g, char => char.toUpperCase());
const formatDynamicValue = (value, key) => {
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value !== 'number' || !Number.isFinite(value)) return String(value);
  if (/count/i.test(key)) return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 0 });
  const digits = /height/i.test(key) ? 1 : 2;
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
};
const dynamicNote = (key, value) => {
  if (typeof value === 'number' && Number.isFinite(value)) {
    if (/height/i.test(key)) return '单位：米';
    if (/area/i.test(key)) return '单位：平方米';
    if (/density/i.test(key)) return '单位：%';
    if (/count/i.test(key)) return '范围内有效建筑';
  }
  return key;
};
const dynamicRows = computed(() => {
  const data = standardData.value || {};
  const rows = [];
  for (const [key, value] of Object.entries(data)) {
    if (CONTROLLED_FIELDS.has(key)) continue;
    if (typeof value === 'number' && Number.isFinite(value)) {
      rows.push({ key, label: FIELD_LABELS[key] || humanizeKey(key), value: formatDynamicValue(value, key), note: dynamicNote(key, value) });
    } else if (typeof value === 'boolean') {
      rows.push({ key, label: FIELD_LABELS[key] || humanizeKey(key), value: formatDynamicValue(value, key), note: key });
    } else if (typeof value === 'string' && value.length <= 40 && !/^[\[{]/.test(value) && value.trim()) {
      rows.push({ key, label: FIELD_LABELS[key] || humanizeKey(key), value, note: key });
    }
  }
  return rows.length ? rows : [{ key: 'empty', label: '指标', value: '暂无', note: '分析未返回可展示字段' }];
});
const buildingExposureAvailable = computed(() => advancedData.value.buildingExposureAvailable !== false);
const advancedBuildingStatus = computed(() =>
  advancedData.value.analysisType === 'flood' && !buildingExposureAvailable.value
    ? '建筑暴露未评估'
    : `${advancedData.value.buildingCount || 0} 栋建筑`
);
const floodExposureStatus = computed(() =>
  !buildingExposureAvailable.value ? '未评估' : (advancedData.value.affectedBuildingCount ?? 0)
);
const skylineMax = computed(() => Math.max(1, ...advancedProfile.value.map(item => Number(item.height) || 0)));
const skylineHeight = value => Math.max(2, Math.min(100, (Number(value) || 0) / skylineMax.value * 100));
const handleComparison = event => { comparison.value=event.detail||{}; mode.value='comparison'; visible.value=true; };
const handleAdvanced = event => { advancedData.value=event.detail||{}; mode.value='advanced'; visible.value=true; };
const switchScenario = scenario => {
  activeScenario.value = scenario;
  window.dispatchEvent(new CustomEvent('request-planning-scenario-switch', { detail: { scenario } }));
};
const handleScenarioChanged = event => { activeScenario.value = event.detail?.scenario || 'existing'; };
const handleStandard = event => {
  const data = event.detail?.metrics || event.detail || {};
  if (!hasComputedMetrics(data)) {
    standardData.value = {};
    if (mode.value === 'standard') visible.value = false;
    return;
  }
  standardData.value = data;
  mode.value = 'standard';
  visible.value = true;
};
const clearStandard = () => {
  if (mode.value === 'standard') visible.value = false;
  standardData.value = {};
};
const handleProvenance = event => { provenance.value = event.detail || {}; };
const handleScenarioResults = event => {
  const results = event.detail?.results || [];
  scenarioResults.value = results;
  if (results.length > 0) {
    mode.value = 'scenario';
    visible.value = true;
  }
};
const selectScenario = scenarioId => {
  window.dispatchEvent(new CustomEvent('request-scenario-map-switch', { detail: { scenarioId } }));
};
watch(()=>props.data,value=>{ if(value&&Object.keys(value).length) handleStandard({detail:value}); },{deep:true});
onMounted(()=>{ window.addEventListener('show-planning-comparison',handleComparison); window.addEventListener('show-gis-charts',handleStandard); window.addEventListener('show-advanced-analysis',handleAdvanced); window.addEventListener('planning-scenario-changed',handleScenarioChanged); window.addEventListener('show-analysis-provenance',handleProvenance); window.addEventListener('show-scenario-results',handleScenarioResults); });
onUnmounted(()=>{ window.removeEventListener('show-planning-comparison',handleComparison); window.removeEventListener('show-gis-charts',handleStandard); window.removeEventListener('clear-gis-charts',clearStandard); window.removeEventListener('show-advanced-analysis',handleAdvanced); window.removeEventListener('planning-scenario-changed',handleScenarioChanged); window.removeEventListener('show-analysis-provenance',handleProvenance); window.removeEventListener('show-scenario-results',handleScenarioResults); });
</script>

<style scoped>
/* GeoScene 组件同款浅色风格 */
.analysis-panel{position:absolute;left:28px;bottom:28px;z-index:1300;width:min(600px,calc(100vw - 56px));color:#4c4c4c;background:#ffffff;border:1px solid #d4d4d4;border-radius:4px;box-shadow:0 2px 8px rgba(0,0,0,.3);overflow:hidden;font-family:"Avenir Next","Segoe UI",Arial,sans-serif;font-size:12px}
.panel-header{display:flex;justify-content:space-between;align-items:flex-start;padding:14px 18px 12px;border-bottom:1px solid #e0e0e0}.eyebrow{display:block;margin-bottom:4px;color:#6e6e6e;font-size:10px;letter-spacing:.08em;text-transform:uppercase}.panel-header h3{margin:0;font-size:17px;color:#2b2b2b}.close-btn{border:0;background:transparent;color:#6e6e6e;font-size:22px;cursor:pointer}.close-btn:hover{color:#0079c1}.status-strip{display:flex;align-items:center;gap:8px;padding:9px 18px;color:#4c4c4c;background:#f8f8f8;border-bottom:1px solid #e0e0e0}.status-strip strong{margin-left:auto;color:#2e7d32}.status-dot{width:7px;height:7px;border-radius:50%;background:#2e7d32}.metrics-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding:14px}.standard-card,.metric-card{padding:12px;border-radius:3px;background:#f8f8f8;border:1px solid #e0e0e0}.standard-card{display:flex;flex-direction:column;min-height:82px}.standard-card span{color:#6e6e6e;font-size:11px}.standard-card strong{margin:8px 0 5px;color:#2b2b2b;font:600 18px Consolas,monospace}.standard-card small{color:#6e6e6e;font-size:10px;line-height:1.35}.quality-row{display:flex;justify-content:space-between;gap:12px;padding:10px 18px 12px;color:#6e6e6e;font-size:10px;border-top:1px solid #e0e0e0}.scenario-switch{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;padding:12px 14px 0}.scenario-switch button{padding:7px 10px;border:1px solid #a8a8a8;border-radius:3px;color:#4c4c4c;background:#ffffff;cursor:pointer;font-size:11px}.scenario-switch button.active,.tab-bar button.active{color:#ffffff;background:#0079c1;border-color:#0079c1;font-weight:600}.tab-bar{display:flex;gap:6px;padding:10px 14px 0}.tab-bar button{flex:1;padding:7px 12px;border:1px solid #a8a8a8;border-radius:3px;color:#4c4c4c;background:#ffffff;cursor:pointer;font-size:11px}.tab-bar button:hover,.scenario-switch button:hover{border-color:#0079c1;color:#0079c1}.scenario-columns{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;padding:14px}.scenario-card{padding:12px;border-radius:3px;background:#f8f8f8;border:1px solid #e0e0e0;cursor:pointer;transition:border-color .2s,box-shadow .2s}.scenario-card:hover{border-color:#0079c1}.scenario-card.recommended{border-color:#0079c1;box-shadow:0 0 0 1px #0079c1}.scenario-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px}.scenario-name{color:#2b2b2b;font-size:13px;font-weight:600}.recommend-badge{padding:2px 8px;border-radius:3px;background:#0079c1;color:#ffffff;font-size:10px;font-weight:600}.scenario-desc{color:#6e6e6e;font-size:10px;margin:0 0 10px;line-height:1.4}.scenario-metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:6px;margin-bottom:10px}.s-metric{display:flex;flex-direction:column;gap:2px;padding:6px;background:#ffffff;border:1px solid #e0e0e0;border-radius:3px}.s-metric span{color:#6e6e6e;font-size:9px}.s-metric strong{color:#2b2b2b;font:600 13px Consolas,monospace}.s-metric strong.has-violations{color:#c62828}.scenario-score{display:flex;justify-content:space-between;align-items:center;padding:6px 8px;background:#e6f2fa;border:1px solid #b8d8ee;border-radius:3px;margin-bottom:8px}.scenario-score span{color:#6e6e6e;font-size:10px}.scenario-score strong{color:#0079c1;font:700 16px Consolas,monospace}.violations-list{display:flex;flex-wrap:wrap;gap:4px}.violation-item{padding:2px 6px;border-radius:3px;background:#fdeeee;border:1px solid #eec5c8;color:#c62828;font-size:9px}.scenario-dot{background:#0079c1}.comparison-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:14px}.metric-title{display:flex;justify-content:space-between;color:#2b2b2b;font-size:12px}.trend{padding:2px 7px;border-radius:3px;font-size:10px}.trend.good{color:#2e7d32;background:#e8f5e9;border:1px solid #c8e6c9}.trend.neutral{color:#6e6e6e;background:#f5f5f5;border:1px solid #e0e0e0}.values{display:grid;grid-template-columns:1fr auto 1fr;align-items:end;gap:8px;margin-top:12px}.values div{display:flex;flex-direction:column}.values small{color:#6e6e6e;font-size:10px}.values strong{font:600 17px Consolas,monospace;color:#2b2b2b}.values strong.after{color:#0079c1}.arrow{color:#a8a8a8}.delta{margin-top:8px;color:#6e6e6e;font-size:10px}.panel-footer{display:flex;gap:14px;padding:10px 18px;border-top:1px solid #e0e0e0;color:#6e6e6e;font-size:10px}.disclaimer{margin:0;padding:0 18px 14px;color:#6e6e6e;font-size:10px}.skyline-wrap{padding:14px 18px}.advanced-summary{display:flex;justify-content:space-between;gap:12px;margin-bottom:12px;color:#2b2b2b;font-size:12px}.advanced-summary.full{grid-column:1/-1;margin:2px 2px 0}.skyline-chart{display:grid;grid-template-columns:repeat(24,minmax(4px,1fr));align-items:end;gap:3px;height:180px;padding-top:12px;border-bottom:1px solid #e0e0e0}.skyline-column{display:flex;flex-direction:column;justify-content:flex-end;align-items:center;height:100%;min-width:0}.skyline-bar{width:100%;min-height:3px;background:#0079c1;border:1px solid #005e95}.skyline-column small{height:17px;margin-top:4px;color:#6e6e6e;font-size:7px;writing-mode:vertical-rl}.sunlight-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px;padding:14px}.sunlight-sample{display:flex;flex-direction:column;gap:5px;padding:10px 8px;background:#f8f8f8;border:1px solid #e0e0e0;border-radius:3px}.sunlight-sample strong{color:#0079c1;font:600 14px Consolas,monospace}.sunlight-sample span{color:#4c4c4c;font-size:10px}.sunlight-sample small{color:#6e6e6e;font-size:9px}.provenance-row{display:flex;flex-wrap:wrap;gap:8px;padding:9px 18px;color:#6e6e6e;border-top:1px solid #e0e0e0;font:10px Consolas,monospace}.flood-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;padding:14px}.flood-card{display:flex;flex-direction:column;gap:8px;padding:12px;background:#f8f8f8;border:1px solid #e0e0e0;border-radius:3px}.flood-card span{color:#6e6e6e;font-size:10px}.flood-card strong{color:#0079c1;font:600 18px Consolas,monospace}.flood-card.danger strong{color:#c62828}.dem-quality{grid-column:1/-1;color:#4c4c4c;font-size:10px;line-height:1.45;padding:8px 10px;border:1px solid #e0e0e0;border-radius:3px;background:#f8f8f8}.slide-fade-enter-active,.slide-fade-leave-active{transition:.25s ease}.slide-fade-enter-from,.slide-fade-leave-to{opacity:0;transform:translateY(16px)}@media(max-width:800px){.metrics-grid{grid-template-columns:repeat(2,1fr)}.sunlight-grid{grid-template-columns:repeat(3,1fr)}}@media(max-width:600px){.analysis-panel{left:12px;bottom:12px;width:calc(100vw - 24px)}.comparison-grid,.metrics-grid{grid-template-columns:1fr}.quality-row{flex-direction:column}.sunlight-grid{grid-template-columns:repeat(2,1fr)}.skyline-chart{height:145px}}@media(max-width:600px){.flood-grid{grid-template-columns:repeat(2,1fr)}}
</style>
