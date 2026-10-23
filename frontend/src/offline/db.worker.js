/**
 * Offline chat history: a SQLite database in the browser's Origin Private File System (OPFS). It runs in this
 * dedicated worker because OPFS sync access handles only exist in workers, and it uses the "opfs-sahpool" VFS, which
 * (unlike the plain "opfs" VFS) needs no COOP/COEP headers that would break the Live2D CDN, Google pickers and media CDN.
 *
 * The pool can be open in one tab only, so the tab holding the Web Lock owns the database until it closes; meanwhile
 * other tabs run without the cache, and the next waiting tab takes over. Until the database is open every operation
 * returns null: the cache must never block or break the app.
 *
 * Invariant: for each conversation the cache holds one unbroken stretch of history ending at the newest message we
 * have seen. A stretch with unknown messages after it is dropped (savePage) rather than shown with a silent gap.
 */
import sqlite3InitModule from '@sqlite.org/sqlite-wasm';
import { DIR, LOCK, PAGE } from './config.js';

const FILE = '/vchat.sqlite3';
const VERSION = 1; // bump to rebuild the cache with a new schema (it is only a copy of server data)

const SCHEMA = `
  CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL);
  CREATE TABLE message (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    created_ms      INTEGER NOT NULL,
    body            TEXT,          -- searchable text, NULL for stickers/voice notes/deleted messages
    json            TEXT NOT NULL  -- the MessageDto exactly as the server sent it
  );
  CREATE INDEX message_by_conversation ON message (conversation_id, created_ms);

  -- Substring search that works for Vietnamese without accents and for CJK text without spaces
  CREATE VIRTUAL TABLE message_fts USING fts5(body, content = 'message', content_rowid = 'rowid',
                                              tokenize = 'trigram remove_diacritics 1');
  CREATE TRIGGER message_ai AFTER INSERT ON message BEGIN
    INSERT INTO message_fts (rowid, body) VALUES (new.rowid, new.body);
  END;
  CREATE TRIGGER message_ad AFTER DELETE ON message BEGIN
    INSERT INTO message_fts (message_fts, rowid, body) VALUES ('delete', old.rowid, old.body);
  END;
  CREATE TRIGGER message_au AFTER UPDATE ON message BEGIN
    INSERT INTO message_fts (message_fts, rowid, body) VALUES ('delete', old.rowid, old.body);
    INSERT INTO message_fts (rowid, body) VALUES (new.rowid, new.body);
  END;
  PRAGMA user_version = ${VERSION};
`;

let db = null;
let owner = null; // MeDto of the signed-in user, from the page
let started = false;

const ms = (t) => new Date(t).getTime() || 0;
const parse = (rows) => rows.map((json) => JSON.parse(json));

function bodyOf(m) {
  if (m.recalled || !m.content) return null;
  return m.type === 'TEXT' || m.type === 'FILE' || m.type === 'IMAGE' ? m.content : null;
}

function upsert(m) {
  db.exec({
    sql: `INSERT INTO message (id, conversation_id, created_ms, body, json) VALUES (?, ?, ?, ?, ?)
          ON CONFLICT (id) DO UPDATE SET body = excluded.body, json = excluded.json`,
    bind: [m.id, m.conversationId, ms(m.createdAt), bodyOf(m), JSON.stringify(m)],
  });
}

const exists = (id) => !!id && db.selectValue('SELECT 1 FROM message WHERE id = ?', [id]) === 1;

/** The database belongs to one user: anything saved for a different account is wiped first. */
function claim() {
  const saved = db.selectValue("SELECT v FROM kv WHERE k = 'me'");
  if (saved && JSON.parse(saved).id !== owner.id) {
    db.transaction(() => db.exec('DELETE FROM message; DELETE FROM kv;'));
  }
  db.exec({ sql: "INSERT INTO kv (k, v) VALUES ('me', ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v", bind: [JSON.stringify(owner)] });
}

async function open() {
  const sqlite3 = await sqlite3InitModule({ print: () => {}, printErr: () => {} });
  const pool = await sqlite3.installOpfsSAHPoolVfs({ name: 'vchat-offline', directory: DIR });
  db = new pool.OpfsSAHPoolDb(FILE);
  const version = db.selectValue('PRAGMA user_version');
  if (version !== VERSION) {
    if (version !== 0) {
      db.close();
      pool.unlink(FILE);
      db = new pool.OpfsSAHPoolDb(FILE);
    }
    db.exec(SCHEMA);
  }
  if (owner) claim();
}

function start() {
  if (started) return;
  started = true;
  if (!navigator.locks || !navigator.storage?.getDirectory) {
    postMessage({ type: 'ready', ok: false });
    return;
  }
  navigator.locks.request(LOCK, async () => {
    try {
      await open();
    } catch (e) {
      console.warn('Offline history unavailable:', e);
      db = null;
      postMessage({ type: 'ready', ok: false });
      return;
    }
    postMessage({ type: 'ready', ok: true });
    await new Promise(() => {}); // keep the lock, and the pool's file handles, for as long as this tab is open
  });
}

const ops = {
  use(me) {
    owner = me;
    if (db) claim();
  },

  /** Everything the app needs to render the chat list without a server. */
  snapshot() {
    const out = {};
    for (const [k, v] of db.selectArrays('SELECT k, v FROM kv')) out[k] = JSON.parse(v);
    return out;
  },

  saveState(state) {
    db.transaction(() => {
      for (const [k, v] of Object.entries(state)) {
        db.exec({ sql: 'INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v', bind: [k, JSON.stringify(v)] });
      }
    });
  },

  /** Up to one page of messages older than `before` (newest page when omitted), oldest first like the API. */
  messages(conversationId, before) {
    const rows = db.selectValues(
      'SELECT json FROM message WHERE conversation_id = ? AND created_ms < ? ORDER BY created_ms DESC LIMIT ?',
      [conversationId, before ? ms(before) : Number.MAX_SAFE_INTEGER, PAGE],
    );
    return parse(rows.reverse());
  },

  /** A page from the API. `latest`: it is the newest page, so it may have jumped past what the cache holds. */
  savePage(conversationId, list, latest) {
    if (!list.length) return;
    db.transaction(() => {
      if (latest && list.length >= PAGE) {
        const newest = db.selectValue('SELECT max(created_ms) FROM message WHERE conversation_id = ?', [conversationId]);
        if (newest != null && newest < ms(list[0].createdAt)) {
          db.exec({ sql: 'DELETE FROM message WHERE conversation_id = ?', bind: [conversationId] });
        }
      }
      list.forEach(upsert);
    });
  },

  /**
   * One message from the app or a realtime event. Known messages are updated; a new one is inserted when the chat's
   * history is current (`insert`) or when it directly follows a message we have (`after`), so no gap can appear.
   */
  putMessage(m, { insert = false, after = null } = {}) {
    if (insert || exists(m.id) || exists(after)) upsert(m);
  },

  has(ids) {
    if (!ids.length) return [];
    return db.selectValues(`SELECT id FROM message WHERE id IN (${ids.map(() => '?').join(',')})`, ids);
  },

  /** Full-text search over saved messages (3+ characters; trigram index). Matches are wrapped in \u0002…\u0003. */
  search(q, limit = 30) {
    if ([...q].length < 3) return [];
    const rows = db.selectObjects(
      `SELECT m.json, snippet(message_fts, 0, char(2), char(3), '…', 40) AS snippet
       FROM message_fts JOIN message m ON m.rowid = message_fts.rowid
       WHERE message_fts MATCH ? ORDER BY m.created_ms DESC LIMIT ?`,
      [`"${q.replaceAll('"', '""')}"`, limit],
    );
    return rows.map((r) => ({ message: JSON.parse(r.json), snippet: r.snippet }));
  },

  /** A few messages before `messageId` through the newest saved one, to jump to a search hit. Null if too far back. */
  around(conversationId, messageId, context = 20, max = 1000) {
    const at = db.selectValue('SELECT created_ms FROM message WHERE id = ?', [messageId]);
    if (at == null) return null;
    const earlier = db.selectValues(
      'SELECT created_ms FROM message WHERE conversation_id = ? AND created_ms < ? ORDER BY created_ms DESC LIMIT ?',
      [conversationId, at, context],
    );
    const from = earlier.length ? earlier[earlier.length - 1] : at;
    const rows = db.selectValues(
      'SELECT json FROM message WHERE conversation_id = ? AND created_ms >= ? ORDER BY created_ms LIMIT ?',
      [conversationId, from, max + 1],
    );
    return rows.length > max ? null : parse(rows);
  },
};

onmessage = ({ data: { id, op, args = [] } }) => {
  if (op === 'start') { start(); return; }
  let result = null;
  let error = null;
  try {
    if (op === 'use') ops.use(...args);
    else if (db) result = ops[op](...args) ?? null;
  } catch (e) {
    error = String(e?.message ?? e);
  }
  if (id !== undefined) postMessage({ id, result, error });
};
