// httpClient.js - 全局注入登录令牌并统一处理 401
// 必须在其它组件加载前引入本模块（main.js 顶部）。
import axios from 'axios';
import { auth, clearSession } from './auth';

// 为所有既有组件使用的 axios 实例统一附加 Bearer 令牌
axios.interceptors.request.use((config) => {
  if (auth.token) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${auth.token}`;
  }
  return config;
});

axios.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response && error.response.status === 401) {
      clearSession();
    }
    return Promise.reject(error);
  }
);

// 补丁原生 fetch（SSE 流、CityEngine 轮询等直接 fetch 的调用自动带令牌）
if (typeof window !== 'undefined') {
  const originalFetch = window.fetch.bind(window);
  window.fetch = async (input, init = {}) => {
    const headers = new Headers(init.headers || {});
    if (auth.token) {
      headers.set('Authorization', `Bearer ${auth.token}`);
    }
    const response = await originalFetch(input, { ...init, headers });
    if (response.status === 401) {
      clearSession();
    }
    return response;
  };
}
