import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import { speak } from '../tts/speech.js';
import { clock } from '../format.js';
import { Ticks } from '../receipts.jsx';

const REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '🙏'];

export default function MessageBubble({ m, mine, first, status, sender, showSender, replyTo, bot }) {
  const [menu, setMenu] = useState(false);
  const ref = useRef(null);
  const canChange = mine && !m.recalled && Date.now() - new Date(m.createdAt) < 24 * 3600e3;
  const isAdmin = useStore((s) => s.me?.role === 'ADMIN');

  useEffect(() => {
    if (!menu) return undefined;
    const close = (e) => { if (!ref.current?.contains(e.target)) setMenu(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [menu]);

  const upsert = (msg) => useStore.getState().upsertMessage(msg);
  async function edit() {
    const next = prompt('Edit message', m.content);
    if (next && next !== m.content) upsert(await api.edit(m.id, next));
  }
  async function recall() {
    if (confirm('Delete this message for everyone?')) upsert(await api.recall(m.id));
  }
  async function moderate() {
    if (confirm('Remove this message for everyone (moderator action)?')) upsert(await api.admin.removeMessage(m.id));
  }
  async function react(e) { setMenu(false); upsert(await api.react(m.id, e)); }
  function replay() {
    useStore.setState((s) => ({ botFace: { ...s.botFace, speaking: true } }));
    speak({ text: m.content, language: bot.language, ttsProvider: bot.ttsProvider, voiceId: bot.voiceId })
      .finally(() => useStore.setState((s) => ({ botFace: { ...s.botFace, speaking: false } })));
  }

  const meta = (
    <span className="meta">
      {m.editedAt && !m.recalled && <span>edited</span>}
      {clock(m.createdAt)}
      <Ticks state={status} />
    </span>
  );

  let body;
  const tail = first ? 'tail' : '';
  if (m.recalled) {
    body = <div className={`bubble recalled ${tail}`}>🚫 {mine ? 'You deleted this message' : 'This message was deleted'}{meta}</div>;
  } else if (m.type === 'STICKER') {
    body = <div className="bubble sticker"><img src={m.sticker?.url} alt={m.sticker?.name} />{meta}</div>;
  } else {
    let content;
    if (m.type === 'IMAGE') content = <><img className="attach" src={m.attachmentUrl} alt="" />{m.content}</>;
    else if (m.type === 'FILE') content = <>📄 <a href={m.attachmentUrl} target="_blank" rel="noreferrer">{m.content || 'File'}</a></>;
    else if (m.type === 'AUDIO') content = <audio controls src={m.attachmentUrl} preload="metadata" />;
    else content = m.content;
    body = (
      <div className={`bubble ${tail}`}>
        {showSender && <div className="sender">{sender?.displayName}</div>}
        {replyTo && <div className="quote">{replyTo.recalled ? 'Deleted message' : replyTo.content || '📎 Attachment'}</div>}
        {content}
        {meta}
      </div>
    );
  }

  const reactions = Object.entries(m.reactions || {});
  return (
    <div className={`row ${mine ? 'me' : ''} ${first ? 'first' : ''}`}>
      <div className="msg-col" ref={ref}>
        {body}
        {!m.recalled && (
          <button type="button" className={`bubble-menu-btn ${menu ? 'open' : ''}`} aria-label="Message options" onClick={() => setMenu(!menu)}>⌄</button>
        )}
        {menu && (
          <div className={`menu ${mine ? '' : 'left'}`} style={{ top: 26 }} role="menu">
            <div className="react-bar">{REACTIONS.map((e) => <button key={e} onClick={() => react(e)} aria-label={`React ${e}`}>{e}</button>)}</div>
            <button role="menuitem" onClick={() => { setMenu(false); useStore.setState({ replyTo: m }); }}>↩ Reply</button>
            {m.type === 'TEXT' && <button role="menuitem" onClick={() => { setMenu(false); navigator.clipboard?.writeText(m.content); }}>⧉ Copy</button>}
            {m.fromBot && m.type === 'TEXT' && bot && <button role="menuitem" onClick={() => { setMenu(false); replay(); }}>🔊 Play voice</button>}
            {canChange && m.type === 'TEXT' && <button role="menuitem" onClick={() => { setMenu(false); edit(); }}>✏️ Edit</button>}
            {canChange && <button role="menuitem" className="danger" onClick={() => { setMenu(false); recall(); }}>🗑 Delete for everyone</button>}
            {isAdmin && !mine && <button role="menuitem" className="danger" onClick={() => { setMenu(false); moderate(); }}>🛡 Remove (moderator)</button>}
          </div>
        )}
        {reactions.length > 0 && (
          <div className="reactions">
            {reactions.map(([e, users]) => <span key={e} onClick={() => react(e)} title="Tap to toggle">{e} {users.length > 1 ? users.length : ''}</span>)}
          </div>
        )}
      </div>
    </div>
  );
}
