<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="logo">GIS</div>
        <h1>GIS 智能分析平台</h1>
        <p class="subtitle">请登录后使用</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          class="login-error"
        />

        <el-button
          type="primary"
          class="login-button"
          :loading="loading"
          @click="submit"
        >
          登 录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue';
import { User, Lock } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { login } from './auth';

const emit = defineEmits(['login-success']);

const formRef = ref(null);
const loading = ref(false);
const errorMessage = ref('');

const form = reactive({ username: '', password: '' });
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

const submit = async () => {
  errorMessage.value = '';
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  loading.value = true;
  try {
    await login(form.username, form.password);
    ElMessage.success('登录成功');
    emit('login-success');
  } catch (error) {
    errorMessage.value = error.message || '登录失败';
  } finally {
    loading.value = false;
  }
};
</script>

<style scoped>
.login-page {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(circle at 20% 20%, rgba(0, 74, 117, 0.45), transparent 45%),
    radial-gradient(circle at 80% 70%, rgba(0, 120, 160, 0.35), transparent 40%),
    linear-gradient(135deg, #0a1a2f 0%, #0e2a45 60%, #0a1a2f 100%);
  overflow: hidden;
}

.login-card {
  width: 380px;
  max-width: 92vw;
  background: rgba(255, 255, 255, 0.97);
  border-radius: 14px;
  padding: 40px 36px 32px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
}

.login-brand {
  text-align: center;
  margin-bottom: 28px;
}

.logo {
  width: 64px;
  height: 64px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, #004a75, #0077b6);
  color: #fff;
  font-weight: 700;
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 1px;
}

.login-brand h1 {
  font-size: 20px;
  margin: 0 0 6px;
  color: #0e2a45;
}

.subtitle {
  font-size: 13px;
  color: #8a94a6;
  margin: 0;
}

.login-button {
  width: 100%;
  margin-top: 4px;
}

.login-error {
  margin-bottom: 14px;
}
</style>
