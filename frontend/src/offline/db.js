/**
 * Offline chat history, page side. The SQLite database itself lives in db.worker.js (OPFS, one tab at a time).
 * Nothing here throws or blocks: while the cache is loading, unsupported, or owned by another tab, every call resolves
 * to null and the app works exactly as it would without it.
 *
 * The copy belongs to the signed-in session: reaching the sign-in screen (sign-out, expired or revoked session) erases it.
 */
import { DIR, LOCK } from './config.js';

const FLAG = 'vchat.offline'; // this browser has a saved copy (so the sign-in screen knows there is something to erase)

let worker = null;
let state = 'idle'; // idle -> waiting -> ready | unavailable
let seq = 0;
const pending = new Map();
const waiters = [];
const readyListeners = [];

function flag(on) {
  try { if (on) localStorage.setItem(FLAG, '1'); else localStorage.removeItem(FLAG); } catch { /* ignore */ }
}
function hasFlag() {
  try { return localStorage.getItem(FLAG) === '1'; } catch { return false; }
}

function settle(ok) {
  if (state !== 'waiting') return;
  state = ok ? 'ready' : 'unavailable';
  if (ok) {
    flag(true);
    readyListeners.forEach((fn) => fn());
  } else {
    worker?.terminate();
    worker = null;
    pending.forEach((p) => p.resolve(null));
    pending.clear();
  }
  waiters.splice(0).forEach((fn) => fn(ok));
}

function boot() {
  if (state !== 'idle') return;
  try {
    worker = new Worker(new URL('./db.worker.js', import.meta.url), { type: 'module' });
  } catch {
    state = 'unavailable';
    return;
  }
  state = 'waiting';
  worker.onmessage = ({ data }) => {
    if (data.type === 'ready') { settle(data.ok); return; }
    const p = pending.get(data.id);
    if (!p) return;
    pending.delete(data.id);
    if (data.error) console.warn(`Offline history: ${p.op} failed:`, data.error);
    p.resolve(data.error ? null : data.result);
  };
  worker.onerror = (e) => { console.warn('Offline history worker failed:', e.message); settle(false); };
  worker.postMessage({ op: 'start' });
}

function call(op, ...args) {
  if (state !== 'ready') return Promise.resolve(null);
  return new Promise((resolve) => {
    const id = ++seq;
    pending.set(id, { resolve, op });
    worker.postMessage({ id, op, args });
  });
}

export const offlineDb = {
  /** Start the cache for this user (loads SQLite on first use). Data saved for another account is wiped. */
  use(me) {
    boot();
    worker?.postMessage({ op: 'use', args: [me] });
  },

  /** Wait up to `timeoutMs` for the database; false if there is none, it failed, or another tab owns it. */
  ready(timeoutMs) {
    if (state === 'idle' && !hasFlag()) return Promise.resolve(false);
    boot();
    if (state === 'ready' || state === 'unavailable') return Promise.resolve(state === 'ready');
    return new Promise((resolve) => {
      waiters.push(resolve);
      setTimeout(() => resolve(false), timeoutMs);
    });
  },

  /** Runs `fn` once the database is open (right away if it already is). */
  onReady(fn) {
    readyListeners.push(fn);
    if (state === 'ready') fn();
  },

  snapshot: () => call('snapshot'),
  saveState: (s) => call('saveState', s),
  messages: (conversationId, before) => call('messages', conversationId, before ?? null),
  savePage: (conversationId, list, latest = false) => call('savePage', conversationId, list, latest),
  putMessage: (m, opts) => call('putMessage', m, opts),
  has: (ids) => call('has', ids),
  search: (q) => call('search', q),
  around: (conversationId, messageId) => call('around', conversationId, messageId),

  /** Erase this browser's copy. Skipped while another tab still has it open; that tab erases it when it signs out. */
  async purge() {
    if (!hasFlag() || !navigator.locks || !navigator.storage?.getDirectory) return;
    try {
      await navigator.locks.request(LOCK, { ifAvailable: true }, async (lock) => {
        if (!lock) return;
        const root = await navigator.storage.getDirectory();
        await root.removeEntry(DIR, { recursive: true }).catch((e) => { if (e.name !== 'NotFoundError') throw e; });
        flag(false);
      });
    } catch (e) {
      console.warn('Could not erase offline history:', e);
    }
  },
};
