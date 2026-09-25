CREATE TABLE IF NOT EXISTS users (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE INDEX IF NOT EXISTS idx_users_role ON users (role);
