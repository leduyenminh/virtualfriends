import { create } from 'zustand';
import { api, clearSession, setNotice } from './api.js';

/** Tell other open tabs to drop their session too (they listen for this key in App.jsx). */
function broadcastSignOut() {
  try { localStorage.setItem('vchat.signout', String(Date.now())); } catch { /* ignore */ }
}
import { speak } from './tts/speech.js';

const savedTheme = (() => { try { return localStorage.getItem('vchat.theme'); } catch { return null; } })();

export const useStore = create((set, get) => ({
  me: null,
  theme: savedTheme || 'light',
  conversations: [],
  activeId: null,
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
    set({ me, conversations, packs, bots, avatars });
    if (!savedTheme && me.theme) get().setTheme(me.theme);
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

  async open(id) {
    set({ activeId: id, replyTo: null });
    if (!get().messages[id]) {
      const list = await api.messages(id);
      set((s) => ({ messages: { ...s.messages, [id]: list } }));
    }
    api.markRead(id).catch(() => {});
    set((s) => ({ conversations: s.conversations.map((c) => (c.id === id ? { ...c, unread: 0 } : c)) }));
  },

  async loadOlder(id) {
    const list = get().messages[id] || [];
    if (!list.length) return false;
    const older = await api.messages(id, list[0].createdAt);
    set((s) => ({ messages: { ...s.messages, [id]: [...older, ...(s.messages[id] || [])] } }));
    return older.length > 0;
  },

  upsertMessage(m) {
    set((s) => {
      const list = s.messages[m.conversationId];
      if (!list) return {};
      const i = list.findIndex((x) => x.id === m.id);
      const next = i >= 0 ? list.map((x) => (x.id === m.id ? m : x)) : [...list, m];
      return { messages: { ...s.messages, [m.conversationId]: next } };
    });
  },

  // ---- realtime ----
  handleEvent(e) {
    const s = get();
    switch (e.type) {
      case 'message.new': {
        s.upsertMessage(e.payload);
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
