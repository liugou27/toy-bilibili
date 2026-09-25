CREATE TABLE IF NOT EXISTS transcode_jobs (
    id          BIGINT PRIMARY KEY,
    video_id    BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts    INT         NOT NULL DEFAULT 0,
    max_attempts INT        NOT NULL DEFAULT 3,
    error       TEXT,
    payload     JSONB,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_transcode_video UNIQUE (video_id)
);

CREATE INDEX IF NOT EXISTS idx_transcode_status ON transcode_jobs (status, created_at);
