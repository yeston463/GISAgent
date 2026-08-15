<!-- components/KnowledgeManager.vue -->
<template>
  <div class="knowledge-container" :class="{ 'is-expanded': isExpanded }">
    <!-- 1. 悬浮触发按钮 (缩小尺寸) -->
    <transition name="fade">
      <div v-if="!isExpanded" class="floating-trigger" @click="isExpanded = true">
        <el-tooltip content="知识库管理" placement="right">
          <el-icon :size="20"><Reading /></el-icon>
        </el-tooltip>
      </div>
    </transition>

    <!-- 2. 展开后的面板 (更窄、更紧凑) -->
    <transition name="slide-up">
      <div v-if="isExpanded" class="knowledge-panel">
        <div class="panel-header">
          <div class="title">
            <el-icon :size="16"><Reading /></el-icon>
            <span>知识中枢</span>
          </div>
          <el-icon class="close-btn" @click="isExpanded = false"><Close /></el-icon>
        </div>

        <div class="panel-body">
          <!-- 缩小的上传区域（仅管理员可见） -->
          <el-upload
              class="knowledge-upload"
              drag
              action="/api/knowledge/upload"
              :headers="uploadHeaders"
              :on-success="handleSuccess"
              :on-error="handleError"
              accept=".txt,.pdf,.md"
          >
            <el-icon class="el-icon--upload" :size="24"><upload-filled /></el-icon>
            <div class="el-upload__text">导入文档 (PDF/MD)</div>
          </el-upload>

          <div class="file-list-header">已存知识 ({{ uploadedFiles.length }})</div>

          <div class="file-list">
            <div v-for="file in uploadedFiles" :key="file.id" class="file-item">
              <el-icon :size="14"><Document /></el-icon>
              <span class="name">{{ file.name }}</span>
              <el-icon class="del-icon" @click="removeKnowledge(file.id)"><Delete /></el-icon>
            </div>
            <el-empty v-if="uploadedFiles.length === 0" :image-size="30" description="暂无背景知识" />
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { Reading, UploadFilled, Close, Document, Delete } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

const isExpanded = ref(false);
const uploadedFiles = ref([]);

const uploadHeaders = {};

const handleSuccess = (res) => {
  ElMessage.success("知识已入库");
  uploadedFiles.value.push({ name: res.name || '新地理文档', id: Date.now() });
};

const handleError = () => ElMessage.error("向量库连接失败");

const removeKnowledge = (id) => {
  uploadedFiles.value = uploadedFiles.value.filter(f => f.id !== id);
};
</script>

<style scoped>
/* 1. 容器定位：固定在左下角 */
.knowledge-container {
  position: absolute;
  left: 15px;
  bottom: 25px; /* 距离底部 25px */
  z-index: 1000;
  pointer-events: none;
}

.knowledge-container * {
  pointer-events: auto;
}

/* 2. 悬浮按钮：尺寸减小至 40px */
.floating-trigger {
  width: 40px;
  height: 40px;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 8px; /* 改为小圆角，更有科技感 */
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  cursor: pointer;
  color: #004a75;
  border: 1px solid rgba(0, 74, 117, 0.1);
  transition: all 0.3s ease;
}

.floating-trigger:hover {
  background: #004a75;
  color: white;
}

/* 3. 面板：宽度减小至 240px，更加紧凑 */
.knowledge-panel {
  width: 240px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(8px);
  border-radius: 8px;
  box-shadow: 0 -4px 20px rgba(0,0,0,0.15); /* 阴影向上 */
  border: 1px solid rgba(255, 255, 255, 0.5);
  overflow: hidden;
}

.panel-header {
  padding: 8px 12px;
  background: #004a75;
  color: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.panel-header .title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.close-btn { cursor: pointer; font-size: 16px; }

.panel-body { padding: 10px; }

/* 调整上传框高度 */
:deep(.el-upload-dragger) {
  padding: 10px 0 !important;
  border-width: 1px;
}

.el-upload__text {
  font-size: 11px;
  margin-top: 5px;
}

.file-list-header {
  font-size: 11px;
  color: #606266;
  margin: 12px 0 6px;
  font-weight: bold;
}

.readonly-tip {
  font-size: 11px;
  color: #8a94a6;
  background: rgba(0, 0, 0, 0.03);
  border-radius: 4px;
  padding: 8px;
  text-align: center;
}

.file-list {
  max-height: 150px; /* 减小高度 */
  overflow-y: auto;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  background: rgba(0,0,0,0.04);
  border-radius: 4px;
  margin-bottom: 4px;
  font-size: 11px;
}

.file-item .name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.del-icon { color: #f89898; cursor: pointer; font-size: 14px; }

/* 4. 动画：从下方滑入 */
.slide-up-enter-active, .slide-up-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}
.slide-up-enter-from, .slide-up-leave-to {
  transform: translateY(20px);
  opacity: 0;
}

.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.file-list::-webkit-scrollbar { width: 3px; }
.file-list::-webkit-scrollbar-thumb { background: #dcdfe6; border-radius: 2px; }
</style>
