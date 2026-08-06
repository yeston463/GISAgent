// auth.js - 全局登录状态（JWT）
import { reactive } from 'vue';

const TOKEN_KEY = 'lc4j_token';
const USER_KEY = 'lc4j_user';

function readStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null');
  } catch {
    return null;
  }
}

export const auth = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  user: readStoredUser(),
  get isAuthenticated() {
    return !!this.token;
  },
  get isAdmin() {
    return this.user && this.user.role === 'ADMIN';
  },
  get displayName() {
    return (this.user && (this.user.displayName || this.user.username)) || '';
  },
  get role() {
    return (this.user && this.user.role) || '';
  },
});

export function getToken() {
  return auth.token;
}

export function setSession(token, user) {
  auth.token = token;
  auth.user = user;
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession() {
  auth.token = '';
  auth.user = null;
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export async function login(username, password) {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.message || '登录失败，请检查用户名和密码。');
  }
  setSession(body.token, {
    username: body.username,
    displayName: body.displayName || body.username,
    role: body.role,
  });
  return body;
}

export function logout() {
  clearSession();
  // 通知后端（无状态 JWT，仅清理服务端侧记录，失败不影响前端退出）
  try {
    fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${auth.token}` } });
  } catch {
    /* ignore */
  }
}
