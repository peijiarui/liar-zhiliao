-- =============================================================================
-- liar-zhiliao Database Schema
-- Target Database: PostgreSQL 14+
-- =============================================================================

-- =============================================================================
-- liar-zhiliao Database Schema
-- Target Database: PostgreSQL 14+
-- 规范：所有表和字段必须带 COMMENT，新建表优先使用 COMMENT ON
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
COMMENT ON TABLE  sys_department IS '部门组织架构树';
COMMENT ON COLUMN sys_department.name IS '部门名称';
COMMENT ON COLUMN sys_department.parent_id IS '父部门 ID，NULL 表示根部门';
COMMENT ON COLUMN sys_department.tenant_id IS '多租户标识';
COMMENT ON COLUMN sys_department.created_at IS '创建时间';

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
COMMENT ON TABLE  sys_user IS '系统用户表';
COMMENT ON COLUMN sys_user.login_name IS '登录名（唯一）';
COMMENT ON COLUMN sys_user.password_hash IS 'BCrypt 密码哈希';
COMMENT ON COLUMN sys_user.name IS '用户显示名';
COMMENT ON COLUMN sys_user.email IS '邮箱，用于 OAuth 自动合并';
COMMENT ON COLUMN sys_user.phone IS '手机号';
COMMENT ON COLUMN sys_user.dept_id IS '所属部门 ID';
COMMENT ON COLUMN sys_user.role IS '角色：USER 普通用户 / ADMIN 管理员';
COMMENT ON COLUMN sys_user.tenant_id IS '多租户标识';
COMMENT ON COLUMN sys_user.created_at IS '创建时间';

-- 3. OAuth 关联
-- =============================================================================

CREATE TABLE IF NOT EXISTS sys_oauth_link (
    id                BIGSERIAL       PRIMARY KEY,
    user_id           BIGINT          NOT NULL,
    provider          VARCHAR(20)     NOT NULL,
    provider_user_id  VARCHAR(100)    NOT NULL,
    provider_email    VARCHAR(200),
    created_at        TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (provider, provider_user_id)
);
COMMENT ON TABLE  sys_oauth_link IS 'OAuth 关联表：GitHub/钉钉/微信账号到本地用户的映射';
COMMENT ON COLUMN sys_oauth_link.user_id IS '关联 sys_user.id';
COMMENT ON COLUMN sys_oauth_link.provider IS 'OAuth 提供商：github / dingtalk / wechat(预留)';
COMMENT ON COLUMN sys_oauth_link.provider_user_id IS '第三方平台中的用户唯一 ID';
COMMENT ON COLUMN sys_oauth_link.provider_email IS 'OAuth 返回的邮箱，用于跨提供商自动合并';

-- 4. Knowledge Bases
-- =============================================================================

CREATE TABLE IF NOT EXISTS zl_knowledge_base (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(200)    NOT NULL,
    description TEXT,
    dept_id     BIGINT          NOT NULL DEFAULT 1 ,
    tenant_id   VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at  TIMESTAMPTZ       DEFAULT NOW()
);
COMMENT ON TABLE  zl_knowledge_base IS '知识库元数据表';
COMMENT ON COLUMN zl_knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN zl_knowledge_base.description IS '知识库描述';
COMMENT ON COLUMN zl_knowledge_base.dept_id IS '所属部门 ID';
COMMENT ON COLUMN zl_knowledge_base.tenant_id IS '多租户标识';
COMMENT ON COLUMN zl_knowledge_base.created_at IS '创建时间';

-- 5. 知识库-部门可见性
-- =============================================================================

CREATE TABLE IF NOT EXISTS zl_kb_dept_visibility (
    id         BIGSERIAL       PRIMARY KEY,
    kb_id      BIGINT          NOT NULL,
    dept_id    BIGINT          NOT NULL,
    created_at TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (kb_id, dept_id)
);
COMMENT ON TABLE  zl_kb_dept_visibility IS '知识库-部门可见性：一个知识库可被多个部门查看';
COMMENT ON COLUMN zl_kb_dept_visibility.kb_id IS '关联 zl_knowledge_base.id';
COMMENT ON COLUMN zl_kb_dept_visibility.dept_id IS '关联 sys_department.id';

-- 6. Documents
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
COMMENT ON TABLE  zl_document IS '文档信息表：记录上传文档的元数据和 MinIO 存储位置';
COMMENT ON COLUMN zl_document.kb_id IS '所属知识库 ID';
COMMENT ON COLUMN zl_document.file_name IS '原始文件名';
COMMENT ON COLUMN zl_document.file_type IS '文件 MIME 类型';
COMMENT ON COLUMN zl_document.status IS '处理状态：UPLOADED / PROCESSING / COMPLETED / FAILED';
COMMENT ON COLUMN zl_document.minio_key IS 'MinIO 对象存储 Key';
COMMENT ON COLUMN zl_document.file_size IS '文件大小（字节）';
COMMENT ON COLUMN zl_document.md5 IS '文件 MD5 哈希，用于增量同步指纹';
COMMENT ON COLUMN zl_document.chunk_count IS '文档切片数（parent 数）';
COMMENT ON COLUMN zl_document.dept_id IS '所属部门 ID';
COMMENT ON COLUMN zl_document.tenant_id IS '多租户标识';
COMMENT ON COLUMN zl_document.created_at IS '创建时间';

-- 7. Chunks (document segments with embeddings)
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
COMMENT ON TABLE  zl_chunk IS '文档切片表：存储父子文档的文本内容和向量 ID 映射';
COMMENT ON COLUMN zl_chunk.doc_id IS '关联 zl_document.id';
COMMENT ON COLUMN zl_chunk.content IS '切片文本内容';
COMMENT ON COLUMN zl_chunk.embedding_id IS 'Milvus 向量 ID，用于检索匹配';
COMMENT ON COLUMN zl_chunk.metadata IS '元数据 JSON：fileName、index 等';
COMMENT ON COLUMN zl_chunk.parent_id IS '父切片 ID，child 指向 parent，实现父子文档模式';
COMMENT ON COLUMN zl_chunk.chunk_type IS '切片类型：parent（2048 token）/ child（512 token）';
COMMENT ON COLUMN zl_chunk.dept_id IS '所属部门 ID';
COMMENT ON COLUMN zl_chunk.tenant_id IS '多租户标识';
COMMENT ON COLUMN zl_chunk.created_at IS '创建时间';

-- 8. Conversations (chat sessions)
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
COMMENT ON TABLE zl_conversation IS '对话记录表：每个 memory_id 对应一个独立会话';
COMMENT ON COLUMN zl_conversation.memory_id IS '会话 memory ID，格式 conv-{UUID}，关联 LangChain4j ChatMemory';
COMMENT ON COLUMN zl_conversation.user_id IS '发起用户 ID';
COMMENT ON COLUMN zl_conversation.title IS '对话标题（AI 自动生成）';
COMMENT ON COLUMN zl_conversation.message_count IS '消息总数';
COMMENT ON COLUMN zl_conversation.dept_id IS '所属部门 ID';
COMMENT ON COLUMN zl_conversation.tenant_id IS '多租户标识';
COMMENT ON COLUMN zl_conversation.created_at IS '创建时间';

-- 8. Conversations — 追加字段
ALTER TABLE zl_conversation ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE zl_conversation ADD UNIQUE (memory_id);

COMMENT ON COLUMN zl_conversation.updated_at IS '最后活动时间，每次收发消息时更新，对话列表按此字段降序排列';
CREATE INDEX IF NOT EXISTS idx_zl_conversation_updated_at ON zl_conversation(updated_at DESC);

-- 9. Audit Logs
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
COMMENT ON TABLE  zl_audit_log IS '审计日志表：记录管理员操作和关键业务变更';
COMMENT ON COLUMN zl_audit_log.user_id IS '操作用户 ID';
COMMENT ON COLUMN zl_audit_log.action IS '操作类型：CREATE / UPDATE / DELETE / LOGIN 等';
COMMENT ON COLUMN zl_audit_log.target_type IS '操作对象类型：knowledge_base / document / user';
COMMENT ON COLUMN zl_audit_log.target_id IS '操作对象 ID';
COMMENT ON COLUMN zl_audit_log.detail IS '操作详情 JSON';
COMMENT ON COLUMN zl_audit_log.dept_id IS '所属部门 ID';
COMMENT ON COLUMN zl_audit_log.tenant_id IS '多租户标识';
COMMENT ON COLUMN zl_audit_log.created_at IS '创建时间';

-- 10. RBAC 权限模型（预留，Phase 3 不启用，仅建表预留结构）
-- =============================================================================
-- 当前管理后台使用 sys_user.role 字段做简单角色校验，
-- 后续迁移到 RBAC 时启用以下三张表。迁移步骤：
--   1. sys_user_role 存用户-角色关联
--   2. AdminFilter 中 `user.role` 校验改为查 sys_role_permission
--   3. 删除 sys_user.role 字段
-- =============================================================================

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(50)     NOT NULL UNIQUE,
    description VARCHAR(200),
    created_at  TIMESTAMPTZ     DEFAULT NOW()
);
COMMENT ON TABLE  sys_role IS '角色表（预留 RBAC）';
COMMENT ON COLUMN sys_role.name IS '角色标识：ADMIN / USER / DEPT_ADMIN';
COMMENT ON COLUMN sys_role.description IS '角色说明';

CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGSERIAL       PRIMARY KEY,
    code        VARCHAR(100)    NOT NULL UNIQUE,
    name        VARCHAR(100)    NOT NULL,
    created_at  TIMESTAMPTZ     DEFAULT NOW()
);
COMMENT ON TABLE  sys_permission IS '权限表（预留 RBAC）';
COMMENT ON COLUMN sys_permission.code IS '权限编码：kb:create, kb:delete, user:manage, admin:access';
COMMENT ON COLUMN sys_permission.name IS '权限名称';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id              BIGSERIAL   PRIMARY KEY,
    role_id         BIGINT      NOT NULL REFERENCES sys_role(id),
    permission_id   BIGINT      NOT NULL REFERENCES sys_permission(id),
    UNIQUE (role_id, permission_id)
);
COMMENT ON TABLE  sys_role_permission IS '角色权限关联表（预留 RBAC）';
COMMENT ON COLUMN sys_role_permission.role_id IS '关联 sys_role.id';
COMMENT ON COLUMN sys_role_permission.permission_id IS '关联 sys_permission.id';

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
