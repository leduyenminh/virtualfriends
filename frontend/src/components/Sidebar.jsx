import React, { useRef, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import { offlineDb } from '../offline/db.js';
import { listTime } from '../format.js';
import { readState, Ticks } from '../receipts.jsx';
import { THEMES } from './ThemePicker.jsx';
import Avatar from './Avatar.jsx';
import Menu from './Menu.jsx';
import BotStudio from './BotStudio.jsx';
import NewGroup from './NewGroup.jsx';
import AdminPanel from './AdminPanel.jsx';
import StickerManager from './stickers/StickerManager.jsx';

function preview(m) {
  if (!m) return 'No messages yet';
  if (m.recalled) return '🚫 This message was deleted';
  switch (m.type) {
    case 'STICKER': return '🧸 Sticker';
    case 'IMAGE': return '📷 Photo';
    case 'FILE': return `📄 ${m.content || 'File'}`;
    case 'AUDIO': return '🎤 Voice message';
    default: return m.content;
  }
}

/** Search-hit snippet from the offline index: \u0002…\u0003 mark the match. Rendered as text, never as HTML. */
function Snippet({ text }) {
  return text.split('\u0002').map((part, i) => {
    const [hit, rest] = i ? part.split('\u0003') : [null, part];
    return <React.Fragment key={i}>{hit && <mark>{hit}</mark>}{rest}</React.Fragment>;
  });
}

const FILTERS = [['all', 'All'], ['unread', 'Unread'], ['groups', 'Groups'], ['bots', 'AI friends']];

export default function Sidebar() {
  const { me, conversations, activeId, open, openAt, logout, logoutAll, typing, theme, setTheme, offline, reconnect } = useStore();
  const [q, setQ] = useState('');
  const [results, setResults] = useState([]);
  const [hits, setHits] = useState([]); // messages saved on this device
  const [filter, setFilter] = useState('all');
  const [modal, setModal] = useState(null);
  const latestQ = useRef('');

  async function search(v) {
    setQ(v);
    latestQ.current = v;
    const t = v.trim();
    const [people, found] = await Promise.all([
      t.length >= 2 && !offline ? api.searchUsers(t).catch(() => []) : [],
      t.length >= 3 ? offlineDb.search(t) : null,
    ]);
    if (latestQ.current !== v) return; // a newer keystroke is already searching
    setResults(people.filter((u) => u.id !== me?.id));
    setHits(found || []);
  }

  async function startDirect(user) {
    const c = await api.createConversation({ type: 'DIRECT', userIds: [user.id] });
    useStore.setState((s) => ({ conversations: s.conversations.some((x) => x.id === c.id) ? s.conversations : [c, ...s.conversations] }));
    search('');
    open(c.id);
  }

  function signOut() {
    if (confirm('Log out of VirtualFriends on this device?')) logout();
  }

  function signOutEverywhere() {
    if (confirm('Log out on ALL your devices and browsers?')) logoutAll();
  }

  const text = q.trim().toLowerCase();
  const filtered = conversations.filter((c) => {
    if (text && !(c.title || '').toLowerCase().includes(text)) return false;
    if (filter === 'unread') return c.unread > 0;
    if (filter === 'groups') return c.type === 'GROUP';
    if (filter === 'bots') return c.type === 'BOT';
    return true;
  });

  return (
    <aside className="window">
      <div className="titlebar">
        <Avatar name={me?.displayName} url={me?.avatarUrl} size="sm" />
        <h2 title={me ? `${me.displayName} (@${me.username})` : ''}>Chats</h2>
        <Menu icon="＋" title="New chat">
          <button role="menuitem" onClick={() => setModal('bot')}>🤖 New AI friend</button>
          <button role="menuitem" onClick={() => setModal('group')}>👥 New group</button>
          <button role="menuitem" onClick={() => document.getElementById('chat-search')?.focus()}>💬 New chat with a person</button>
        </Menu>
        <Menu icon="⋮" title="Menu">
          <div className="label">Signed in as @{me?.username}</div>
          <button role="menuitem" onClick={() => setModal('stickers')}>🧸 Sticker packs</button>
          {me?.role === 'ADMIN' && <button role="menuitem" onClick={() => setModal('admin')}>🛡 Admin: users</button>}
          <div className="sep" />
          <div className="label">Theme</div>
          {THEMES.map((t) => (
            <button key={t.id} role="menuitemradio" aria-checked={theme === t.id} onClick={() => setTheme(t.id)}>
              <span style={{ width: 14 }}>{theme === t.id ? '●' : '○'}</span>{t.label}
            </button>
          ))}
          <div className="sep" />
          <button role="menuitem" className="danger" onClick={signOut}>⎋ Log out</button>
          <button role="menuitem" className="danger" onClick={signOutEverywhere}>⎋ Log out of all devices</button>
        </Menu>
      </div>

      {offline && (
        <div className="offline-bar" role="status">
          <span>Offline · showing chats saved on this device</span>
          <button type="button" className="btn" onClick={reconnect}>Retry</button>
        </div>
      )}

      <div className="search-row">
        <label className="search">
          <span className="muted" aria-hidden="true">🔍</span>
          <input id="chat-search" placeholder="Search chats, messages, or people by username" value={q} onChange={(e) => search(e.target.value)} />
          {q && <button type="button" className="icon-btn" style={{ width: 24, height: 24, fontSize: 13 }} onClick={() => search('')} aria-label="Clear search">✕</button>}
        </label>
      </div>
      <div className="chips" role="tablist" aria-label="Filter chats">
        {FILTERS.map(([id, label]) => (
          <button key={id} role="tab" aria-selected={filter === id} className={filter === id ? 'on' : ''} onClick={() => setFilter(id)}>{label}</button>
        ))}
      </div>

      <div className="conv-list">
        {results.length > 0 && <div className="section-label">People</div>}
        {results.map((u) => (
          <div key={u.id} className="conv" onClick={() => startDirect(u)}>
            <Avatar name={u.displayName} url={u.avatarUrl} />
            <div className="meta">
              <div className="row1"><span className="name">{u.displayName}</span></div>
              <div className="row2"><span className="preview">@{u.username} · tap to chat</span></div>
            </div>
          </div>
        ))}
        {results.length > 0 && filtered.length > 0 && <div className="section-label">Chats</div>}
        {filtered.map((c) => {
          const other = c.type === 'DIRECT' ? c.members.find((u) => u.id !== me?.id) : null;
          const isTyping = Object.keys(typing[c.id] || {}).length > 0;
          const last = c.lastMessage;
          return (
            <div key={c.id} className={`conv ${c.id === activeId ? 'active' : ''}`} onClick={() => open(c.id)}>
              <Avatar name={c.title} url={other?.avatarUrl} />
              <div className="meta">
                <div className="row1">
                  <span className="name">{c.type === 'BOT' ? '🤖 ' : ''}{c.title}</span>
                  <span className={`when ${c.unread > 0 && !c.muted ? 'unread' : ''}`}>{listTime(last?.createdAt ?? c.updatedAt)}</span>
                </div>
                <div className="row2">
                  {isTyping ? <span className="preview" style={{ color: 'var(--accent)' }}>typing…</span> : (
                    <span className="preview">
                      <Ticks state={readState(c, last, me?.id)} />
                      {c.type === 'GROUP' && last && !last.fromBot && last.senderId !== me?.id
                        ? `${c.members.find((u) => u.id === last.senderId)?.displayName?.split(' ')[0] ?? ''}: ` : ''}
                      {preview(last)}
                    </span>
                  )}
                  {c.muted && <span className="muted" title="Muted">🔕</span>}
                  {c.pinned && <span className="muted" title="Pinned">📌</span>}
                  {c.unread > 0 && <span className="badge" style={c.muted ? { background: 'var(--muted)' } : undefined}>{c.unread}</span>}
                </div>
              </div>
            </div>
          );
        })}
        {hits.length > 0 && <div className="section-label">Messages</div>}
        {hits.map(({ message: m, snippet }) => {
          const c = conversations.find((x) => x.id === m.conversationId);
          if (!c) return null;
          const other = c.type === 'DIRECT' ? c.members.find((u) => u.id !== me?.id) : null;
          return (
            <div key={m.id} className="conv" onClick={() => openAt(c.id, m.id)}>
              <Avatar name={c.title} url={other?.avatarUrl} />
              <div className="meta">
                <div className="row1">
                  <span className="name">{c.type === 'BOT' ? '🤖 ' : ''}{c.title}</span>
                  <span className="when">{listTime(m.createdAt)}</span>
                </div>
                <div className="row2"><span className="preview"><Snippet text={snippet} /></span></div>
              </div>
            </div>
          );
        })}
        {!filtered.length && !results.length && !hits.length && (
          <div className="muted" style={{ padding: 24, textAlign: 'center' }}>
            {q ? 'No chats, messages or people match. Usernames need at least 2 letters, messages 3.' : 'No chats here yet.'}
          </div>
        )}
      </div>
      {modal === 'bot' && <BotStudio onClose={() => setModal(null)} />}
      {modal === 'group' && <NewGroup onClose={() => setModal(null)} />}
      {modal === 'stickers' && <StickerManager onClose={() => setModal(null)} />}
      {modal === 'admin' && <AdminPanel onClose={() => setModal(null)} />}
    </aside>
  );
}
