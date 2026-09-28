-- 风控评级落地:机审报告记录 risk-service 的打分与评级(降级时为空)
ALTER TABLE moderation_reports
    ADD COLUMN risk_score INT,
    ADD COLUMN risk_level VARCHAR(16);
