-- ============================================================================================
-- 1. Social login (Google / Facebook). OAuth-only accounts have no password.
-- ============================================================================================
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE app_user ADD COLUMN email VARCHAR(255);

-- Usernames are case-insensitive from now on (stored lower-case, so the existing UNIQUE still holds).
UPDATE app_user SET username = lower(username) WHERE username <> lower(username);

CREATE TABLE user_identity (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider   VARCHAR(20)  NOT NULL,           -- google | facebook
    subject    VARCHAR(255) NOT NULL,           -- provider's stable user id ("sub" / "id")
    email      VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider, subject)
);
CREATE INDEX idx_user_identity_user ON user_identity (user_id);

-- ============================================================================================
-- 2. Indexes for the hot read paths. PostgreSQL does NOT create indexes for foreign keys, so without
--    these, "my conversations" scans conversation_member and every ON DELETE SET NULL/CASCADE scans
--    the (largest) message table.
-- ============================================================================================

-- "Which conversations am I in?" (chat list). INCLUDE makes it an index-only scan for the list query.
CREATE INDEX idx_member_user ON conversation_member (user_id) INCLUDE (conversation_id, pinned, muted, last_read_at);

-- FK columns on message: partial indexes keep them tiny because most rows have NULL here.
CREATE INDEX idx_message_reply_to ON message (reply_to_id) WHERE reply_to_id IS NOT NULL;
CREATE INDEX idx_message_sticker  ON message (sticker_id)  WHERE sticker_id IS NOT NULL;
-- Needed so deleting a user (sender_id -> SET NULL) doesn't seq-scan every message.
CREATE INDEX idx_message_sender   ON message (sender_id)   WHERE sender_id IS NOT NULL;

CREATE INDEX idx_reaction_user      ON message_reaction (user_id);
CREATE INDEX idx_sticker_pack       ON sticker (pack_id, created_at);
CREATE INDEX idx_sticker_pack_owner ON sticker_pack (owner_id, created_at);
CREATE INDEX idx_bot_owner          ON bot_character (owner_id);
CREATE INDEX idx_avatar_owner       ON avatar_model (owner_id);
CREATE INDEX idx_conversation_bot   ON conversation (bot_id) WHERE bot_id IS NOT NULL;

-- People search uses  lower(col) LIKE '%term%'. A B-tree can't serve a leading wildcard;
-- trigram GIN indexes can (and Postgres BitmapOr's the two for the OR).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_user_username_trgm ON app_user USING gin (lower(username) gin_trgm_ops);
CREATE INDEX idx_user_display_trgm  ON app_user USING gin (lower(display_name) gin_trgm_ops);

-- ============================================================================================
-- 3. Storage tuning for the append-heavy message table: vacuum/analyze after 5%/2% churn instead of
--    the default 20%/10%, so planner statistics stay fresh as the table grows.
-- ============================================================================================
ALTER TABLE message SET (autovacuum_vacuum_scale_factor = 0.05, autovacuum_analyze_scale_factor = 0.02);
