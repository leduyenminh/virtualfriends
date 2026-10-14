-- Roles + account suspension
ALTER TABLE app_user ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'USER';   -- USER | ADMIN
ALTER TABLE app_user ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Refresh tokens (server-side sessions). Only a SHA-256 hash is stored: a DB leak doesn't leak usable tokens.
-- Every refresh rotates the token; all tokens descending from one login share a family_id, so presenting an
-- already-rotated token (= stolen copy) revokes the whole family.
CREATE TABLE refresh_token (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    family_id  UUID         NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    user_agent VARCHAR(255),
    ip         VARCHAR(64)
);
CREATE INDEX idx_refresh_user_active ON refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_family      ON refresh_token (family_id);
CREATE INDEX idx_refresh_expires     ON refresh_token (expires_at);
