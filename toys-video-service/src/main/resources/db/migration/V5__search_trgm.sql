-- pg_trgm:标题模糊匹配加速与相似度排序(搜索用 ILIKE + % 相似度算子)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_videos_title_trgm ON videos USING gin (title gin_trgm_ops);
