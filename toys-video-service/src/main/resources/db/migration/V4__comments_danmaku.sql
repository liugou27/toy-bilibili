-- 评论:按发表时间倒序分页展示
CREATE TABLE IF NOT EXISTS comments (
    id         BIGINT PRIMARY KEY,
    video_id   BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    content    VARCHAR(500) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_comments_video ON comments (video_id, created_at);

-- 弹幕:按播放位置正序全量下发,不可删除
CREATE TABLE IF NOT EXISTS danmaku (
    id         BIGINT PRIMARY KEY,
    video_id   BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    content    VARCHAR(100) NOT NULL,
    time_sec   DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_danmaku_video ON danmaku (video_id, time_sec);
