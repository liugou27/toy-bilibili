-- 黑样本库:violation_samples
CREATE TABLE IF NOT EXISTS violation_samples (
    id         BIGINT PRIMARY KEY,
    phash      BIGINT      NOT NULL,
    type       VARCHAR(16) NOT NULL,
    note       VARCHAR(200),
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_violation_samples_type ON violation_samples (type);
