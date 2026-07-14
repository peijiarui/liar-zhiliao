-- =============================================================================
-- liar-zhiliao Database Schema
-- Target Database: PostgreSQL 14+
-- =============================================================================

-- 0. Extensions
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- zhparser 中文分词插件（全文搜索用）
CREATE EXTENSION IF NOT EXISTS zhparser;
CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser);

-- 1. Departments
-- =============================================================================
-- Department hierarchy tree. parent_id = NULL means root-level department.

CREATE TABLE IF NOT EXISTS sys_department (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(100)    NOT NULL,
    parent_id   BIGINT          ,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

-- 2. Users
-- =============================================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGSERIAL       PRIMARY KEY,
    login_name      VARCHAR(50)     NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    name            VARCHAR(100),
    email           VARCHAR(200),
    phone           VARCHAR(30),
    dept_id         BIGINT          NOT NULL DEFAULT 1,
    role            VARCHAR(20)     NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    tenant_id       VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at      TIMESTAMPTZ       DEFAULT NOW()
);

-- 3. Knowledge Bases
-- =============================================================================

CREATE TABLE IF NOT EXISTS zl_knowledge_base (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    dept_id     BIGINT          NOT NULL DEFAULT 1 ,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW()
);

-- 4. Documents
-- =============================================================================

CREATE TABLE IF NOT EXISTS zl_document (
    id          BIGSERIAL       PRIMARY KEY,
    kb_id       BIGINT          NOT NULL ,
    file_name   VARCHAR(255)    NOT NULL,
    file_type   VARCHAR(50),
    status      VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    minio_key   VARCHAR(500),
    file_size   BIGINT,
    md5         VARCHAR(32),
    chunk_count INT             DEFAULT 0,
    dept_id     BIGINT          NOT NULL DEFAULT 1 ,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW()
);

-- 5. Chunks (document segments with embeddings)
-- =============================================================================

CREATE TABLE IF NOT EXISTS zl_chunk (
    id           BIGSERIAL       PRIMARY KEY,
    doc_id       BIGINT          NOT NULL ,
    content      TEXT            NOT NULL,
    embedding_id VARCHAR(100),
    metadata     JSONB,
    parent_id    BIGINT,
    chunk_type   VARCHAR(10)     NOT NULL DEFAULT 'child' CHECK (chunk_type IN ('parent', 'child')),
    dept_id      BIGINT          NOT NULL DEFAULT 1 ,
    tenant_id    VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at   TIMESTAMPTZ       DEFAULT NOW()
);

-- 6. Conversations (chat sessions)
-- =============================================================================

CREATE TABLE IF NOT EXISTS zl_conversation (
    id              BIGSERIAL       PRIMARY KEY,
    memory_id       VARCHAR(100)    NOT NULL,
    user_id         BIGINT          ,
    title           VARCHAR(200),
    message_count   INT             DEFAULT 0,
    dept_id         BIGINT          NOT NULL DEFAULT 1 ,
    tenant_id       VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at      TIMESTAMPTZ       DEFAULT NOW()
);

-- 6. Conversations (chat sessions) — 追加字段
ALTER TABLE zl_conversation ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE zl_conversation ADD UNIQUE (memory_id);

COMMENT ON COLUMN zl_conversation.updated_at IS '最后活动时间，每次收发消息时更新，对话列表按此字段降序排列';
CREATE INDEX IF NOT EXISTS idx_zl_conversation_updated_at ON zl_conversation(updated_at DESC);

-- 7. Audit Logs
-- =============================================================================

CREATE TABLE IF NOT EXISTS zl_audit_log (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          ,
    action      VARCHAR(100)    NOT NULL,
    target_type VARCHAR(50),
    target_id   BIGINT,
    detail      JSONB,
    dept_id     BIGINT          NOT NULL DEFAULT 1 ,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW()
);

-- Indexes
-- =============================================================================

-- sys_department
CREATE INDEX IF NOT EXISTS idx_sys_department_parent_id ON sys_department(parent_id);
CREATE INDEX IF NOT EXISTS idx_sys_department_tenant_id ON sys_department(tenant_id);

-- zl_document
CREATE INDEX IF NOT EXISTS idx_zl_document_kb_id    ON zl_document(kb_id);
CREATE INDEX IF NOT EXISTS idx_zl_document_status   ON zl_document(status);
CREATE INDEX IF NOT EXISTS idx_zl_document_tenant_dept ON zl_document(tenant_id, dept_id);

-- zl_chunk
CREATE INDEX IF NOT EXISTS idx_zl_chunk_doc_id      ON zl_chunk(doc_id);
CREATE INDEX IF NOT EXISTS idx_zl_chunk_tenant_dept ON zl_chunk(tenant_id, dept_id);
-- BM25 全文搜索索引（检索质量提升 Phase 1）
CREATE INDEX IF NOT EXISTS idx_zl_chunk_content_fts ON zl_chunk
  USING GIN (to_tsvector('zh', content));

-- zl_conversation
CREATE INDEX IF NOT EXISTS idx_zl_conversation_memory_id ON zl_conversation(memory_id);
CREATE INDEX IF NOT EXISTS idx_zl_conversation_user_id   ON zl_conversation(user_id);
CREATE INDEX IF NOT EXISTS idx_zl_conversation_tenant_dept ON zl_conversation(tenant_id, dept_id);

-- zl_knowledge_base
CREATE INDEX IF NOT EXISTS idx_kb_tenant_dept     ON zl_knowledge_base(tenant_id, dept_id);

-- zl_audit_log
CREATE INDEX IF NOT EXISTS idx_zl_audit_log_tenant_user ON zl_audit_log(tenant_id, user_id);

-- lookup composites
CREATE INDEX IF NOT EXISTS idx_sys_user_tenant_dept  ON sys_user(tenant_id, dept_id);
