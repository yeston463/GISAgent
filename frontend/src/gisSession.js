const STORAGE_KEY = 'gis-agent-memory-id';
const VERSION_KEY = 'gis-agent-context-version';

export function getGisSessionId() {
  let sessionId = sessionStorage.getItem(STORAGE_KEY);
  if (!sessionId) {
    sessionId = `gis-session-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    sessionStorage.setItem(STORAGE_KEY, sessionId);
  }
  return sessionId;
}

export function getGisContextVersion() {
  const value = Number(sessionStorage.getItem(VERSION_KEY) || '0');
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

export function setGisContextVersion(version) {
  const normalized = Number(version);
  if (Number.isSafeInteger(normalized) && normalized >= 0) {
    sessionStorage.setItem(VERSION_KEY, String(normalized));
  }
}
