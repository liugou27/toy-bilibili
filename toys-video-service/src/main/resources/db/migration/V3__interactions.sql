-- 点赞:唯一键 (video_id, user_id) 保证一人一赞
CREATE TABLE IF NOT EXISTS video_likes (
    id         BIGINT PRIMARY KEY,
    video_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_video_likes_video_user ON video_likes (video_id, user_id);
CREATE INDEX IF NOT EXISTS idx_video_likes_user ON video_likes (user_id, created_at);

-- 收藏:唯一键 (video_id, user_id) 保证一人一收藏
CREATE TABLE IF NOT EXISTS video_favorites (
    id         BIGINT PRIMARY KEY,
    video_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_video_favorites_video_user ON video_favorites (video_id, user_id);
CREATE INDEX IF NOT EXISTS idx_video_favorites_user ON video_favorites (user_id, created_at);

-- 播放历史/断点续播:唯一键 (user_id, video_id),position_sec 为续播秒数
CREATE TABLE IF NOT EXISTS play_histories (
    id           BIGINT PRIMARY KEY,
    user_id      BIGINT           NOT NULL,
    video_id     BIGINT           NOT NULL,
    position_sec DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP        NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_play_histories_user_video ON play_histories (user_id, video_id);
CREATE INDEX IF NOT EXISTS idx_play_histories_user ON play_histories (user_id, updated_at);

ALTER TABLE videos ADD COLUMN IF NOT EXISTS like_count BIGINT NOT NULL DEFAULT 0;
