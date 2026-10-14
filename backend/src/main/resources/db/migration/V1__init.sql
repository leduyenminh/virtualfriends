CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(40)  NOT NULL UNIQUE,
    display_name  VARCHAR(80)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    avatar_url    VARCHAR(500),
    theme         VARCHAR(20)  NOT NULL DEFAULT 'light',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ
);

CREATE TABLE avatar_model (
    id         UUID PRIMARY KEY,
    owner_id   UUID REFERENCES app_user(id) ON DELETE CASCADE, -- NULL = built-in
    name       VARCHAR(80)  NOT NULL,
    format     VARCHAR(20)  NOT NULL, -- PLACEHOLDER | GLTF | VRM | LIVE2D | UNITY
    url        VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bot_character (
    id              UUID PRIMARY KEY,
    owner_id        UUID REFERENCES app_user(id) ON DELETE CASCADE,
    name            VARCHAR(80) NOT NULL,
    persona         TEXT        NOT NULL,
    language        VARCHAR(8)  NOT NULL DEFAULT 'en',
    tts_provider    VARCHAR(20) NOT NULL DEFAULT 'BROWSER', -- BROWSER | ELEVENLABS | COEIROINK | GOOGLE
    voice_id        VARCHAR(120),
    avatar_model_id UUID REFERENCES avatar_model(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE conversation (
    id         UUID PRIMARY KEY,
    type       VARCHAR(10) NOT NULL, -- DIRECT | GROUP | BOT
    title      VARCHAR(120),
    bot_id     UUID REFERENCES bot_character(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE conversation_member (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role            VARCHAR(10) NOT NULL DEFAULT 'MEMBER', -- OWNER | ADMIN | MEMBER
    last_read_at    TIMESTAMPTZ,
    muted           BOOLEAN NOT NULL DEFAULT FALSE,
    pinned          BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (conversation_id, user_id)
);

CREATE TABLE sticker_pack (
    id         UUID PRIMARY KEY,
    owner_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name       VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sticker (
    id         UUID PRIMARY KEY,
    pack_id    UUID NOT NULL REFERENCES sticker_pack(id) ON DELETE CASCADE,
    name       VARCHAR(80) NOT NULL,
    url        VARCHAR(500) NOT NULL,
    mime_type  VARCHAR(40)  NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    sender_id       UUID REFERENCES app_user(id) ON DELETE SET NULL, -- NULL when sent by bot
    from_bot        BOOLEAN NOT NULL DEFAULT FALSE,
    type            VARCHAR(10) NOT NULL, -- TEXT | STICKER | IMAGE | FILE | AUDIO | SYSTEM
    content         TEXT,
    sticker_id      UUID REFERENCES sticker(id) ON DELETE SET NULL,
    attachment_url  VARCHAR(500),
    reply_to_id     UUID REFERENCES message(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at       TIMESTAMPTZ,
    recalled        BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_message_conv_created ON message (conversation_id, created_at DESC);

CREATE TABLE message_reaction (
    id         UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES message(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    emoji      VARCHAR(16) NOT NULL,
    UNIQUE (message_id, user_id, emoji)
);

-- Built-in placeholder avatars (procedural stick figures rendered by the frontend)
INSERT INTO avatar_model (id, owner_id, name, format, url) VALUES
 ('00000000-0000-0000-0000-000000000001', NULL, 'Stick Mika (pink)',  'PLACEHOLDER', 'stick:pink'),
 ('00000000-0000-0000-0000-000000000002', NULL, 'Stick Kaito (blue)', 'PLACEHOLDER', 'stick:blue'),
 ('00000000-0000-0000-0000-000000000003', NULL, 'Stick Lan (green)',  'PLACEHOLDER', 'stick:green');
