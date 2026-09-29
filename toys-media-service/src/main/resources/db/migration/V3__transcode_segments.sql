-- 转码分段:大视频切段后作为子作业在集群实例间并行转码,全部完成再组装
CREATE TABLE IF NOT EXISTS transcode_segments (
    id            BIGINT PRIMARY KEY,
    job_id        BIGINT      NOT NULL,
    video_id      BIGINT      NOT NULL,
    seg_index     INT         NOT NULL,
    object_key    TEXT        NOT NULL,
    duration_sec  DOUBLE PRECISION,
    ladder        TEXT        NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts      INT         NOT NULL DEFAULT 0,
    max_attempts  INT         NOT NULL DEFAULT 3,
    owner_instance VARCHAR(64),
    lease_until   TIMESTAMP,
    error         TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_segment UNIQUE (job_id, seg_index)
);

CREATE INDEX IF NOT EXISTS idx_segment_lease ON transcode_segments (status, lease_until);
CREATE INDEX IF NOT EXISTS idx_segment_job ON transcode_segments (job_id);
