-- Per-bot Claude model (one of vchat.ai.models). NULL = the server default (vchat.ai.model).
ALTER TABLE bot_character ADD COLUMN model VARCHAR(64);
