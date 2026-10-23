import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { sendTyping } from '../realtime.js';
import { useStore } from '../store.js';
import StickerPicker from './stickers/StickerPicker.jsx';

const EMOJI = ['😀', '😂', '🥰', '😎', '🤔', '😭', '😡', '👍', '🙏', '🎉', '❤️', '🔥', '✨', '🌸', '🍜', '☕'];

export default function Composer({ conversation }) {
  const [text, setText] = useState('');
  const [panel, setPanel] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [rec, setRec] = useState(null); // { recorder, started }
  const [elapsed, setElapsed] = useState(0);
  const fileRef = useRef(null);
  const typingTimer = useRef(null);
  const { replyTo, upsertMessage, offline } = useStore();

  useEffect(() => {
    if (!rec) return undefined;
    const t = setInterval(() => setElapsed(Math.floor((Date.now() - rec.started) / 1000)), 500);
    return () => clearInterval(t);
  }, [rec]);

  if (offline) {
    return <div className="composer offline-note" role="status">You’re offline. You can read saved messages; sending resumes when you’re back online.</div>;
  }

  function onChange(e) {
    setText(e.target.value);
    if (!typingTimer.current) sendTyping(conversation.id, true);
    clearTimeout(typingTimer.current);
    typingTimer.current = setTimeout(() => { sendTyping(conversation.id, false); typingTimer.current = null; }, 2000);
  }

  async function send(body) {
    setError(null);
    try {
      const msg = await api.send(conversation.id, { ...body, replyToId: replyTo?.id });
      upsertMessage(msg);
      useStore.setState({ replyTo: null });
    } catch (e) { setError(e.message); }
  }

  async function submit(e) {
    e?.preventDefault();
    if (!text.trim()) return;
    const content = text;
    setText('');
    clearTimeout(typingTimer.current); typingTimer.current = null;
    sendTyping(conversation.id, false);
    await send({ type: 'TEXT', content });
  }

  async function upload(file, type) {
    setBusy(true);
    try {
      const up = await api.upload('ATTACHMENT', file);
      await send({ type, attachmentUrl: up.url, content: type === 'FILE' ? file.name : null });
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }

  async function attach(e) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    const type = file.type.startsWith('image/') ? 'IMAGE' : file.type.startsWith('audio/') ? 'AUDIO' : 'FILE';
    upload(file, type);
  }

  /** Voice notes: record with MediaRecorder, upload as an attachment, send as an AUDIO message. */
  async function startRecording() {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const recorder = new MediaRecorder(stream);
      const chunks = [];
      recorder.ondataavailable = (ev) => chunks.push(ev.data);
      recorder.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        if (recorder.cancelled || !chunks.length) return;
        const type = recorder.mimeType || 'audio/webm';
        upload(new File(chunks, `voice-${Date.now()}.${type.includes('ogg') ? 'ogg' : 'webm'}`, { type }), 'AUDIO');
      };
      recorder.start();
      setElapsed(0);
      setRec({ recorder, started: Date.now() });
    } catch {
      setError('Microphone not available or permission denied.');
    }
  }
  function stopRecording(cancel) {
    if (!rec) return;
    rec.recorder.cancelled = cancel;
    rec.recorder.stop();
    setRec(null);
  }

  return (
    <>
      {replyTo && (
        <div className="reply-bar">
          <div className="quote">{replyTo.content?.slice(0, 120) || '📎 Attachment'}</div>
          <button className="icon-btn" onClick={() => useStore.setState({ replyTo: null })} aria-label="Cancel reply">✕</button>
        </div>
      )}
      {error && <div className="reply-bar error" role="alert" style={{ paddingBottom: 4 }}>{error}</div>}
      <form className="composer" onSubmit={submit}>
        {panel === 'emoji' && (
          <div className="popover" style={{ width: 290 }}>
            <div className="sticker-grid" style={{ gridTemplateColumns: 'repeat(8, 1fr)', fontSize: 22 }}>
              {EMOJI.map((e) => <span key={e} style={{ cursor: 'pointer' }} onClick={() => setText(text + e)}>{e}</span>)}
            </div>
          </div>
        )}
        {panel === 'sticker' && (
          <StickerPicker onPick={(s) => { setPanel(null); send({ type: 'STICKER', stickerId: s.id }); }} />
        )}
        {rec ? (
          <>
            <button type="button" className="icon-btn" onClick={() => stopRecording(true)} aria-label="Discard recording">🗑</button>
            <div style={{ flex: 1, color: 'var(--danger)' }}>● Recording {Math.floor(elapsed / 60)}:{String(elapsed % 60).padStart(2, '0')}</div>
            <button type="button" className="send-btn rec" onClick={() => stopRecording(false)} aria-label="Send voice message">➤</button>
          </>
        ) : (
          <>
            <button type="button" className={`icon-btn ${panel === 'emoji' ? 'on' : ''}`} title="Emoji" onClick={() => setPanel(panel === 'emoji' ? null : 'emoji')}>😊</button>
            <button type="button" className={`icon-btn ${panel === 'sticker' ? 'on' : ''}`} title="Stickers" onClick={() => setPanel(panel === 'sticker' ? null : 'sticker')}>🧸</button>
            <button type="button" className="icon-btn" title="Attach photo or file" disabled={busy} onClick={() => fileRef.current.click()}>📎</button>
            <input ref={fileRef} type="file" hidden onChange={attach} />
            <textarea
              rows={1} value={text} onChange={onChange} placeholder="Type a message" aria-label="Message"
              onFocus={() => setPanel(null)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); } }}
            />
            {text.trim()
              ? <button className="send-btn" type="submit" aria-label="Send">➤</button>
              : <button className="send-btn" type="button" onClick={startRecording} aria-label="Record voice message" disabled={busy}>🎤</button>}
          </>
        )}
      </form>
    </>
  );
}
