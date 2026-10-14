import React, { useEffect, useState } from 'react';
import { api, setSession, takeNotice } from '../api.js';

const LAST_USER = 'vchat.lastUser';
const read = (k) => { try { return localStorage.getItem(k); } catch { return null; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };

const PROVIDERS = {
  google: { label: 'Continue with Google', mark: 'G', color: '#4285f4' },
  facebook: { label: 'Continue with Facebook', mark: 'f', color: '#1877f2' },
};

export default function AuthScreen({ onDone }) {
  const lastUser = read(LAST_USER);
  const [mode, setMode] = useState(lastUser ? 'login' : 'register');
  const [form, setForm] = useState({ username: lastUser ?? '', displayName: '', password: '' });
  const [notice] = useState(takeNotice);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [providers, setProviders] = useState([]);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  useEffect(() => { api.providers().then(setProviders).catch(() => {}); }, []);

  async function submit(e) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = mode === 'login' ? await api.login(form) : await api.register(form);
      write(LAST_USER, res.user.username);
      setSession(res); // access token stays in memory; the refresh cookie was set by the server
      onDone();
    } catch (err) {
      setError(err.message);
    } finally { setBusy(false); }
  }

  const switchTo = (m) => { setMode(m); setError(null); };

  return (
    <div className="auth-page">
      <div className="auth-brand">
        <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden="true">
          <circle cx="16" cy="16" r="15" fill="#fff" opacity=".25" />
          <circle cx="16" cy="11" r="5" fill="none" stroke="#fff" strokeWidth="2.2" />
          <path d="M16 16v7m0-4-5-3m5 3 5-3m-5 7-3 5m3-5 3 5" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" fill="none" />
        </svg>
        VirtualFriends
      </div>
      <div className="auth-card">
        <div className="auth-intro">
          <h1>Chat with friends and AI companions</h1>
          <ol>
            <li>{mode === 'login' ? 'Sign in with your username and password' : 'Create an account (takes 10 seconds)'}</li>
            <li>Search a friend's username to start a chat, or make a group</li>
            <li>Open the <b>＋</b> menu → <b>New AI friend</b> to meet a VTuber-style companion</li>
            <li>Switch themes any time from the <b>⋮</b> menu</li>
          </ol>
        </div>
        <div>
          <div className="auth-tabs" role="tablist">
            <button type="button" role="tab" aria-selected={mode === 'login'} className={mode === 'login' ? 'on' : ''} onClick={() => switchTo('login')}>Sign in</button>
            <button type="button" role="tab" aria-selected={mode === 'register'} className={mode === 'register' ? 'on' : ''} onClick={() => switchTo('register')}>Create account</button>
          </div>
          <form className="auth-form" onSubmit={submit}>
            {notice && <div className="notice" role="status">{notice}</div>}
            <div className="field">
              <label htmlFor="u">Username</label>
              <input id="u" className="input" value={form.username} onChange={set('username')} autoComplete="username" autoFocus required />
              {mode === 'register' && <div className="hint">3–40 characters: letters, numbers, dot or underscore. Not case-sensitive.</div>}
            </div>
            {mode === 'register' && (
              <div className="field">
                <label htmlFor="d">Display name</label>
                <input id="d" className="input" value={form.displayName} onChange={set('displayName')} placeholder="How friends see you" required />
              </div>
            )}
            <div className="field">
              <label htmlFor="p">Password</label>
              <input id="p" className="input" type="password" value={form.password} onChange={set('password')}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required />
              {mode === 'register' && <div className="hint">At least 8 characters.</div>}
            </div>
            {error && <div className="error" role="alert">{error}</div>}
            <button className="btn primary" type="submit" disabled={busy}>{mode === 'login' ? 'Sign in' : 'Create account'}</button>
            {providers.length > 0 && (
              <>
                <div className="or">or</div>
                {providers.map((p) => (
                  <a key={p} className="social" href={`/oauth2/authorization/${p}`}>
                    <span className="logo" style={{ background: PROVIDERS[p]?.color }}>{PROVIDERS[p]?.mark}</span>
                    {PROVIDERS[p]?.label ?? p}
                  </a>
                ))}
              </>
            )}
            <div className="muted" style={{ fontSize: 13, textAlign: 'center' }}>
              {mode === 'login'
                ? <>New here? <a href="#" onClick={(e) => { e.preventDefault(); switchTo('register'); }}>Create an account</a></>
                : <>Already have an account? <a href="#" onClick={(e) => { e.preventDefault(); switchTo('login'); }}>Sign in</a></>}
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
