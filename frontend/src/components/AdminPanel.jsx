import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import { listTime } from '../format.js';
import Avatar from './Avatar.jsx';
import Modal from './Modal.jsx';

/** Moderation for ADMIN accounts. The server enforces the role on every call; this screen is only a convenience. */
export default function AdminPanel({ onClose }) {
  const me = useStore((s) => s.me);
  const [q, setQ] = useState('');
  const [users, setUsers] = useState([]);
  const [error, setError] = useState(null);

  const load = (term) => api.admin.users(term).then(setUsers).catch((e) => setError(e.message));
  useEffect(() => { load(''); }, []);

  async function act(fn, confirmText) {
    if (confirmText && !confirm(confirmText)) return;
    setError(null);
    try {
      const updated = await fn();
      setUsers((list) => list.map((u) => (u.id === updated.id ? updated : u)));
    } catch (e) { setError(e.message); }
  }

  return (
    <Modal title="Admin · users" onClose={onClose} width={640}>
      <input className="input" placeholder="Search username or name" value={q}
        onChange={(e) => { setQ(e.target.value); load(e.target.value); }} />
      {error && <div className="error">{error}</div>}
      <div>
        {users.map((u) => (
          <div key={u.id} className="conv" style={{ height: 64, cursor: 'default' }}>
            <Avatar name={u.displayName} size="sm" />
            <div className="meta">
              <div className="row1">
                <span className="name" style={{ fontSize: 15 }}>
                  {u.displayName} <span className="muted">@{u.username}</span>
                </span>
                {u.role === 'ADMIN' && <span className="pill">🛡 admin</span>}
                {u.disabled && <span className="pill" style={{ color: 'var(--danger)' }}>disabled</span>}
              </div>
              <div className="row2">
                <span className="preview">joined {listTime(u.createdAt)}{u.lastSeenAt ? ` · seen ${listTime(u.lastSeenAt)}` : ''}</span>
                {u.id !== me?.id && (
                  <>
                    <button className="btn" style={{ padding: '3px 10px', fontSize: 12 }}
                      onClick={() => act(() => api.admin.setRole(u.id, u.role === 'ADMIN' ? 'USER' : 'ADMIN'),
                        `${u.role === 'ADMIN' ? 'Remove admin from' : 'Make admin:'} @${u.username}?`)}>
                      {u.role === 'ADMIN' ? 'Remove admin' : 'Make admin'}
                    </button>
                    {u.disabled
                      ? <button className="btn" style={{ padding: '3px 10px', fontSize: 12 }} onClick={() => act(() => api.admin.enable(u.id))}>Enable</button>
                      : <button className="btn danger" style={{ padding: '3px 10px', fontSize: 12 }}
                          onClick={() => act(() => api.admin.disable(u.id), `Disable @${u.username}? They are signed out everywhere immediately.`)}>Disable</button>}
                  </>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
    </Modal>
  );
}
