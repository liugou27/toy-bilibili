CREATE TABLE IF NOT EXISTS user_violations (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(16)  NOT NULL,
    reason     VARCHAR(300),
    video_id   BIGINT,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_violations_user_time ON user_violations (user_id, created_at);

ALTER TABLE users ADD COLUMN IF NOT EXISTS banned_until TIMESTAMP;

CREATE TABLE IF NOT EXISTS follows (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    target_id  BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_follows_user_target UNIQUE (user_id, target_id)
);

CREATE INDEX IF NOT EXISTS idx_follows_target ON follows (target_id);
