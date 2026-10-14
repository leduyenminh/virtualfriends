import React, { useEffect, useState } from 'react';
import { onSessionLost, refreshSession, setNotice } from './api.js';
import { connectRealtime } from './realtime.js';
import { useStore } from './store.js';
import AuthScreen from './components/AuthScreen.jsx';
import Sidebar from './components/Sidebar.jsx';
import ChatView from './components/ChatView.jsx';
import AvatarPanel from './components/avatar/AvatarPanel.jsx';

/**
 * Google/Facebook sign-in lands on /#/oauth (or /#/oauth?error=...). The session itself arrived as the httpOnly
 * refresh cookie, so there is no token in the URL; we only surface errors and clean the address bar.
 */
(function consumeOAuthRedirect() {
  const h = location.hash;
  if (!h.startsWith('#/oauth')) return;
  const params = new URLSearchParams(h.includes('?') ? h.slice(h.indexOf('?') + 1) : '');
  if (params.get('error')) setNotice(params.get('error'));
  history.replaceState(null, '', location.pathname + location.search);
}());

function EmptyState() {
  return (
    <div className="window empty-state">
      <svg width="150" height="150" viewBox="0 0 200 220" aria-hidden="true">
        <g stroke="var(--accent)" strokeWidth="7" strokeLinecap="round" fill="none">
          <line x1="100" y1="92" x2="100" y2="160" /><line x1="100" y1="160" x2="80" y2="215" /><line x1="100" y1="160" x2="120" y2="215" />
          <line x1="100" y1="105" x2="48" y2="70" /><line x1="100" y1="105" x2="155" y2="75" />
        </g>
        <circle cx="100" cy="58" r="34" fill="var(--panel)" stroke="var(--accent)" strokeWidth="3" />
        <circle cx="89" cy="56" r="4" fill="var(--text)" /><circle cx="111" cy="56" r="4" fill="var(--text)" />
        <path d="M88 70 Q100 82 112 70" stroke="var(--text)" strokeWidth="3" fill="none" strokeLinecap="round" />
      </svg>
      <h1>VirtualFriends for Web</h1>
      <p>Chat with friends, share stickers, and talk to AI companions with their own voice and avatar.</p>
      <p>Pick a chat on the left, search for someone, or open <b>＋</b> to create a group or an AI friend.</p>
    </div>
  );
}

export default function App() {
  // 'checking' until the refresh cookie has been tried, then 'in' or 'out'
  const [status, setStatus] = useState('checking');
  const { theme, bootstrap, handleEvent, activeId, conversations, showAvatar } = useStore();
  const [error, setError] = useState(null);

  useEffect(() => { document.documentElement.dataset.theme = theme; }, [theme]);

  useEffect(() => {
    // Restore the session from the httpOnly cookie (works after reloads and after Google/Facebook sign-in).
    refreshSession().then(() => setStatus('in')).catch(() => setStatus('out'));
    // Session can't be renewed any more (expired / revoked / disabled): start over at the sign-in screen.
    onSessionLost(() => location.reload());
    // Signing out in one tab signs out every tab.
    const onStorage = (e) => { if (e.key === 'vchat.signout') location.reload(); };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  useEffect(() => {
    if (status !== 'in') return undefined;
    bootstrap().catch((e) => setError(e.message));
    return connectRealtime(handleEvent);
  }, [status]);

  if (status === 'checking') return <div className="center-note muted">Loading…</div>;
  if (status === 'out') return <AuthScreen onDone={() => setStatus('in')} />;
  if (error) return <div className="center-note">Could not load: {error}</div>;

  const active = conversations.find((c) => c.id === activeId);
  return (
    <div className={`app-shell ${active ? 'has-active' : ''}`}>
      <Sidebar />
      {active ? <ChatView key={active.id} conversation={active} /> : <EmptyState />}
      {active?.type === 'BOT' && showAvatar && <AvatarPanel bot={active.bot} />}
    </div>
  );
}
