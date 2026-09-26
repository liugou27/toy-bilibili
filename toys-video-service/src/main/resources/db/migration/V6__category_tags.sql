-- 分区与标签:投稿/编辑可选设置,首页分区筛选与 UP 主主页统计
ALTER TABLE videos ADD COLUMN IF NOT EXISTS category VARCHAR(32);
ALTER TABLE videos ADD COLUMN IF NOT EXISTS tags VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_videos_category ON videos (category);
