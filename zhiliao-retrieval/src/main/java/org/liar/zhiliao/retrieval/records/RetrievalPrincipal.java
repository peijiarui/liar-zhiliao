package org.liar.zhiliao.retrieval.records;

/**
 * 检索主体：从会话 memoryId 解析出的用户身份。
 * role 取自 sys_user 实时值，避免会话创建后角色变更导致权限漂移。
 */
public record RetrievalPrincipal(Long userId, String role, Long deptId) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
