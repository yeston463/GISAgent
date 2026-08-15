<template>
  <section class="graph-workbench">
    <button class="graph-toggle" @click="toggleWorkbench">图谱工作台</button>
    <aside v-if="visible" class="graph-panel">
      <header><div><small>空间知识图谱</small><h2>图谱发布工作台</h2></div><button @click="visible=false">×</button></header>
      <p class="active">当前图谱：{{ graphStatus.version || '加载中' }} · {{ graphStatus.source || '内置' }}</p>
      <div class="toolbar"><button @click="refreshGraph" :disabled="busy">刷新</button><button @click="loadSelected" :disabled="busy || !selected">加载</button><button @click="applyTemplate" :disabled="busy || !selected">套用模板</button><button @click="clearDraft" :disabled="busy">手动填写</button><button @click="generateWithAi" :disabled="busy || !selected || !aiRequest.trim()">AI 生成</button><button @click="testIntents" :disabled="busy">测试意图</button></div>
      <div v-if="manualMode" class="dynamic-method">
        <strong>新增动态方法</strong>
        <label>方法名<input v-model.trim="dynamicName" placeholder="例如 parcel_score" /></label>
        <label>方法说明<input v-model.trim="dynamicDescription" placeholder="返回 JSON 指标" /></label>
        <label>计算需求<textarea v-model="dynamicRequirement" rows="3" placeholder="例如根据 params.area 和 params.count 计算平均值" /></label>
        <label>注册上下文 JSON<textarea v-model="dynamicContextText" rows="3" spellcheck="false">{}</textarea></label>
        <button class="primary" @click="createDynamicTool" :disabled="busy || !dynamicName || !dynamicRequirement.trim()">注册工具并发布图谱</button>
      </div>
      <label>能力 ID
        <select v-if="!manualMode" v-model="selected" @change="loadSelected"><option v-for="item in capabilities" :key="item.id" :value="item.id">{{ item.id }}</option></select>
        <input v-else v-model.trim="selected" placeholder="例如 site_selection 或 urban_metrics" />
      </label>
      <label>AI 图谱需求<textarea v-model="aiRequest" rows="3" placeholder="例如：为 site_selection 增加“最短路径选择”别名，说明考虑道路通行距离，并生成验收语句。" /></label>
      <label>候选版本<input v-model="draft.version" placeholder="可留空，由 AI 生成" /></label>
      <label>别名（每行一个）<textarea v-model="aliases" rows="4" /></label>
      <label>知识说明<textarea v-model="purpose" rows="3" /></label>
      <label>验收语句（每行一个）<textarea v-model="acceptanceText" rows="3" /></label>
      <label>完整候选 JSON<textarea v-model="draftText" rows="10" spellcheck="false" /></label>
      <div class="toolbar"><button @click="generateDraft" :disabled="busy">生成 JSON</button><button class="primary" @click="preview" :disabled="busy">校验并预览</button></div>
      <div v-if="previewData" class="preview"><strong>{{ previewData.valid ? '质量校验通过' : '质量校验未通过' }}：{{ previewData.preview?.version }}</strong><span v-for="change in previewData.preview?.changes" :key="change.capabilityId">{{ change.capabilityId }}：{{ changed(change) }} · {{ (change.impacts||[]).join('、') }}</span><span v-for="gate in previewData.qualityGates" :key="`${gate.capabilityId}-${gate.name}`" :class="gate.passed ? 'gate-pass' : 'gate-fail'">{{ gate.passed ? '✓' : '×' }} {{ gate.capabilityId }} / {{ gate.message }}</span><button v-if="previewData.valid" class="publish" @click="publish" :disabled="busy">发布并启用</button></div>
      <div class="tests" v-if="tests.length"><strong>自然语言测试</strong><div v-for="item in tests" :key="item.utterance" :class="item.matched ? 'pass' : 'fail'">{{ item.matched ? '✓' : '×' }} {{ item.utterance }}<small>{{ item.capabilityId || '未匹配' }} {{ item.tool || '' }}</small></div></div>
      <div class="revisions"><strong>已发布版本</strong><div v-for="item in revisions" :key="item.version"><span>{{ item.version }}</span><small>{{ item.note || item.publishedAt }}</small><button @click="rollback(item.version)" :disabled="busy || item.version===graphStatus.version">回滚</button></div></div>
      <p v-if="notice" :class="notice.error ? 'notice error' : 'notice'">{{ notice.text }}</p>
    </aside>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import axios from 'axios';

const visible = ref(false); const busy = ref(false); const capabilities = ref([]); const revisions = ref([]); const graphStatus = ref({});
const selected = ref(''); const aliases = ref(''); const purpose = ref(''); const acceptanceText = ref(''); const aiRequest = ref(''); const manualMode = ref(false); const draft = ref({ version: '' }); const draftText = ref(''); const previewData = ref(null); const tests = ref([]); const notice = ref(null);
const dynamicName = ref(''); const dynamicDescription = ref(''); const dynamicRequirement = ref(''); const dynamicContextText = ref('{}'); const dynamicTool = ref('');
const templates = { urban_metrics:{aliases:'容积率\n建筑指标\nFAR',purpose:'计算范围内容积率、建筑密度与覆盖率。',tests:'计算容积率'}, skyline_analysis:{aliases:'天际线分析\n天际线\nskyline',purpose:'按方向统计建筑高度轮廓。',tests:'进行天际线分析'}, sunlight_analysis:{aliases:'日照分析\n日照与阴影筛查\n阴影分析\nsunlight',purpose:'筛查日照时窗和阴影影响。',tests:'日照与阴影筛查'}, flood_analysis:{aliases:'洪水分析\n内涝分析\n淹没分析\nflood',purpose:'结合 DEM、降雨和排水条件进行洪涝风险筛查。',tests:'洪水分析 80mm，24h，20年'} };
const current = computed(() => capabilities.value.find(item => item.id === selected.value));
const notify = (text, error=false) => { notice.value = { text, error }; };
const load = async () => { busy.value=true; try { const [caps, history] = await Promise.all([axios.get('/api/agent/capabilities'), axios.get('/api/agent/capabilities/revisions')]); capabilities.value=caps.data.capabilities||[]; graphStatus.value=caps.data.graph||{}; revisions.value=history.data.revisions||[]; if (!selected.value) selected.value=capabilities.value[0]?.id||''; loadSelected(); } catch (e) { notify(e.response?.data?.code || e.message, true); } finally { busy.value=false; } };
const refreshGraph = async () => { busy.value=true; try { await axios.post('/api/agent/capabilities/refresh'); await load(); notify('已刷新图谱配置。'); } catch (e) { notify(e.response?.data?.message || e.message, true); } finally { busy.value=false; } };
const loadSelected = () => { const item=current.value; if (!item) return; manualMode.value=false; aliases.value=(item.aliases||[]).join('\n'); purpose.value=String(item.knowledge?.purpose||''); acceptanceText.value=templates[item.id]?.tests || (item.aliases||[])[0] || ''; previewData.value=null; };
const suggestedVersion = () => {
  const currentVersion = String(graphStatus.value.version || '1.0');
  const match = currentVersion.match(/^(.*?)(\d+)$/);
  return match ? `${match[1]}${Number(match[2]) + 1}` : `${currentVersion}-candidate`;
};
const applyTemplate = () => {
  const template=templates[selected.value];
  if (!template) return notify('当前能力没有可用模板',true);
  draft.value.version=suggestedVersion();
  aliases.value=template.aliases;
  purpose.value=template.purpose;
  acceptanceText.value=template.tests;
  draftText.value=JSON.stringify(graphObject(),null,2);
  previewData.value=null;
  notify(`已载入 ${selected.value} 模板。`);
};
const clearDraft = () => {
  manualMode.value=true;
  selected.value='';
  draft.value.version='';
  aliases.value='';
  purpose.value='';
  acceptanceText.value='';
  aiRequest.value='';
  dynamicName.value=''; dynamicDescription.value=''; dynamicRequirement.value=''; dynamicContextText.value='{}'; dynamicTool.value='';
  draftText.value='';
  previewData.value=null;
  notify('可手动填写候选图谱。');
};
const graphObject = () => {
  const id = selected.value.trim();
  if (manualMode.value) {
    if (!dynamicTool.value || !id) throw new Error('请先生成并注册动态方法');
    return {version:draft.value.version.trim(),capabilities:[{
      id, enabled:true, aliases:aliases.value.split(/\r?\n/).map(x=>x.trim()).filter(Boolean),
      requires:[], optional:[], operations:['dynamic_compute'], tool:dynamicTool.value,
      outputs:['metric'], rendererKinds:['metric'], dataRequirements:[],
      knowledge:{purpose:purpose.value.trim() || dynamicDescription.value.trim()}
    }]};
  }
  const item=current.value; if (!item) throw new Error('请填写已注册的能力 ID');
  const edited={...item, aliases:aliases.value.split(/\r?\n/).map(x=>x.trim()).filter(Boolean), knowledge:{...(item.knowledge||{}), ...(purpose.value.trim()?{purpose:purpose.value.trim()}: {})}};
  return {version:draft.value.version.trim(),capabilities:[edited]};
};
const generateDraft = () => { try { draftText.value=JSON.stringify(graphObject(),null,2); previewData.value=null; } catch(e) { notify(e.message,true); } };
const createDynamicTool = async () => {
  busy.value=true;
  try {
    const context=JSON.parse(dynamicContextText.value || '{}');
    const {data}=await axios.post('/api/agent/capabilities/publish-dynamic',{name:dynamicName.value,capabilityId:dynamicName.value,description:dynamicDescription.value,requirement:dynamicRequirement.value,context,version:draft.value.version.trim(),aliases:aliases.value.split(/\r?\n/).map(x=>x.trim()).filter(Boolean),purpose:purpose.value,acceptanceUtterances:acceptanceText.value.split(/\r?\n/).map(x=>x.trim()).filter(Boolean),author:'graph-workbench',note:`动态方法 ${dynamicName.value}`});
    if (!data.published || !data.registeredTool) throw new Error(data.message || '动态方法发布失败');
    dynamicTool.value=data.registeredTool; selected.value=dynamicName.value; draft.value.version=data.revision?.version || draft.value.version || suggestedVersion();
    aliases.value=`动态方法\n${dynamicDescription.value || dynamicName.value}`; purpose.value=dynamicDescription.value;
    acceptanceText.value=aliases.value.split(/\r?\n/)[0]; draftText.value=data.graph || JSON.stringify(graphObject(),null,2); previewData.value=null;
    manualMode.value=false; await load(); notify(`动态方法 ${data.registeredTool} 已注册、校验并发布`);
  } catch(e) { notify(e.response?.data?.message || e.message,true); }
  finally { busy.value=false; }
};
const generateWithAi = async () => {
  busy.value=true;
  try {
    const { data } = await axios.post('/api/agent/capabilities/candidates/ai-generate', {
      capabilityId:selected.value,
      request:aiRequest.value.trim(),
      candidateVersion:draft.value.version.trim()
    });
    draft.value.version=data.version||'';
    aliases.value=(data.aliases||[]).join('\n');
    purpose.value=data.purpose||'';
    acceptanceText.value=(data.acceptanceUtterances||[]).join('\n');
    draftText.value=data.graph||'';
    previewData.value=data.preview||null;
    notify('AI 已生成候选图谱，请校验后再发布。');
  } catch(e) { notify(e.response?.data?.message || e.message,true); }
  finally { busy.value=false; }
};
const candidate = () => JSON.parse(draftText.value || JSON.stringify(graphObject()));
const acceptanceTests = () => ({[selected.value]:acceptanceText.value.split(/\r?\n/).map(x=>x.trim()).filter(Boolean)});
const preview = async () => { busy.value=true; try { const graph=JSON.stringify(candidate()); previewData.value=(await axios.post('/api/agent/capabilities/candidates/preview',{graph,acceptanceTests:acceptanceTests()})).data; notify(previewData.value.valid ? '候选图谱已通过质量校验。' : '候选图谱未通过质量校验。',!previewData.value.valid); } catch(e) { previewData.value=null; notify(e.response?.data?.message || e.message,true); } finally { busy.value=false; } };
const publish = async () => { busy.value=true; try { const graph=JSON.stringify(candidate()); const result=(await axios.post('/api/agent/capabilities/publish',{graph,author:'graph-workbench',note:`编辑 ${selected.value}`,acceptanceTests:acceptanceTests()})).data; graphStatus.value=result.active||{}; previewData.value=null; notify(`已发布并启用 ${result.revision?.version}`); await load(); } catch(e) { notify(e.response?.data?.message || e.message,true); } finally { busy.value=false; } };
const rollback = async version => { if (!window.confirm(`确认回滚到 ${version}？`)) return; busy.value=true; try { const result=(await axios.post(`/api/agent/capabilities/rollback/${encodeURIComponent(version)}`)).data; graphStatus.value=result.active||{}; notify(`已回滚到 ${version}`); await load(); } catch(e) { notify(e.response?.data?.message || e.message,true); } finally { busy.value=false; } };
const testIntents = async () => { busy.value=true; try { tests.value=(await axios.post('/api/agent/capabilities/test-intents',{utterances:['进行天际线分析','日照与阴影筛查','洪水分析 80mm，24h，20年','计算容积率']})).data.results||[]; } catch(e) { notify(e.response?.data?.message || e.message,true); } finally { busy.value=false; } };
const changed = value => ['aliasesChanged','dataRequirementsChanged','knowledgeChanged'].filter(key=>value[key]).map(key=>({aliasesChanged:'aliases',dataRequirementsChanged:'data requirements',knowledgeChanged:'knowledge'}[key])).join(', ');
const toggleWorkbench = () => { visible.value = !visible.value; if (visible.value && !capabilities.value.length) load(); };
onMounted(load);
</script>

<style scoped>
/* GeoScene 组件同款浅色风格 */
.graph-workbench{position:absolute;top:74px;right:18px;z-index:1400}
.graph-toggle{border:1px solid #a8a8a8;border-radius:4px;background:#ffffff;color:#2b2b2b;padding:7px 12px;font-size:12px;cursor:pointer;box-shadow:0 1px 2px rgba(0,0,0,.25);font-family:"Avenir Next","Segoe UI",Arial,sans-serif}
.graph-toggle:hover{border-color:#0079c1;color:#0079c1}
.graph-panel{position:absolute;right:0;top:42px;width:360px;max-height:calc(100vh - 134px);overflow:auto;padding:16px;border:1px solid #d4d4d4;border-radius:4px;background:#ffffff;color:#4c4c4c;box-shadow:0 2px 8px rgba(0,0,0,.3);font-size:12px;font-family:"Avenir Next","Segoe UI",Arial,sans-serif}
.graph-panel header{display:flex;justify-content:space-between}
.graph-panel header h2{margin:3px 0 12px;font-size:17px;color:#2b2b2b}
.graph-panel header small{color:#6e6e6e;letter-spacing:.08em}
.graph-panel header button{border:0;background:transparent;color:#6e6e6e;font-size:22px;cursor:pointer}
.graph-panel header button:hover{color:#0079c1}
.active{margin:0 0 12px;color:#6e6e6e}
.graph-panel label{display:block;margin:10px 0;color:#4c4c4c;font-weight:600}
.graph-panel input,.graph-panel select,.graph-panel textarea{box-sizing:border-box;display:block;width:100%;margin-top:4px;padding:6px 7px;border:1px solid #a8a8a8;border-radius:3px;background:#ffffff;color:#2b2b2b;font:12px Consolas,monospace;outline:none}
.graph-panel input:focus,.graph-panel select:focus,.graph-panel textarea:focus{border-color:#0079c1;box-shadow:0 0 0 1px #0079c1}
.toolbar{display:flex;gap:7px;margin:9px 0;flex-wrap:wrap}
.toolbar button,.preview button,.revisions button,.dynamic-method button{border:1px solid #a8a8a8;border-radius:3px;background:#f8f8f8;color:#2b2b2b;padding:5px 9px;cursor:pointer;font-family:inherit}
.toolbar button:hover,.preview button:hover,.revisions button:hover,.dynamic-method button:hover{border-color:#0079c1;color:#0079c1}
.toolbar .primary,.publish{background:#0079c1!important;color:#ffffff!important;border-color:#0079c1!important}
.toolbar .primary:hover,.publish:hover{background:#005e95!important;color:#ffffff!important;border-color:#005e95!important}
.preview,.tests,.revisions{display:grid;gap:6px;margin-top:13px;padding:10px;border:1px solid #e0e0e0;border-radius:4px;background:#f8f8f8}
.preview span,.tests small,.revisions small{display:block;color:#6e6e6e}
.gate-pass{color:#2e7d32!important}
.gate-fail{color:#c62828!important}
.tests .pass{color:#2e7d32}
.tests .fail{color:#c62828}
.revisions>div{display:grid;grid-template-columns:1fr auto;gap:4px;align-items:center}
.revisions small{grid-column:1}
.revisions button{grid-column:2;grid-row:1/3}
.notice{color:#2e7d32}
.notice.error{color:#c62828}
.dynamic-method{margin:10px 0;padding:10px;border:1px solid #e0e0e0;border-radius:4px;background:#f8f8f8}
.dynamic-method strong{display:block;color:#2b2b2b;margin-bottom:6px}
@media(max-width:600px){.graph-panel{width:min(360px,calc(100vw - 36px))}}
</style>
