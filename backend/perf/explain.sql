-- EXPLAIN (ANALYZE, BUFFERS) for the app's hot queries, written the way Hibernate sends them.
-- ANALYZE really executes the query and reports actual rows/time; BUFFERS shows pages read from cache (hit) vs disk.
-- What to look for: "Seq Scan" on a big table, "Rows Removed by Filter" in the thousands, actual rows far from the
-- estimate (stale statistics -> run ANALYZE), "Sort Method: external merge" (work_mem too small).
\set QUIET on
\pset pager off
SELECT id AS me FROM app_user WHERE username = 'user1' \gset
SELECT conversation_id AS conv FROM conversation_member WHERE user_id = :'me' LIMIT 1 \gset
\set QUIET off

\echo '=== 1. Chat list: my memberships + conversation (MemberRepo.findMineWithConversation) ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT m.*, c.*, b.*, a.*
FROM conversation_member m
JOIN conversation c ON c.id = m.conversation_id
LEFT JOIN bot_character b ON b.id = c.bot_id
LEFT JOIN avatar_model a ON a.id = b.avatar_model_id
WHERE m.user_id = :'me'
ORDER BY m.pinned DESC, c.updated_at DESC;

\echo '=== 2. Newest message per chat (MessageRepo.findLatestPerConversation) ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT m.* FROM message m
WHERE m.id IN (
  SELECT (SELECT x.id FROM message x WHERE x.conversation_id = c.id ORDER BY x.created_at DESC LIMIT 1)
  FROM conversation c
  WHERE c.id IN (SELECT conversation_id FROM conversation_member WHERE user_id = :'me'));

\echo '=== 3. Unread counts (MessageRepo.countUnreadByConversation) ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT cm.conversation_id, count(m.id)
FROM conversation_member cm
JOIN message m ON m.conversation_id = cm.conversation_id
              AND m.created_at > coalesce(cm.last_read_at, '-infinity'::timestamptz)
              AND (m.sender_id IS NULL OR m.sender_id <> cm.user_id)
WHERE cm.user_id = :'me'
GROUP BY cm.conversation_id;

\echo '=== 4. Message history page, keyset pagination (MessageRepo.page) ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT m.* FROM message m LEFT JOIN sticker s ON s.id = m.sticker_id
WHERE m.conversation_id = :'conv' AND m.created_at < now()
ORDER BY m.created_at DESC LIMIT 50;

\echo '=== 4b. Same page with OFFSET 900 (why the app does NOT use offset paging) ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT m.* FROM message m WHERE m.conversation_id = :'conv'
ORDER BY m.created_at DESC OFFSET 900 LIMIT 50;

\echo '=== 5. People search (UserRepo.search) ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT u.* FROM app_user u
WHERE lower(u.username) LIKE '%' || 'r1234' || '%' OR lower(u.display_name) LIKE '%' || 'r1234' || '%'
ORDER BY u.username LIMIT 20;

\echo '=== 6. Deleting a user: ON DELETE SET NULL on message.sender_id (rolled back) ==='
BEGIN;
EXPLAIN (ANALYZE, COSTS OFF) DELETE FROM app_user WHERE username = 'user19999';
ROLLBACK;
