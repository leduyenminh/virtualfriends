-- Synthetic data for query tuning. Run against a SEPARATE database (see perf/README.md), never the dev DB.
-- 20k users, 50k direct chats, ~2.3M messages. "user1" is a heavy user with 300 chats x ~1000 messages.
\timing on
SET synchronous_commit = off;

INSERT INTO app_user (id, username, display_name, password_hash, created_at)
SELECT gen_random_uuid(), 'user' || g, 'User ' || g || ' ' || md5(g::text), 'not-a-real-hash', now() - g * interval '1 minute'
FROM generate_series(1, 20000) g;

CREATE TEMP TABLE u AS SELECT id, row_number() OVER (ORDER BY created_at DESC) AS rn FROM app_user;
CREATE INDEX ON u (rn);

INSERT INTO conversation (id, type, created_at, updated_at)
SELECT gen_random_uuid(), 'DIRECT', now() - interval '200 days', now() - random() * interval '90 days'
FROM generate_series(1, 50000);

-- pairs: the first 300 chats belong to user1, the rest pair up pseudo-random users
CREATE TEMP TABLE pairs AS
SELECT c.id AS conv_id, c.rn,
       (SELECT id FROM u WHERE u.rn = CASE WHEN c.rn <= 300 THEN 1 ELSE 2 + (c.rn % 19990) END) AS a,
       (SELECT id FROM u WHERE u.rn = CASE WHEN c.rn <= 300 THEN c.rn + 1 ELSE 3 + ((c.rn * 7919) % 19990) END) AS b
FROM (SELECT id, row_number() OVER () AS rn FROM conversation) c;
CREATE INDEX ON pairs (rn);

INSERT INTO conversation_member (id, conversation_id, user_id, role, last_read_at)
SELECT gen_random_uuid(), conv_id, a, 'OWNER', now() - random() * interval '30 days' FROM pairs
UNION ALL
SELECT gen_random_uuid(), conv_id, b, 'MEMBER', now() - random() * interval '30 days' FROM pairs WHERE a <> b;

-- ~40 messages in every chat
INSERT INTO message (id, conversation_id, sender_id, from_bot, type, content, created_at)
SELECT gen_random_uuid(), p.conv_id, CASE WHEN g % 2 = 0 THEN p.a ELSE p.b END, false, 'TEXT',
       'message ' || g, now() - random() * interval '180 days'
FROM generate_series(1, 2000000) g JOIN pairs p ON p.rn = 1 + (g % 50000);

-- user1's 300 chats get ~1000 more each
INSERT INTO message (id, conversation_id, sender_id, from_bot, type, content, created_at)
SELECT gen_random_uuid(), p.conv_id, CASE WHEN g % 2 = 0 THEN p.a ELSE p.b END, false, 'TEXT',
       'hot message ' || g, now() - random() * interval '60 days'
FROM generate_series(1, 300000) g JOIN pairs p ON p.rn = 1 + (g % 300);

VACUUM ANALYZE;
SELECT (SELECT count(*) FROM app_user) AS users, (SELECT count(*) FROM conversation) AS chats,
       (SELECT count(*) FROM message) AS messages;
