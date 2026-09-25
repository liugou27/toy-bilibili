ALTER TABLE moderation_reports ADD COLUMN IF NOT EXISTS claimed_by BIGINT;
ALTER TABLE moderation_reports ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_moderation_claim_release ON moderation_reports (claimed_at);
