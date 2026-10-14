import React, { useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import Avatar from './Avatar.jsx';
import Modal from './Modal.jsx';

export default function NewGroup({ onClose }) {
  const me = useStore((s) => s.me);
  const [title, setTitle] = useState('');
  const [q, setQ] = useState('');
  const [results, setResults] = useState([]);
  const [picked, setPicked] = useState([]);
  const [error, setError] = useState(null);

  async function search(v) {
    setQ(v);
    setResults(v.trim().length >= 2 ? (await api.searchUsers(v.trim())).filter((u) => u.id !== me?.id) : []);
  }

  async function create() {
    setError(null);
    try {
      const c = await api.createConversation({ type: 'GROUP', title: title.trim() || 'New group', userIds: picked.map((u) => u.id) });
      useStore.setState((s) => ({ conversations: [c, ...s.conversations.filter((x) => x.id !== c.id)] }));
      useStore.getState().open(c.id);
      onClose();
    } catch (e) { setError(e.message); }
  }

  return (
    <Modal title="New group" onClose={onClose}>
      <div className="field"><label htmlFor="gt">Group name</label>
        <input id="gt" className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Đồ án nhóm 5" autoFocus />
      </div>
      <div className="field"><label htmlFor="gq">Add members</label>
        <input id="gq" className="input" value={q} onChange={(e) => search(e.target.value)} placeholder="Search by username or name" />
      </div>
      {picked.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {picked.map((u) => (
            <span key={u.id} className="pill">{u.displayName}
              <button type="button" aria-label={`Remove ${u.displayName}`} onClick={() => setPicked(picked.filter((x) => x.id !== u.id))}>✕</button>
            </span>
          ))}
        </div>
      )}
      <div>
        {results.filter((u) => !picked.some((p) => p.id === u.id)).map((u) => (
          <div key={u.id} className="conv" style={{ height: 56 }} onClick={() => { setPicked([...picked, u]); setQ(''); setResults([]); }}>
            <Avatar name={u.displayName} url={u.avatarUrl} size="sm" />
            <div className="meta"><div className="row1"><span className="name" style={{ fontSize: 15 }}>{u.displayName}</span></div>
              <div className="row2"><span className="preview">@{u.username}</span></div></div>
          </div>
        ))}
      </div>
      {error && <div className="error">{error}</div>}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={!picked.length} onClick={create}>Create group ({picked.length + 1})</button>
      </div>
    </Modal>
  );
}
