-- OAuth2 关联表：第三方账号到本地用户的映射
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
COMMENT ON COLUMN sys_oauth_link.provider_user_id IS '第三方平台中的用户唯一ID';
COMMENT ON COLUMN sys_oauth_link.provider_email IS 'OAuth 返回的邮箱，用于跨提供商自动合并';

-- 知识库-部门可见性：一个知识库可被多个部门查看
CREATE TABLE IF NOT EXISTS zl_kb_dept_visibility (
    id         BIGSERIAL       PRIMARY KEY,
    kb_id      BIGINT          NOT NULL,
    dept_id    BIGINT          NOT NULL,
    created_at TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (kb_id, dept_id)
);
COMMENT ON TABLE  zl_kb_dept_visibility IS '知识库-部门可见性：控制哪些部门可以查看特定知识库';
COMMENT ON COLUMN zl_kb_dept_visibility.kb_id IS '关联 zl_knowledge_base.id';
COMMENT ON COLUMN zl_kb_dept_visibility.dept_id IS '关联 sys_department.id';
