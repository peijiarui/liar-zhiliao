# 设计：知识库关联、文档管理与检索权限修复

- 日期：2026-09-22
- 状态：已获用户批准，待实现
- 范围：zhiliao-ingestion、zhiliao-retrieval、zhiliao-chat、zhiliao-app（SQL）、前端 liar-zhiliao-ui

## 1. 背景与问题

| # | 问题 | 根因 |
|---|------|------|
| 1 | 上传文档未与知识库关联 | 前端上传不传 kbId，API 封装硬编码 `kbId=1`；后端 upload 的 kbId 参数 `defaultValue = "1"`，无存在性校验 |
| 2 | 文档管理页无删除功能 | 后端不存在任何文档删除逻辑（Service/Controller 均无）；删除需清理 PG chunk、Milvus 向量、MinIO 对象三处 |
| 3 | 左侧菜单"文档"与"文档管理"两个页面重叠 | `Documents.vue`（/documents，普通用户：上传/列表/详情）与 `DocumentList.vue`（/admin/documents，管理员：状态筛选/重新处理）功能重叠且都无删除 |
| 4 | `KnowledgeRetrievalTool#retrieveKnowledge` 向量检索不按部门过滤，用户可访问无权限文档 | 写入 Milvus 的 metadata 只有 `chunkId/parentId`（无 kbId/deptId）；`EmbeddingSearchRequest` 无任何 Filter；工具方法依赖流式线程中不可靠的 `UserContextHolder` ThreadLocal。仅 PG BM25 稀疏路有部门过滤 |

## 2. 已确认的决策

1. **页面整合**：文档管理是纯管理员功能。合并为一个页面，仅管理员可预览和上传；普通用户菜单完全隐藏文档入口，后端不保留任何普通用户文档接口。
2. **删除策略**：物理删除（PG + Milvus + MinIO 三处彻底清理），操作列二次确认。
3. **权限过滤方案**：方案 A —— Milvus metadata 补写 kbId，检索时 `EmbeddingSearchRequest.filter` 过滤（与 BM25 稀疏路同一可见性语义），需全量重建存量向量。

## 3. 接口与页面整合

### 后端（文档接口全部收编到 /api/admin/*，由现有 AdminFilter（@Order(2)，校验 ADMIN 角色）保护）

| 接口 | 状态 |
|------|------|
| `POST /api/admin/documents/upload` | 从 `/api/documents/upload` 迁移 |
| `GET /api/admin/documents`（分页 + 状态筛选） | 已有 |
| `GET /api/admin/documents/{id}` | 从 `/api/documents/{id}` 迁移 |
| `DELETE /api/admin/documents/{id}` | 新增 |
| `POST /api/admin/documents/{id}/reprocess` | 已有 |

- 删除 `DocumentController`（`/api/documents/*` 整个移除）。
- `DocumentService` 接口相应调整：新增 `delete(Long id)`，`upload`/`getDocument` 迁移后仅供 admin 控制器调用；移除 `listDocuments`（admin 分页沿用现有 `AdminDocumentController` 的查询逻辑）。

### 前端（项目路径 `/Users/liar/Java/project/ui/liar-zhiliao-ui`）

- 删除 `src/views/Documents.vue`、`src/api/document.js`；`src/router/index.js` 移除 `/documents` 路由；`App.vue` menuOptions 移除"文档"菜单项（约 L97）。
- `src/views/admin/DocumentList.vue` 扩展为唯一文档管理页：
  - 顶部：上传按钮（n-upload + 知识库选择器）、状态筛选（已有）
  - 表格操作列：详情（预览抽屉）、删除（NPopconfirm 二次确认）、重新处理（已有）
- `src/api/admin.js` 增加 `uploadDocument`、`getDocument`、`deleteDocument` 封装。

## 4. 上传关联知识库

- 前端上传表单增加知识库下拉（n-select），数据源为已有 `GET /api/admin/knowledge-bases`；**必选、无默认值**；移除 `kbId=1` 硬编码。
- 后端 `upload()`：移除 `defaultValue = "1"`；kbId 为空返回 400；校验 kbId 存在（查 `zl_knowledge_base`），不存在返回 400。
- 上传流程其余逻辑不变（MinIO key `docs/{kbId}/{uuid}/{filename}` 本就含 kbId，保证 `zl_kb_dept_visibility(kbId, deptId)` 可见性记录，发 MQ 异步处理）。

## 5. 物理删除编排

`DocumentServiceImpl.delete(id)` 顺序执行，任一步失败即中止并抛出错误，已执行步骤不回滚（向前推进）：

```
1. 查 zl_document，不存在 → 404
2. 查 zl_chunk WHERE doc_id=? AND chunk_type='child' → embedding_id 列表
3. Milvus removeAll(embeddingIds)        ← 最难恢复，先做；失败则中止
4. PG 事务：DELETE zl_chunk WHERE doc_id=?；DELETE zl_document WHERE id=?
5. MinIO 删除 zl_document.minio_key 对象  ← 尽力而为，失败仅记 warn 日志（残留孤儿对象无害）
6. 发布 DocumentUpdateEvent → 清两级检索缓存（复用现有 DocumentUpdateEventListener）
```

要点：
- Milvus 删除用 `MilvusEmbeddingStore.removeAll(Collection<String> ids)`（1.17.0-beta27 已确认支持）。
- 父切片（parent）只存在于 PG，随 `zl_chunk` 按 doc_id 一并删除。
- MinIO 失败不阻断删除结果（文档已不可检索、不可见）。

## 6. 检索权限过滤（方案 A）

### 入库侧

`DocumentConsumerProcessor` 写 Milvus 的 TextSegment metadata 在现有 `chunkId`、`parentId` 基础上增加 `kbId`（以字符串形式写入，与 Milvus metadata JSON 表达式兼容）。

### 检索侧（`KnowledgeRetrievalTool#retrieveKnowledge`）

- 方法签名增加 `@MemoryId String memoryId` 参数（LangChain4j 自动传入会话 ID），**移除对 `UserContextHolder` ThreadLocal 的依赖**（流式工具执行线程不保证持有该上下文）。
- 身份解析链：`memoryId` → `zl_conversation`（按 `memory_id` 查 `user_id`、`dept_id`）→ `sys_user.role`。会话不存在则拒绝检索（返回空结果并记日志）。
- 权限分支：
  - `role=ADMIN`：稠密检索不加 Filter；BM25 SQL 部门过滤参数传 null（表示不过滤）。
  - 普通用户：`deptId` → `DeptPermissionService.getVisibleKbIds(deptId)`（查 `zl_kb_dept_visibility`）→ `metadataKey("kbId").in(kbIds)` 构造 `EmbeddingSearchRequest.filter`；BM25 SQL 维持现有部门过滤。
- 已确认 `langchain4j-milvus` 1.17.0-beta27 的 `MilvusMetadataFilterMapper` 支持 `IsIn`（映射为 `metadata["kbId"] in [...]` 表达式）。
- 检索缓存 key 已带部门后缀，语义不变，保留。
- 事务边界外说明：BM25 稀疏路（`ChunkRepository.searchBm25WithDeptFilter`）通过 `zl_chunk` → `zl_document` 关联到 KB 可见性（`zl_chunk` 无 kb_id 列），本设计不改动其过滤语义。

## 7. 存量数据重建（一次性迁移）

旧向量 metadata 无 kbId，必须重建。执行步骤：

1. 清空 `zl_chunk` 表（TRUNCATE）。
2. drop Milvus collection `zhiliao_chunks`（下次写入自动重建 collection）。
3. `zl_document` 行与 MinIO 对象保留；对全部文档触发 reprocess（发 MQ 走原有消费流程重新解析、切分、embedding，此时 metadata 已带 kbId）。

以 SQL + 手工操作执行一次，不新增代码接口。

## 8. 测试

### 单元测试（-DskipTests=false 运行）

- 删除编排：正常顺序执行；Milvus 失败中止且 PG 数据保留；文档不存在返回 404。
- 过滤构造：admin 不带 filter；普通用户按可见 KB 集合构造 `IsIn` filter；会话不存在返回空结果。
- 上传校验：kbId 缺失 400；kbId 不存在 400。

### 手工验证

- 管理员上传选择 KB → 对话提问命中该 KB 内容。
- 换另一部门用户提问，检索结果不越界（该部门不可见 KB 的知识不被引用）。
- 删除文档后检索不再命中，`zl_chunk`/Milvus/MinIO 三处数据消失。
- 普通用户：菜单无文档入口；直接请求 `/api/admin/*` 返回 403。

## 9. 风险与注意点

- **Milvus metadata 类型**：kbId 以字符串写入并匹配，避免数字/字符串类型不一致导致 filter 失效。
- **`@MemoryId` 工具参数**：实现时验证当前 LangChain4j 版本对 `@Tool` 方法 `@MemoryId` 参数的注入（1.x 主线版本支持，风险低）。
- **重建期间检索**：重建窗口内知识检索结果为空/不全，属预期，选低峰执行。
- **AdminFilter 已覆盖迁移后的路径**：`/api/admin/*` 前缀校验在 SessionFilter 之后执行（@Order(2)），迁移接口无需重复校验。
