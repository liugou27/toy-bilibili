-- 转码集群:实例标识 + 租约 + 派发参数
ALTER TABLE transcode_jobs ADD COLUMN IF NOT EXISTS owner_instance VARCHAR(64);
ALTER TABLE transcode_jobs ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
ALTER TABLE transcode_jobs ADD COLUMN IF NOT EXISTS object_key TEXT;

CREATE INDEX IF NOT EXISTS idx_transcode_lease ON transcode_jobs (status, lease_until);
