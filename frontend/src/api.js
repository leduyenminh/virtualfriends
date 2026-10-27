const BASE = import.meta.env.VITE_API_URL ?? '';

// Session model:
//  - The ACCESS token (15 min) lives only in this module's memory. It is never written to localStorage, so an
//    injected script can't harvest it from storage, and it disappears when the tab closes.
//  - The REFRESH token is an httpOnly cookie (path /api/auth) that page scripts can't read at all. On page load and
//    whenever the access token is about to expire, we trade it for a new access token at /api/auth/refresh.
let accessToken = null;
let expiresAt = 0;
let refreshing = null;
let sessionLost = () => {};
let connectionLost = () => {};

try { localStorage.removeItem('vchat.token'); } catch { /* old versions kept the token here */ }

export function getToken() { return accessToken; }
export function setSession(res) { accessToken = res.token; expiresAt = Date.now() + res.expiresIn * 1000; }
export function clearSession() { accessToken = null; expiresAt = 0; }
/** Called when the session can't be renewed (expired, revoked, account disabled). */
export function onSessionLost(fn) { sessionLost = fn; }
/** Called when the server can't be reached at all (no network, backend down). */
export function onConnectionLost(fn) { connectionLost = fn; }

/**
 * fetch() that tells "can't reach the server" apart from errors the server sent: the former reject with
 * err.offline = true. A 502/503/504 without a JSON body comes from the load balancer/CDN/dev proxy, not from Spring.
 */
async function send(url, init) {
  let res;
  try {
    res = await fetch(url, init);
  } catch {
    throw unreachable();
  }
  if (res.status >= 502 && res.status <= 504 && !res.headers.get('Content-Type')?.includes('json')) throw unreachable();
  return res;
}

function unreachable() {
  connectionLost();
  return Object.assign(new Error("Can't reach VirtualFriends. Check your connection."), { offline: true });
}

/** Cheap reachability check (a public endpoint); when it fails, onConnectionLost fires like for any request. */
export function ping() {
  return send(`${BASE}/api/auth/providers`).then(() => true, () => false);
}

/** Trade the refresh cookie for a new access token. Concurrent callers share one request. */
export function refreshSession() {
  refreshing ??= send(`${BASE}/api/auth/refresh`, { method: 'POST', credentials: 'include' })
    .then(async (res) => {
      const body = await res.json().catch(() => ({}));
      if (!res.ok) { clearSession(); throw new Error(body.error || 'Not signed in'); }
      setSession(body);
      return body;
    })
    .finally(() => { refreshing = null; });
  return refreshing;
}

/** An access token valid for at least 30 more seconds (used before opening the WebSocket). */
export async function freshToken() {
  if (!accessToken || Date.now() > expiresAt - 30_000) await refreshSession();
  return accessToken;
}

/** One-shot message for the sign-in screen ("You signed out", "Session expired"). Not a secret. */
export function setNotice(text) {
  try { sessionStorage.setItem('vchat.notice', text); } catch { /* ignore */ }
}
export function takeNotice() {
  try {
    const t = sessionStorage.getItem('vchat.notice');
    sessionStorage.removeItem('vchat.notice');
    return t;
  } catch { return null; }
}

async function req(method, path, body, { raw = false, retry = true } = {}) {
  const isAuthCall = path.startsWith('/api/auth/');
  // Renew a token that is about to expire before using it, instead of waiting for a 401.
  if (!isAuthCall && accessToken && Date.now() > expiresAt - 30_000) await refreshSession().catch(() => {});

  const headers = {};
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  let payload = body;
  if (body && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await send(BASE + path, { method, headers, body: payload, credentials: 'include' });

  if (res.status === 401 && !isAuthCall) {
    if (retry) {
      try {
        await refreshSession();
        return req(method, path, body, { raw, retry: false });
      } catch { /* fall through: session is gone */ }
    }
    setNotice('Your session expired. Please sign in again.');
    sessionLost();
    throw new Error('Session expired');
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || `${res.status} ${res.statusText}`);
  }
  if (raw) return res.blob();
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  register: (b) => req('POST', '/api/auth/register', b),
  login: (b) => req('POST', '/api/auth/login', b),
  logout: () => req('POST', '/api/auth/logout'),
  logoutAll: () => req('POST', '/api/auth/logout-all'),
  me: () => req('GET', '/api/users/me'),
  updateMe: (b) => req('PATCH', '/api/users/me', b),
  searchUsers: (q) => req('GET', `/api/users/search?q=${encodeURIComponent(q)}`),
  presence: (ids) => req('GET', `/api/users/presence?ids=${ids.join(',')}`),
  providers: () => req('GET', '/api/auth/providers'),

  conversations: () => req('GET', '/api/conversations'),
  createConversation: (b) => req('POST', '/api/conversations', b),
  conversationSettings: (id, b) => req('PATCH', `/api/conversations/${id}/settings`, b),
  markRead: (id) => req('POST', `/api/conversations/${id}/read`),
  messages: (id, before) => req('GET', `/api/conversations/${id}/messages${before ? `?before=${encodeURIComponent(before)}` : ''}`),
  send: (id, b) => req('POST', `/api/conversations/${id}/messages`, b),
  edit: (id, content) => req('PATCH', `/api/messages/${id}`, { content }),
  recall: (id) => req('POST', `/api/messages/${id}/recall`),
  react: (id, emoji) => req('POST', `/api/messages/${id}/reactions`, { emoji }),

  stickerPacks: () => req('GET', '/api/sticker-packs'),
  createPack: (name) => req('POST', '/api/sticker-packs', { name }),
  addSticker: (packId, b) => req('POST', `/api/sticker-packs/${packId}/stickers`, b),
  deletePack: (packId) => req('DELETE', `/api/sticker-packs/${packId}`),

  avatars: () => req('GET', '/api/avatars'),
  importAvatar: (b) => req('POST', '/api/avatars', b),
  bots: () => req('GET', '/api/bots'),
  botModels: () => req('GET', '/api/bots/models'),
  createBot: (b) => req('POST', '/api/bots', b),
  updateBot: (id, b) => req('PUT', `/api/bots/${id}`, b),

  upload(kind, file) {
    const fd = new FormData();
    fd.append('kind', kind);
    fd.append('file', file);
    return req('POST', '/api/uploads', fd);
  },
  uploadBundle(files) {
    const fd = new FormData();
    for (const f of files) {
      fd.append('files', f);
      // webkitRelativePath = "MyModel/textures/a.png"; drop the top folder so paths are relative to the model root
      const rel = (f.webkitRelativePath || f.name).split('/').slice(f.webkitRelativePath ? 1 : 0).join('/');
      fd.append('paths', rel);
    }
    return req('POST', '/api/uploads/bundle', fd);
  },

  ttsProviders: () => req('GET', '/api/tts/providers'),
  ttsVoices: (provider) => req('GET', `/api/tts/voices?provider=${provider}`),
  tts: (b) => req('POST', '/api/tts', b, { raw: true }),

  // Moderation (ADMIN role only; the server enforces this, the UI just hides the menu)
  admin: {
    users: (q) => req('GET', `/api/admin/users${q ? `?q=${encodeURIComponent(q)}` : ''}`),
    disable: (id) => req('POST', `/api/admin/users/${id}/disable`),
    enable: (id) => req('POST', `/api/admin/users/${id}/enable`),
    setRole: (id, role) => req('POST', `/api/admin/users/${id}/role`, { role }),
    removeMessage: (id) => req('POST', `/api/admin/messages/${id}/remove`),
  },
};
