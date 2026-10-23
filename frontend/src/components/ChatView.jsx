import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import { dayLabel, isSameDay, lastSeen } from '../format.js';
import { readState } from '../receipts.jsx';
import Avatar from './Avatar.jsx';
import MessageBubble from './MessageBubble.jsx';
import Composer from './Composer.jsx';

const LANG = { vi: 'Tiếng Việt', en: 'English', zh: '中文', ja: '日本語', ko: '한국어', es: 'Español', ru: 'Русский' };

export default function ChatView({ conversation }) {
  const { messages, typing, me, loadOlder, showAvatar, offline, focusId } = useStore();
  const list = messages[conversation.id] || [];
  const scroller = useRef(null);
  const atBottom = useRef(true);
  const [flashId, setFlashId] = useState(null);
  const byId = Object.fromEntries(list.map((m) => [m.id, m]));
  const members = Object.fromEntries(conversation.members.map((u) => [u.id, u]));
  const typers = Object.keys(typing[conversation.id] || {});
  const other = conversation.type === 'DIRECT' ? conversation.members.find((u) => u.id !== me?.id) : null;
  const [presence, setPresence] = useState(null);

  useEffect(() => {
    if (!other) return undefined;
    let alive = true;
    const load = () => api.presence([other.id]).then((p) => alive && setPresence(p[other.id])).catch(() => {});
    load();
    const t = setInterval(load, 30000);
    return () => { alive = false; clearInterval(t); };
  }, [other?.id]);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && atBottom.current) el.scrollTop = el.scrollHeight;
  }, [list.length, list[list.length - 1]?.reactions]);

  // Jump to a search hit once it is on screen
  useEffect(() => {
    const el = focusId && scroller.current?.querySelector(`[data-mid="${focusId}"]`);
    if (!el) return;
    atBottom.current = false;
    el.scrollIntoView({ block: 'center' });
    setFlashId(focusId);
    useStore.setState({ focusId: null });
  }, [focusId, list.length]);

  useEffect(() => {
    if (!flashId) return undefined;
    const t = setTimeout(() => setFlashId(null), 1600);
    return () => clearTimeout(t);
  }, [flashId]);

  async function onScroll(e) {
    const el = e.currentTarget;
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    if (el.scrollTop === 0 && list.length >= 50) {
      const h = el.scrollHeight;
      if (await loadOlder(conversation.id)) requestAnimationFrame(() => { el.scrollTop = el.scrollHeight - h; });
    }
  }

  const toggle = (field) => api.conversationSettings(conversation.id, { [field]: !conversation[field] }).then(() =>
    useStore.setState((s) => ({ conversations: s.conversations.map((c) => (c.id === conversation.id ? { ...c, [field]: !c[field] } : c)) })));

  let subtitle;
  let live = false;
  if (typers.length) {
    live = true;
    subtitle = conversation.type === 'GROUP'
      ? `${typers.map((id) => members[id]?.displayName?.split(' ')[0]).filter(Boolean).join(', ')} typing…`
      : 'typing…';
  } else if (conversation.type === 'DIRECT') {
    live = !!presence?.online;
    subtitle = presence ? (presence.online ? 'online' : lastSeen(presence.lastSeenAt)) : '';
  } else if (conversation.type === 'GROUP') {
    subtitle = conversation.members.map((u) => (u.id === me?.id ? 'You' : u.displayName)).join(', ');
  } else {
    subtitle = `AI friend · ${LANG[conversation.bot?.language] ?? ''}`;
  }

  const lastBotAt = [...list].reverse().find((m) => m.fromBot)?.createdAt;

  return (
    <section className="window">
      <div className="titlebar">
        <button className="icon-btn mobile-back" onClick={() => useStore.setState({ activeId: null })} aria-label="Back to chats">←</button>
        <Avatar name={conversation.title} url={other?.avatarUrl} size="sm" />
        <div style={{ flex: 1, minWidth: 0 }}>
          <h2>{conversation.type === 'BOT' ? '🤖 ' : ''}{conversation.title}</h2>
          <span className={`sub ${live ? 'live' : ''}`}>{subtitle}</span>
        </div>
        {conversation.type === 'BOT' && (
          <button className={`icon-btn ${showAvatar ? 'on' : ''}`} title={showAvatar ? 'Hide avatar' : 'Show avatar'}
            onClick={() => useStore.setState({ showAvatar: !showAvatar })}>🎭</button>
        )}
        <button className={`icon-btn ${conversation.pinned ? 'on' : ''}`} title={conversation.pinned ? 'Unpin chat' : 'Pin chat'} disabled={offline} onClick={() => toggle('pinned')}>📌</button>
        <button className={`icon-btn ${conversation.muted ? 'on' : ''}`} title={conversation.muted ? 'Unmute' : 'Mute notifications'} disabled={offline} onClick={() => toggle('muted')}>{conversation.muted ? '🔕' : '🔔'}</button>
      </div>
      <div className="chat-body">
        <div className="messages" ref={scroller} onScroll={onScroll}>
          {list.map((m, i) => {
            const prev = list[i - 1];
            const newDay = !prev || !isSameDay(prev.createdAt, m.createdAt);
            const first = newDay || prev.senderId !== m.senderId || prev.fromBot !== m.fromBot || prev.type === 'SYSTEM';
            const mine = m.senderId === me?.id;
            return (
              <React.Fragment key={m.id}>
                {newDay && <div className="day-chip">{dayLabel(m.createdAt)}</div>}
                <MessageBubble
                  m={m}
                  mine={mine}
                  first={first}
                  status={mine ? readState(conversation, m, me?.id, !!lastBotAt && lastBotAt > m.createdAt) : null}
                  sender={m.fromBot ? { displayName: conversation.bot?.name } : members[m.senderId]}
                  showSender={conversation.type === 'GROUP' && first && !mine}
                  replyTo={m.replyToId ? byId[m.replyToId] : null}
                  bot={conversation.bot}
                  flash={m.id === flashId}
                />
              </React.Fragment>
            );
          })}
          {!list.length && (
            <div className="day-chip" style={{ textTransform: 'none', marginTop: 24 }}>
              {offline ? 'This chat isn’t saved on this device yet. It will be after you open it online.'
                : conversation.type === 'BOT' ? `Say hi to ${conversation.bot?.name}! Replies are voiced and animated.` : 'No messages yet. Say hello 👋'}
            </div>
          )}
        </div>
      </div>
      <Composer conversation={conversation} />
    </section>
  );
}
