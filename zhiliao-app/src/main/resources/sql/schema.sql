-- =============================================================================
-- liar-zhiliao Database Schema
-- Target Database: PostgreSQL 14+
-- =============================================================================

-- 0. Extensions
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Departments
-- =============================================================================
-- Department hierarchy tree. parent_id = NULL means root-level department.

CREATE TABLE IF NOT EXISTS departments (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(100)    NOT NULL,
    parent_id   BIGINT          REFERENCES departments(id) ON DELETE RESTRICT,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

-- 2. Users
-- =============================================================================

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(50)     NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    dept_id         BIGINT          NOT NULL DEFAULT 1 REFERENCES departments(id) ON DELETE RESTRICT,
    role            VARCHAR(20)     NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    tenant_id       VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at      TIMESTAMPTZ       DEFAULT NOW()
);

-- 3. Knowledge Bases
-- =============================================================================

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    dept_id     BIGINT          NOT NULL DEFAULT 1 REFERENCES departments(id) ON DELETE RESTRICT,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW()
);

-- 4. Documents
-- =============================================================================

CREATE TABLE IF NOT EXISTS documents (
    id          BIGSERIAL       PRIMARY KEY,
    kb_id       BIGINT          NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    file_name   VARCHAR(255)    NOT NULL,
    file_type   VARCHAR(50),
    status      VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    minio_key   VARCHAR(500),
    file_size   BIGINT,
    md5         VARCHAR(32),
    chunk_count INT             DEFAULT 0,
    dept_id     BIGINT          NOT NULL DEFAULT 1 REFERENCES departments(id) ON DELETE RESTRICT,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW()
);

-- 5. Chunks (document segments with embeddings)
-- =============================================================================

CREATE TABLE IF NOT EXISTS chunks (
    id           BIGSERIAL       PRIMARY KEY,
    doc_id       BIGINT          NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    content      TEXT            NOT NULL,
    embedding_id VARCHAR(100),
    metadata     JSONB,
    dept_id      BIGINT          NOT NULL DEFAULT 1 REFERENCES departments(id) ON DELETE RESTRICT,
    tenant_id    VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at   TIMESTAMPTZ       DEFAULT NOW()
);

-- 6. Conversations (chat sessions)
-- =============================================================================

CREATE TABLE IF NOT EXISTS conversations (
    id              BIGSERIAL       PRIMARY KEY,
    memory_id       VARCHAR(100)    NOT NULL,
    user_id         BIGINT          REFERENCES users(id) ON DELETE SET NULL,
    title           VARCHAR(200),
    message_count   INT             DEFAULT 0,
    dept_id         BIGINT          NOT NULL DEFAULT 1 REFERENCES departments(id) ON DELETE RESTRICT,
    tenant_id       VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at      TIMESTAMPTZ       DEFAULT NOW()
);

-- 7. Audit Logs
-- =============================================================================

CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          REFERENCES users(id) ON DELETE SET NULL,
    action      VARCHAR(100)    NOT NULL,
    target_type VARCHAR(50),
    target_id   BIGINT,
    detail      JSONB,
    dept_id     BIGINT          NOT NULL DEFAULT 1 REFERENCES departments(id) ON DELETE RESTRICT,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW()
);

-- Indexes
-- =============================================================================

-- departments
CREATE INDEX IF NOT EXISTS idx_departments_parent_id ON departments(parent_id);
CREATE INDEX IF NOT EXISTS idx_departments_tenant_id ON departments(tenant_id);

-- documents
CREATE INDEX IF NOT EXISTS idx_documents_kb_id    ON documents(kb_id);
CREATE INDEX IF NOT EXISTS idx_documents_status   ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_tenant_dept ON documents(tenant_id, dept_id);

-- chunks
CREATE INDEX IF NOT EXISTS idx_chunks_doc_id      ON chunks(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunks_tenant_dept ON chunks(tenant_id, dept_id);

-- conversations
CREATE INDEX IF NOT EXISTS idx_conversations_memory_id ON conversations(memory_id);
CREATE INDEX IF NOT EXISTS idx_conversations_user_id   ON conversations(user_id);
CREATE INDEX IF NOT EXISTS idx_conversations_tenant_dept ON conversations(tenant_id, dept_id);

-- knowledge_bases
CREATE INDEX IF NOT EXISTS idx_kb_tenant_dept     ON knowledge_bases(tenant_id, dept_id);

-- audit_logs
CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_user ON audit_logs(tenant_id, user_id);

-- lookup composites
CREATE INDEX IF NOT EXISTS idx_users_tenant_dept  ON users(tenant_id, dept_id);
