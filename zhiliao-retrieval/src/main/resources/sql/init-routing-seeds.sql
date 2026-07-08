-- ============================================================
-- 查询路由种子表 — 用于 EmbeddingQueryRouter 意图分类
-- chat:      闲聊类种子，命中时跳过 RAG 检索
-- knowledge: 知识类种子，命中时走正常 RAG
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_routing_seed (
    id          BIGSERIAL       PRIMARY KEY,
    category    VARCHAR(32)     NOT NULL,           -- chat / knowledge
    content     TEXT            NOT NULL,           -- 种子文本
    sort_order  INT             NOT NULL DEFAULT 0, -- 排序（仅用于维护）
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

COMMENT ON TABLE  sys_routing_seed           IS '查询路由种子 — EmbeddingQueryRouter 意图分类';
COMMENT ON COLUMN sys_routing_seed.category   IS '类别: chat=闲聊, knowledge=知识';
COMMENT ON COLUMN sys_routing_seed.content    IS '种子文本，用于计算该类别的质心 embedding';
COMMENT ON COLUMN sys_routing_seed.sort_order IS '排序（仅用于展示/维护）';

CREATE UNIQUE INDEX IF NOT EXISTS idx_routing_seed_uniq ON sys_routing_seed (category, content);

-- ============================================================
-- 初始种子数据
-- ============================================================

INSERT INTO sys_routing_seed (category, content, sort_order) VALUES
-- 闲聊类
('chat', '你好',       1),
('chat', '您好',       2),
('chat', '嗨',         3),
('chat', 'hello',      4),
('chat', 'hi',         5),
('chat', 'hey',        6),
('chat', '早上好',     7),
('chat', '下午好',     8),
('chat', '晚上好',     9),
('chat', '大家好',    10),
('chat', '再见',      11),
('chat', '拜拜',      12),
('chat', 'bye',       13),
('chat', '回见',      14),
('chat', '明天见',    15),
('chat', '谢谢',      16),
('chat', '感谢',      17),
('chat', '多谢',      18),
('chat', 'thanks',    19),
('chat', 'thank you', 20),
('chat', '你是谁',    21),
('chat', '你叫什么',  22),
('chat', 'who are you', 23),
('chat', '好的',      24),
('chat', '可以',      25),
('chat', 'ok',        26),
('chat', '嗯',        27),
('chat', '行',        28),
('chat', '好滴',      29),
('chat', '没问题',    30),
('chat', '在吗',      31),
('chat', '在不在',    32),
('chat', '有人吗',    33),
('chat', '哈哈',      34),
('chat', '哈哈哈',    35),
-- 知识类
('knowledge', '什么是报销制度',             1),
('knowledge', '请假流程是什么',             2),
('knowledge', '公司的考勤规定',             3),
('knowledge', '如何在系统中提交申请',       4),
('knowledge', '费用报销的标准是什么',       5),
('knowledge', '介绍一下公司的产品',         6),
('knowledge', '具体的操作步骤',             7),
('knowledge', 'where can I find the policy', 8),
('knowledge', 'how to apply for leave',      9),
('knowledge', '有没有关于休假的规定',       10),
('knowledge', '差旅费怎么报销',             11),
('knowledge', '加班怎么申请',               12)
ON CONFLICT (category, content) DO NOTHING;
