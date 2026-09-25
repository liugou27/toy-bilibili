CREATE TABLE IF NOT EXISTS videos (
    id                BIGINT PRIMARY KEY,
    owner_id          BIGINT        NOT NULL,
    title             VARCHAR(100)  NOT NULL,
    description       VARCHAR(2000) NOT NULL DEFAULT '',
    status            VARCHAR(32)   NOT NULL,
    object_key        VARCHAR(255),
    original_filename VARCHAR(255),
    size_bytes        BIGINT,
    duration_sec      DOUBLE PRECISION,
    width             INT,
    height            INT,
    format            VARCHAR(64),
    play_count        BIGINT        NOT NULL DEFAULT 0,
    note              VARCHAR(500),
    published_at      TIMESTAMP,
    created_at        TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_videos_status_published ON videos (status, published_at);
CREATE INDEX IF NOT EXISTS idx_videos_owner ON videos (owner_id);
