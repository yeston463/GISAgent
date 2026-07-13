<template>
  <Transition name="slide-fade">
    <section v-if="visible" class="analysis-panel">
      <header class="panel-header">
        <div>
          <span class="eyebrow">{{ isComparison ? '城市更新 · 规划决策' : 'GIS 空间分析 · 实测结果' }}</span>
          <h3>{{ isComparison ? '现状与优化方案对比' : '建筑指标分析结果' }}</h3>
        </div>
        <button class="close-btn" @click="visible = false">×</button>
      </header>

      <template v-if="isComparison">
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
        <p class="disclaimer">演示规则用于比赛流程验证，不作为法定规划审批依据。</p>
      </template>

      <template v-else>
        <div class="status-strip">
          <span class="status-dot"></span><span>GIS 引擎计算完成</span>
          <strong>{{ confidenceText }}</strong>
        </div>
        <div class="metrics-grid">
          <article v-for="item in standardRows" :key="item.key" class="standard-card">
            <span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small>
          </article>
        </div>
        <div class="quality-row">
          <span>楼层数据可信度：{{ confidenceText }}</span>
          <span>绿地率：普通建筑数据未包含绿地图层</span>
        </div>
      </template>
    </section>
  </Transition>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
const props = defineProps({ data: { type: Object, default: () => ({}) } });
const visible = ref(false);
const mode = ref('standard');
const comparison = ref({});
const activeScenario = ref('existing');
const standardData = ref({});
const number = value => Number.isFinite(Number(value)) ? Number(value) : null;
const format = (value, digits = 2, unit = '') => number(value) === null ? '未计算' : `${Number(value).toLocaleString('zh-CN',{minimumFractionDigits:digits,maximumFractionDigits:digits})}${unit}`;
const isComparison = computed(() => mode.value === 'comparison');
const confidenceText = computed(() => ({ high:'高', medium:'中', low:'低' }[standardData.value.floor_confidence] || '待评估'));
const standardRows = computed(() => {
  const data = standardData.value || {};
  return [
    { key:'far', label:'容积率 FAR', value:format(data.far,3), note:'总建筑面积 ÷ 用地面积' },
    { key:'density', label:'建筑密度', value:format(data.building_density,2,'%'), note:'建筑基底面积 ÷ 用地面积' },
    { key:'site', label:'用地面积', value:format(data.site_area,0,' m²'), note:'分析范围投影面积' },
    { key:'building', label:'建筑总面积', value:format(data.building_area,0,' m²'), note:'建筑基底 × 楼层汇总' },
    { key:'count', label:'建筑数量', value:format(data.building_count,0,' 栋'), note:'范围内有效建筑' },
    { key:'height', label:'最高建筑', value:format(data.height_stats?.max,1,' m'), note:data.height_stats?.max == null ? '源数据未提供高度' : '建筑属性高度统计' },
    { key:'floors', label:'平均楼层', value:format(data.floor_stats?.avg,1,' 层'), note:'含缺失楼层估算' },
    { key:'footprint', label:'建筑基底面积', value:format(data.footprint_area_sqm,0,' m²'), note:'建筑轮廓投影面积' }
  ];
});
const comparisonRows = computed(() => {
  const configs=[['far','容积率 FAR','',true],['buildingDensity','建筑密度','%',true],['buildingHeight','最高建筑','m',true],['greenRate','绿地率','%',false]];
  return configs.map(([key,label,unit,lowerBetter])=>{ const metric=comparison.value[key]||{}; const before=number(metric.before); const after=number(metric.after); const valid=before!==null&&after!==null; const diff=valid?after-before:0; const improved=valid&&(lowerBetter?diff<0:diff>0); return {key,label,improved,before:format(before,2,unit),after:format(after,2,unit),delta:valid?`变化 ${diff>=0?'+':''}${diff.toFixed(2)}${unit}`:'等待方案结果'}; });
});
const improvedCount = computed(() => comparisonRows.value.filter(item=>item.improved).length);
const handleComparison = event => { comparison.value=event.detail||{}; mode.value='comparison'; visible.value=true; };
const switchScenario = scenario => {
  activeScenario.value = scenario;
  window.dispatchEvent(new CustomEvent('request-planning-scenario-switch', { detail: { scenario } }));
};
const handleScenarioChanged = event => { activeScenario.value = event.detail?.scenario || 'existing'; };
const handleStandard = event => { standardData.value=event.detail?.metrics||event.detail||{}; mode.value='standard'; visible.value=true; };
watch(()=>props.data,value=>{ if(value&&Object.keys(value).length) handleStandard({detail:value}); },{deep:true});
onMounted(()=>{ window.addEventListener('show-planning-comparison',handleComparison); window.addEventListener('show-gis-charts',handleStandard); window.addEventListener('planning-scenario-changed',handleScenarioChanged); });
onUnmounted(()=>{ window.removeEventListener('show-planning-comparison',handleComparison); window.removeEventListener('show-gis-charts',handleStandard); window.removeEventListener('planning-scenario-changed',handleScenarioChanged); });
</script>

<style scoped>
.analysis-panel{position:absolute;left:28px;bottom:28px;z-index:1300;width:min(600px,calc(100vw - 56px));color:#e8f3fb;background:linear-gradient(145deg,rgba(7,24,39,.97),rgba(9,39,56,.95));border:1px solid rgba(94,201,219,.28);border-radius:16px;box-shadow:0 24px 70px rgba(2,12,22,.38);backdrop-filter:blur(18px);overflow:hidden}.panel-header{display:flex;justify-content:space-between;align-items:flex-start;padding:18px 20px 14px;border-bottom:1px solid rgba(148,207,217,.14)}.eyebrow{display:block;margin-bottom:4px;color:#69d3ca;font-size:11px;letter-spacing:.14em}h3{margin:0;font-size:18px}.close-btn{border:0;background:transparent;color:#8eb2c4;font-size:24px;cursor:pointer}.status-strip{display:flex;align-items:center;gap:8px;padding:10px 20px;color:#9db8c6;background:rgba(7,18,28,.42);font-size:12px}.status-strip strong{margin-left:auto;color:#75e0b2}.status-dot{width:7px;height:7px;border-radius:50%;background:#54d69b;box-shadow:0 0 12px #54d69b}.metrics-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding:14px}.standard-card,.metric-card{padding:13px;border-radius:12px;background:rgba(20,53,70,.58);border:1px solid rgba(126,190,207,.12)}.standard-card{display:flex;flex-direction:column;min-height:82px}.standard-card span{color:#9db8c6;font-size:11px}.standard-card strong{margin:8px 0 5px;color:#67dcc0;font:600 18px Consolas,monospace}.standard-card small{color:#668a9b;font-size:9px;line-height:1.35}.quality-row{display:flex;justify-content:space-between;gap:12px;padding:11px 18px 14px;color:#7898a8;font-size:10px;border-top:1px solid rgba(148,207,217,.14)}.scenario-switch{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;padding:12px 14px 0}.scenario-switch button{padding:8px 10px;border:1px solid rgba(101,216,189,.22);border-radius:8px;color:#8facbb;background:rgba(13,41,56,.6);cursor:pointer;font-size:11px}.scenario-switch button.active{color:#062b34;background:#65d8bd;border-color:#65d8bd;font-weight:700}.comparison-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:14px}.metric-title{display:flex;justify-content:space-between;color:#b8ced9;font-size:12px}.trend{padding:2px 7px;border-radius:999px;font-size:10px}.trend.good{color:#78e1b4;background:rgba(30,148,102,.18)}.trend.neutral{color:#a7bdc8;background:rgba(148,163,184,.12)}.values{display:grid;grid-template-columns:1fr auto 1fr;align-items:end;gap:8px;margin-top:12px}.values div{display:flex;flex-direction:column}.values small{color:#7695a5;font-size:10px}.values strong{font:600 17px Consolas,monospace}.values strong.after{color:#65d8bd}.arrow{color:#4d7b91}.delta{margin-top:8px;color:#7898a8;font-size:10px}.panel-footer{display:flex;gap:14px;padding:10px 18px;border-top:1px solid rgba(148,207,217,.14);color:#8facbb;font-size:10px}.disclaimer{margin:0;padding:0 18px 14px;color:#688a9b;font-size:10px}.slide-fade-enter-active,.slide-fade-leave-active{transition:.25s ease}.slide-fade-enter-from,.slide-fade-leave-to{opacity:0;transform:translateY(16px)}@media(max-width:800px){.metrics-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:600px){.analysis-panel{left:12px;bottom:12px;width:calc(100vw - 24px)}.comparison-grid,.metrics-grid{grid-template-columns:1fr}.quality-row{flex-direction:column}}
</style>
