import { create } from 'zustand';
import { api, clearSession, setNotice } from './api.js';
import { offlineDb } from './offline/db.js';
import { PAGE } from './offline/config.js';

/** Tell other open tabs to drop their session too (they listen for this key in App.jsx). */
function broadcastSignOut() {
  try { localStorage.setItem('vchat.signout', String(Date.now())); } catch { /* ignore */ }
}
import { speak } from './tts/speech.js';

const savedTheme = (() => { try { return localStorage.getItem('vchat.theme'); } catch { return null; } })();

const time = (m) => new Date(m.createdAt).getTime();

/**
 * Merge a newer, unbroken stretch of messages (the API's latest page, or a stretch from the offline copy) into what is
 * on screen: keep older messages if they connect to it, and messages that arrived while it was loading.
 */
function mergeNewer(stretch, current = []) {
  if (!stretch.length || !current.length) return stretch.length ? stretch : current;
  const ids = new Set(stretch.map((m) => m.id));
  const first = time(stretch[0]);
  const last = time(stretch[stretch.length - 1]);
  const connected = stretch.length < PAGE || current.some((m) => ids.has(m.id));
  const older = connected ? current.filter((m) => time(m) < first && !ids.has(m.id)) : [];
  const newer = current.filter((m) => time(m) > last && !ids.has(m.id));
  return [...older, ...stretch, ...newer];
}

const SYNC_CHATS = 20; // recent chats whose latest messages are saved for offline use at startup
let syncing = false;

export const useStore = create((set, get) => ({
  me: null,
  theme: savedTheme || 'light',
  offline: false,        // server unreachable: showing the copy saved on this device (set by App.jsx)
  reconnect: () => {},   // try the server again (set by App.jsx)
  conversations: [],
  activeId: null,
  focusId: null,         // message to scroll to and highlight (search hit)
  messages: {},          // conversationId -> MessageDto[]
  typing: {},            // conversationId -> { [userId|'bot']: true }
  packs: [],
  bots: [],
  avatars: [],
  botFace: { emotion: 'neutral', speaking: false },
  autoSpeak: true,
  showAvatar: true,     // avatar drawer in AI-friend chats
  replyTo: null,

  setTheme(theme) {
    set({ theme });
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('vchat.theme', theme); } catch { /* ignore */ }
    if (get().me) api.updateMe({ theme }).catch(() => {});
  },

  async bootstrap() {
    const [me, conversations, packs, bots, avatars] = await Promise.all([
      api.me(), api.conversations(), api.stickerPacks(), api.bots(), api.avatars(),
    ]);
    // After a reconnect, chats other than the open one reload when they are next opened.
    set((s) => ({ me, conversations, packs, bots, avatars, messages: s.messages[s.activeId] ? { [s.activeId]: s.messages[s.activeId] } : {} }));
    if (!savedTheme && me.theme) get().setTheme(me.theme);
    offlineDb.use(me);
    if (get().activeId) get().open(get().activeId, true).catch(() => {});
    get().syncRecent();
  },

  /** No server: load the chat list saved on this device. False if there is none. */
  async bootstrapOffline() {
    if (!(await offlineDb.ready(3000))) return false;
    const saved = await offlineDb.snapshot();
    if (!saved?.me) return false;
    const { me, conversations = [], packs = [], bots = [], avatars = [] } = saved;
    set({ me, conversations, packs, bots, avatars });
    return true;
  },

  /** Save the latest messages of recent chats that changed since they were last saved (best effort). */
  async syncRecent() {
    if (syncing) return;
    syncing = true;
    try {
      const recent = get().conversations.filter((c) => c.lastMessage).slice(0, SYNC_CHATS);
      const saved = await offlineDb.has(recent.map((c) => c.lastMessage.id));
      if (!saved) return; // cache not open (yet)
      const have = new Set(saved);
      for (const c of recent) {
        if (get().offline) break;
        if (have.has(c.lastMessage.id) || get().messages[c.id]) continue;
        await offlineDb.savePage(c.id, await api.messages(c.id), true);
      }
    } catch { /* try again next start */ } finally {
      syncing = false;
    }
  },

  /** This device: server revokes the refresh cookie + current access token, then we forget everything. */
  async logout() {
    await api.logout().catch(() => {});
    clearSession();
    broadcastSignOut();
    setNotice('You signed out. Sign in again, or create a new account.');
    location.reload();
  },

  /** Every device and tab: server revokes all sessions and tells open WebSockets to sign out. */
  async logoutAll() {
    await api.logoutAll().catch(() => {});
    clearSession();
    broadcastSignOut();
    setNotice('You signed out on all devices.');
    location.reload();
  },

  /** Show a chat: saved messages first (instant, works offline), then the latest page from the server. */
  async open(id, refresh = false) {
    set({ activeId: id, replyTo: null });
    if (!get().messages[id]) {
      const saved = await offlineDb.messages(id);
      if (saved?.length && !get().messages[id]) set((s) => ({ messages: { ...s.messages, [id]: saved } }));
      refresh = true;
    }
    if (refresh && !get().offline) {
      try {
        const page = await api.messages(id);
        set((s) => ({ messages: { ...s.messages, [id]: mergeNewer(page, s.messages[id]) } }));
        offlineDb.savePage(id, page, true);
      } catch (e) {
        if (!e.offline) throw e;
      }
    }
    if (!get().offline) api.markRead(id).catch(() => {});
    set((s) => ({ conversations: s.conversations.map((c) => (c.id === id ? { ...c, unread: 0 } : c)) }));
  },

  /** Open a chat at a message (search hit), loading the saved stretch from there if it isn't on screen yet. */
  async openAt(conversationId, messageId) {
    await get().open(conversationId);
    if (!get().messages[conversationId]?.some((m) => m.id === messageId)) {
      const stretch = await offlineDb.around(conversationId, messageId);
      if (stretch) set((s) => ({ messages: { ...s.messages, [conversationId]: mergeNewer(stretch, s.messages[conversationId]) } }));
    }
    set({ focusId: messageId });
  },

  async loadOlder(id) {
    const list = get().messages[id] || [];
    if (!list.length) return false;
    let older;
    if (get().offline) {
      older = (await offlineDb.messages(id, list[0].createdAt)) || [];
    } else {
      older = await api.messages(id, list[0].createdAt);
      offlineDb.savePage(id, older);
    }
    set((s) => ({ messages: { ...s.messages, [id]: [...older, ...(s.messages[id] || [])] } }));
    return older.length > 0;
  },

  /** `after`: the chat's previous last message, so the offline copy can extend history that isn't on screen. */
  upsertMessage(m, after) {
    const loaded = !!get().messages[m.conversationId];
    set((s) => {
      const list = s.messages[m.conversationId];
      if (!list) return {};
      const i = list.findIndex((x) => x.id === m.id);
      const next = i >= 0 ? list.map((x) => (x.id === m.id ? m : x)) : [...list, m];
      return { messages: { ...s.messages, [m.conversationId]: next } };
    });
    offlineDb.putMessage(m, { insert: loaded, after });
  },

  // ---- realtime ----
  handleEvent(e) {
    const s = get();
    switch (e.type) {
      case 'message.new': {
        s.upsertMessage(e.payload, s.conversations.find((c) => c.id === e.conversationId)?.lastMessage?.id);
        const active = s.activeId === e.conversationId;
        const mine = e.payload.senderId === s.me?.id;
        set((st) => ({
          conversations: st.conversations
            .map((c) => (c.id === e.conversationId
              ? { ...c, lastMessage: e.payload, updatedAt: e.payload.createdAt, unread: active || mine ? 0 : c.unread + 1 }
              : c))
            .sort((a, b) => (b.pinned - a.pinned) || (new Date(b.updatedAt) - new Date(a.updatedAt))),
        }));
        if (active && !mine) api.markRead(e.conversationId).catch(() => {});
        break;
      }
      case 'message.updated':
        s.upsertMessage(e.payload);
        break;
      case 'conversation.new':
        if (!s.conversations.some((c) => c.id === e.payload.id)) {
          api.conversations().then((conversations) => set({ conversations }));
        }
        break;
      case 'read':
        set((st) => ({
          conversations: st.conversations.map((c) => (c.id === e.conversationId
            ? { ...c, readBy: { ...(c.readBy || {}), [e.payload.userId]: e.payload.at } }
            : c)),
        }));
        break;
      case 'typing': {
        const who = e.payload.userId ?? 'bot';
        if (who === s.me?.id) break;
        set((st) => {
          const cur = { ...(st.typing[e.conversationId] || {}) };
          if (e.payload.typing) cur[who] = true; else delete cur[who];
          return { typing: { ...st.typing, [e.conversationId]: cur } };
        });
        break;
      }
      case 'session.revoked':
        clearSession();
        setNotice(e.payload?.reason || 'Your session ended. Please sign in again.');
        location.reload();
        break;
      case 'bot.speak':
        set({ botFace: { emotion: e.payload.emotion, speaking: false } });
        if (s.autoSpeak && s.activeId === e.conversationId) {
          set((st) => ({ botFace: { ...st.botFace, speaking: true } }));
          speak(e.payload).finally(() => set((st) => ({ botFace: { ...st.botFace, speaking: false } })));
        }
        break;
      default:
    }
  },
}));

// ---- offline copy of the chat list (messages are saved as they load, see open/loadOlder/upsertMessage) ----
const PERSISTED = ['me', 'conversations', 'packs', 'bots', 'avatars'];
let saveTimer = null;

function persist() {
  const s = useStore.getState();
  if (s.me) offlineDb.saveState(Object.fromEntries(PERSISTED.map((k) => [k, s[k]])));
}

useStore.subscribe((s, prev) => {
  if (PERSISTED.some((k) => s[k] !== prev[k])) {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(persist, 500);
  }
});

offlineDb.onReady(() => {
  persist();
  useStore.getState().syncRecent();
});
