import React, { useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
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

const FILTERS = [['all', 'All'], ['unread', 'Unread'], ['groups', 'Groups'], ['bots', 'AI friends']];

export default function Sidebar() {
  const { me, conversations, activeId, open, logout, logoutAll, typing, theme, setTheme } = useStore();
  const [q, setQ] = useState('');
  const [results, setResults] = useState([]);
  const [filter, setFilter] = useState('all');
  const [modal, setModal] = useState(null);

  async function search(v) {
    setQ(v);
    setResults(v.trim().length >= 2 ? (await api.searchUsers(v.trim())).filter((u) => u.id !== me?.id) : []);
  }

  async function startDirect(user) {
    const c = await api.createConversation({ type: 'DIRECT', userIds: [user.id] });
    useStore.setState((s) => ({ conversations: s.conversations.some((x) => x.id === c.id) ? s.conversations : [c, ...s.conversations] }));
    setQ(''); setResults([]);
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

      <div className="search-row">
        <label className="search">
          <span className="muted" aria-hidden="true">🔍</span>
          <input id="chat-search" placeholder="Search chats or find people by username" value={q} onChange={(e) => search(e.target.value)} />
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
        {!filtered.length && !results.length && (
          <div className="muted" style={{ padding: 24, textAlign: 'center' }}>
            {q ? 'No chats or people match. Usernames need at least 2 letters.' : 'No chats here yet.'}
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
