CREATE TABLE IF NOT EXISTS sensitive_words (
    id         BIGINT PRIMARY KEY,
    word       VARCHAR(64) NOT NULL,
    level      VARCHAR(16) NOT NULL DEFAULT 'REJECT',
    category   VARCHAR(32),
    status     VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_sensitive_word UNIQUE (word)
);

CREATE INDEX IF NOT EXISTS idx_sensitive_words_updated_at ON sensitive_words (updated_at);

INSERT INTO sensitive_words (id, word, level, category) VALUES
    (1,  '颠覆国家', 'REJECT', '涉政暴恐'),
    (2,  '分裂国家', 'REJECT', '涉政暴恐'),
    (3,  '煽动颠覆', 'REJECT', '涉政暴恐'),
    (4,  '恐怖袭击', 'REJECT', '涉政暴恐'),
    (5,  '恐怖组织', 'REJECT', '涉政暴恐'),
    (6,  '暴恐音视频', 'REJECT', '涉政暴恐'),
    (7,  '本拉登', 'REJECT', '涉政暴恐'),
    (8,  '邪教组织', 'REJECT', '涉政暴恐'),
    (9,  '反动宣传', 'REJECT', '涉政暴恐'),
    (10, '色情', 'REJECT', '色情'),
    (11, '情色', 'REJECT', '色情'),
    (12, '裸聊', 'REJECT', '色情'),
    (13, '约炮', 'REJECT', '色情'),
    (14, '援交', 'REJECT', '色情'),
    (15, '卖淫', 'REJECT', '色情'),
    (16, '嫖娼', 'REJECT', '色情'),
    (17, '招嫖', 'REJECT', '色情'),
    (18, '一夜情', 'REJECT', '色情'),
    (19, '赌博', 'REJECT', '赌博'),
    (20, '博彩', 'REJECT', '赌博'),
    (21, '赌场', 'REJECT', '赌博'),
    (22, '六合彩', 'REJECT', '赌博'),
    (23, '网赌', 'REJECT', '赌博'),
    (24, '现金网', 'REJECT', '赌博'),
    (25, 'porn', 'REJECT', '英文'),
    (26, 'casino', 'REJECT', '英文');
