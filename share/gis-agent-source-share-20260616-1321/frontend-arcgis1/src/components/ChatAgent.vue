<template>
  <div class="chat-wrapper">
    <div class="chat-window" ref="scrollContainer">
      <div
        v-for="(msg, index) in messages"
        :key="index"
        :class="['msg-bubble', msg.role, { 'report-bubble': msg.report }]"
      >
        <div v-if="msg.hasCommand" class="command-tag">
          <el-icon><Compass /></el-icon>
          <span>空间引擎联动中</span>
        </div>
        <article v-if="msg.report" class="analysis-report">
          <header class="report-header">
            <div>
              <p class="report-kicker">建筑指标分析</p>
              <h2>{{ msg.report.title }}</h2>
            </div>
            <div v-if="msg.report.far" class="far-badge">
              <span>FAR</span>
              <strong>{{ msg.report.far.value }}</strong>
            </div>
          </header>

          <div v-if="msg.report.info.length" class="info-strip">
            <div v-for="item in msg.report.info" :key="item.label" class="info-item">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>

          <div v-if="msg.report.metrics.length" class="metric-grid">
            <div
              v-for="metric in msg.report.metrics"
              :key="metric.label"
              :class="['metric-card', { primary: metric.primary }]"
            >
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
            </div>
          </div>

          <section v-if="msg.report.conclusions.length" class="report-section">
            <h3>分析结论</h3>
            <ol class="report-list">
              <li v-for="item in msg.report.conclusions" :key="item">{{ item }}</li>
            </ol>
          </section>

          <section v-if="msg.report.suggestions.length" class="report-section">
            <h3>后续建议</h3>
            <ol class="report-list suggestion-list">
              <li v-for="item in msg.report.suggestions" :key="item">{{ item }}</li>
            </ol>
          </section>
        </article>

        <div v-else class="msg-content">
          <template v-for="(block, blockIndex) in formatMessageBlocks(msg.text)" :key="blockIndex">
            <h3 v-if="block.type === 'heading'" class="message-heading">{{ block.text }}</h3>
            <ol v-else-if="block.type === 'ordered-list'" class="message-list">
              <li v-for="item in block.items" :key="item">{{ item }}</li>
            </ol>
            <ul v-else-if="block.type === 'unordered-list'" class="message-list">
              <li v-for="item in block.items" :key="item">{{ item }}</li>
            </ul>
            <p v-else>{{ block.text }}</p>
          </template>
        </div>
      </div>

      <div v-if="isAnalyzing" class="ai-loading">
        <div class="spinner"></div>
        <span class="loading-text">{{ loadingStatusText }}</span>
      </div>
    </div>

    <div class="input-container">
      <input
        v-model="currentInput"
        @keyup.enter="handleUserSend"
        placeholder="输入 GIS 分析任务..."
        :disabled="isAnalyzing"
      />
      <button @click="handleUserSend" :disabled="isAnalyzing || !currentInput.trim()">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, toRef, nextTick, onMounted, onUnmounted } from 'vue';
import { Compass } from '@element-plus/icons-vue';
import axios from 'axios';
import { useCommandExecutor } from '../useCommandExecutor';

const props = defineProps(['mapView']);
const scrollContainer = ref(null);
const { execute } = useCommandExecutor(toRef(props, 'mapView'));

const messages = ref([
  {
    role: 'assistant',
    text: '你好，我是 GIS 分析 Agent。你可以让我计算容积率、分析红线/AOI，或查询指定地点周边建筑指标。'
  }
]);
const currentInput = ref('');
const isAnalyzing = ref(false);
const loadingStatusText = ref('正在分析...');
const memoryId = ref(`gis-session-${Date.now()}`);

let isLoopLocked = false;

const handleScenarioSwitchRequest = event => {
  execute([{ action: 'switchPlanningScenario', params: event.detail || {} }]);
};
onMounted(() => {
  window.addEventListener('gis-data-ready', handleDataReady);
  window.addEventListener('sketch-aoi-ready', handleSketchReady);
  window.addEventListener('request-planning-scenario-switch', handleScenarioSwitchRequest);
});

onUnmounted(() => {
  window.removeEventListener('gis-data-ready', handleDataReady);
  window.removeEventListener('sketch-aoi-ready', handleSketchReady);
  window.removeEventListener('request-planning-scenario-switch', handleScenarioSwitchRequest);
});

const handleDataReady = () => {
  if (isLoopLocked) return;
  isLoopLocked = true;
  processAiChat('请调用 analyzeCurrentView 计算当前已上传数据的建筑指标并展示结果。', true);
  setTimeout(() => {
    isLoopLocked = false;
  }, 10000);
};

const handleSketchReady = () => {
  processAiChat('分析当前红线区域的建筑指标。', true);
};

const handleUserSend = () => {
  if (!currentInput.value.trim() || isAnalyzing.value) return;
  window.lastGisResult = {
    far: 0,
    site_area: 0,
    building_area: 0,
    building_count: 0,
    status: 'pending'
  };
  isLoopLocked = false;
  const text = currentInput.value;
  messages.value.push({ role: 'user', text });
  currentInput.value = '';
  scrollToBottom();
  processAiChat(text);
};

const processAiChat = async (userInput, isHidden = false) => {
  isAnalyzing.value = true;
  loadingStatusText.value = isHidden ? '正在同步分析...' : 'Agent 正在抓取有效数据...';

  try {
    const response = await axios.post('http://localhost:8080/api/agent/chat/agentic', {
      message: userInput,
      memoryId: memoryId.value
    });

    const data = response.data || {};
    const commands = Array.isArray(data.commands) ? data.commands : [];
    const hasScreenFallback = commands.some(command => command.action === 'getScreenBuildings');
    const comparisonCommand = commands.find(command => command.action === 'comparePlanningScenarios');
    if (comparisonCommand?.params?.comparison) {
      window.dispatchEvent(new CustomEvent('show-planning-comparison', {
        detail: comparisonCommand.params.comparison
      }));
    }

    if (commands.length > 0) {
      await execute(commands);
    }

    if (hasScreenFallback) {
      await waitForGisDataReady();
      if (!isHidden) {
        messages.value.push(createAssistantMessage(
          '已完成前端建筑数据同步，正在用服务端重新校验指标。',
          true
        ));
      }
      return;
    }

    if (!isHidden || data.needClarification) {
      messages.value.push(createAssistantMessage(
        data.reply || data.clarification || '分析完成。',
        commands.length > 0
      ));
    }

    if (!isHidden && Array.isArray(data.suggestions) && data.suggestions.length > 0) {
      messages.value.push(createAssistantMessage(
        `建议：\n${data.suggestions.map((item, index) => `${index + 1}. ${item}`).join('\n')}`,
        false
      ));
    }
  } catch (err) {
    console.error('Agent request failed:', err);
    if (!isHidden) {
      messages.value.push(createAssistantMessage('处理请求时出现错误，请确认后端服务和 GIS 引擎已启动。'));
    }
  } finally {
    isAnalyzing.value = false;
    scrollToBottom();
  }
};

const waitForGisDataReady = () => {
  loadingStatusText.value = '正在同步前端建筑数据...';
  return new Promise(resolve => {
    const handler = () => {
      window.removeEventListener('gis-data-ready', handler);
      resolve();
    };
    window.addEventListener('gis-data-ready', handler);
    setTimeout(() => {
      window.removeEventListener('gis-data-ready', handler);
      resolve();
    }, 30000);
  });
};

const scrollToBottom = () => {
  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight;
    }
  });
};

const createAssistantMessage = (text, hasCommand = false) => ({
  role: 'assistant',
  text,
  hasCommand,
  report: parseAnalysisReport(text)
});

const cleanMarkdown = (value = '') => value
  .replace(/\*\*/g, '')
  .replace(/`/g, '')
  .replace(/^#+\s*/, '')
  .trim();

const parseAnalysisReport = (text = '') => {
  if (!/建筑分析报告|建筑指标|容积率\(FAR\)|容积率/.test(text)) return null;

  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  const title = cleanMarkdown(lines.find(line => /^#{1,3}\s/.test(line)) || '建筑分析报告');
  const report = {
    title,
    info: [],
    metrics: [],
    conclusions: [],
    suggestions: [],
    far: null
  };

  let section = '';
  for (const line of lines) {
    const normalized = cleanMarkdown(line);
    if (!normalized || normalized === title) continue;

    if (/基本信息/.test(normalized)) {
      section = 'info';
      continue;
    }
    if (/建筑指标/.test(normalized)) {
      section = 'metrics';
      continue;
    }
    if (/分析结论/.test(normalized)) {
      section = 'conclusions';
      continue;
    }
    if (/^建议[:：]?$|后续建议/.test(normalized)) {
      section = 'suggestions';
      continue;
    }

    if (section === 'info' && /^[-*]\s*/.test(line)) {
      const item = cleanMarkdown(line.replace(/^[-*]\s*/, ''));
      const parts = item.split(/[：:]/);
      if (parts.length >= 2) {
        report.info.push({
          label: parts.shift().trim(),
          value: parts.join('：').trim()
        });
      }
      continue;
    }

    if (section === 'metrics' && /^\|/.test(line)) {
      const cells = line.split('|').map(cleanMarkdown).filter(Boolean);
      if (cells.length < 2 || /指标|---/.test(cells[0])) continue;
      const metric = {
        label: cells[0],
        value: cells[1],
        primary: /容积率|FAR/i.test(cells[0])
      };
      report.metrics.push(metric);
      if (metric.primary) report.far = metric;
      continue;
    }

    const numbered = normalized.match(/^\d+[.、]\s*(.+)$/);
    if (numbered && (section === 'conclusions' || section === 'suggestions')) {
      const item = numbered[1].replace(/^(.{2,12})[：:]\s*/, '$1：').trim();
      report[section].push(item);
    }
  }

  if (!report.metrics.length && !report.info.length && !report.conclusions.length) {
    return null;
  }
  return report;
};

const formatMessageBlocks = (text = '') => {
  const blocks = [];
  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);

  for (const line of lines) {
    if (/^#{1,4}\s+/.test(line)) {
      blocks.push({ type: 'heading', text: cleanMarkdown(line) });
      continue;
    }

    const ordered = line.match(/^(\d+)[.、]\s+(.+)$/);
    if (ordered) {
      const last = blocks[blocks.length - 1];
      if (last?.type === 'ordered-list') {
        last.items.push(cleanMarkdown(ordered[2]));
      } else {
        blocks.push({ type: 'ordered-list', items: [cleanMarkdown(ordered[2])] });
      }
      continue;
    }

    const unordered = line.match(/^[-*]\s+(.+)$/);
    if (unordered) {
      const last = blocks[blocks.length - 1];
      if (last?.type === 'unordered-list') {
        last.items.push(cleanMarkdown(unordered[1]));
      } else {
        blocks.push({ type: 'unordered-list', items: [cleanMarkdown(unordered[1])] });
      }
      continue;
    }

    blocks.push({ type: 'paragraph', text: cleanMarkdown(line) });
  }

  return blocks.length ? blocks : [{ type: 'paragraph', text }];
};
</script>

<style scoped>
.chat-wrapper { position: absolute; bottom: 30px; right: 30px; z-index: 1000; width: min(520px, calc(100vw - 36px)); height: min(650px, calc(100vh - 72px)); background: rgba(255, 255, 255, 0.95); border-radius: 12px; box-shadow: 0 12px 40px rgba(15,23,42,0.18); display: flex; flex-direction: column; overflow: hidden; backdrop-filter: blur(10px); border: 1px solid rgba(148, 163, 184, 0.28); }
.chat-window { flex: 1; padding: 16px; overflow-y: auto; background: #f7f9fb; display: flex; flex-direction: column; gap: 12px; }
.msg-bubble { max-width: 86%; padding: 10px 14px; border-radius: 10px; font-size: 13.5px; line-height: 1.55; }
.msg-bubble.report-bubble { width: 100%; max-width: 100%; padding: 0; overflow: hidden; }
.user { align-self: flex-end; background: #005e95; color: white; }
.assistant { align-self: flex-start; background: white; color: #26313f; box-shadow: 0 1px 4px rgba(15,23,42,0.06); border: 1px solid rgba(226, 232, 240, 0.9); }
.command-tag { font-size: 11px; color: #005e95; font-weight: bold; margin-bottom: 5px; display: flex; align-items: center; gap: 4px; border-bottom: 1px dashed rgba(0,0,0,0.1); padding-bottom: 4px; }
.msg-content { display: flex; flex-direction: column; gap: 6px; }
.msg-content p { margin: 0; }
.message-heading { margin: 2px 0 4px; font-size: 14px; color: #0f3d5c; }
.message-list { margin: 0; padding-left: 18px; }
.message-list li { margin: 3px 0; }
.analysis-report { background: white; color: #1f2937; }
.report-header { display: flex; justify-content: space-between; gap: 12px; padding: 16px; background: linear-gradient(135deg, #f8fbff 0%, #eef7f6 100%); border-bottom: 1px solid #dde7ef; }
.report-kicker { margin: 0 0 4px; color: #3c6f7d; font-size: 11px; font-weight: 700; letter-spacing: 0; }
.report-header h2 { margin: 0; color: #102a43; font-size: 18px; line-height: 1.25; }
.far-badge { flex: 0 0 auto; min-width: 82px; padding: 8px 10px; border-radius: 8px; background: #0f766e; color: white; text-align: center; align-self: flex-start; }
.far-badge span { display: block; font-size: 11px; opacity: 0.85; }
.far-badge strong { display: block; font-size: 22px; line-height: 1.1; font-family: Consolas, monospace; }
.info-strip { display: grid; grid-template-columns: 1fr; gap: 8px; padding: 12px 16px; background: #fbfcfd; border-bottom: 1px solid #edf2f7; }
.info-item { display: grid; grid-template-columns: 78px 1fr; gap: 8px; align-items: baseline; }
.info-item span { color: #64748b; font-size: 12px; }
.info-item strong { color: #27364a; font-size: 12.5px; font-weight: 600; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 12px 16px; }
.metric-card { min-height: 62px; padding: 10px; border-radius: 8px; background: #f8fafc; border: 1px solid #e5edf3; display: flex; flex-direction: column; justify-content: space-between; }
.metric-card span { color: #667085; font-size: 12px; }
.metric-card strong { color: #162033; font-size: 15px; font-family: Consolas, monospace; line-height: 1.25; word-break: break-word; }
.metric-card.primary { background: #ecfdf5; border-color: #99d9c8; }
.metric-card.primary strong { color: #0f766e; font-size: 18px; }
.report-section { padding: 12px 16px; border-top: 1px solid #edf2f7; }
.report-section h3 { margin: 0 0 8px; font-size: 13px; color: #17324d; }
.report-list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 7px; }
.report-list li { color: #344054; }
.suggestion-list li { color: #475467; }
.input-container { padding: 12px; display: flex; gap: 8px; background: white; border-top: 1px solid #eee; }
input { flex: 1; border: 1px solid #ddd; border-radius: 8px; padding: 8px; outline: none; min-width: 0; }
button { background: #005e95; color: white; border: none; padding: 0 15px; border-radius: 8px; cursor: pointer; white-space: nowrap; }
button:disabled { opacity: 0.55; cursor: not-allowed; }
.ai-loading { display: flex; align-items: center; gap: 8px; padding: 10px; color: #475569; font-size: 13px; }
.spinner { width: 14px; height: 14px; border: 2px solid #ddd; border-top-color: #005e95; border-radius: 50%; animation: spin 0.8s infinite linear; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 640px) {
  .chat-wrapper { right: 12px; bottom: 12px; width: calc(100vw - 24px); height: min(680px, calc(100vh - 24px)); }
  .metric-grid { grid-template-columns: 1fr; }
  .report-header { flex-direction: column; }
  .far-badge { width: 100%; }
}
</style>
