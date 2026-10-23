/** Shared by the page (db.js) and the database worker (db.worker.js). */

/** Web Lock held by whichever tab currently owns the database (the OPFS pool can be open in only one tab). */
export const LOCK = 'vchat-offline-db';
/** OPFS directory of the SQLite "opfs-sahpool" VFS. Removing it erases everything. */
export const DIR = '.vchat-offline';
/** Server page size for message history (ConversationController's default limit). */
export const PAGE = 50;
