CREATE TABLE IF NOT EXISTS moderation_reports (
    id            BIGINT PRIMARY KEY,
    video_id      BIGINT      NOT NULL,
    auto_verdict  VARCHAR(20) NOT NULL,
    auto_report   JSONB       NOT NULL,
    decision      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewer_id   BIGINT,
    reject_reason VARCHAR(500),
    decided_at    TIMESTAMP,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_moderation_video UNIQUE (video_id)
);

CREATE INDEX IF NOT EXISTS idx_moderation_decision ON moderation_reports (decision, created_at);
