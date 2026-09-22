# 向量数据一次性重建（kb_id 分区键 schema 变更后）

> 背景：2026-09 起 Milvus 中 kbId 由 metadata JSON 迁到顶层 Int64 分区键字段 `kb_id`，
> collection 为 5 字段（id/text/metadata/vector/kb_id）+ HNSW 索引 + 64 分区。
> 旧 collection 的 4 字段 schema 不兼容，必须 drop 后重建。
> 前置：部署包含本次改动的新版本（zhiliao-vector 模块，自研 store 取代 langchain4j-milvus starter）。

## 本机环境（2026-09-22 实测）

容器均为手工 `docker run` 启动，**没有使用**仓库的 `docker/local-dev.yml`（该文件中的 `zhiliao-*` 命名与实际不符）：

| 组件 | 容器名 | 镜像 | 端口 |
|------|--------|------|------|
| Milvus | `milvus-standalone` | `milvusdb/milvus:v2.6.18` | 19530（gRPC + REST 同端口） |
| PostgreSQL | `postgres16-zhparser` | `abcfy2/zhparser:16` | 5432，库 `zhiliao`，用户 `peijiarui` |
| Redis | `redis-stack` | `redis/redis-stack:7.2.0-v20` | 6379 |
| RabbitMQ | `rabbitmq` | `rabbitmq:3.13-management` | 5672 |
| MinIO | `minio` | `minio/minio:RELEASE.2025-09-07T16-13-09Z` | 9000 |
| attu（Milvus 控制台） | `attu` | `zilliz/attu:v3.0.0-beta.6` | 见 `docker port attu` |

**Milvus v2.6.18 ≥ 2.5.4**，`partitionkey.isolation` 可用，无需升级镜像。

## 顺序要求

新版**启动期会校验 schema，不符即拒绝启动**（不自动 drop）。因此 drop 必须发生在启动之前。
未执行 drop 时启动会报 `Milvus collection schema mismatch: missing field 'kb_id'`。

## 步骤

0. 停旧版应用（避免重建窗口内继续写入旧 schema 数据）。

1. drop Milvus collection `zhiliao_chunks`（旧向量全部失效）。两种方式任选：
   - **attu**（推荐，无需处理鉴权）：`docker port attu` 查端口后打开，选中 `zhiliao_chunks` → Drop Collection。
   - **REST**：
     ```bash
     # 若 Milvus 未开鉴权则省略 -H；开启则用 Bearer <username>:<password>
     curl -s -X POST http://localhost:19530/v2/vectordb/collections/drop \
          -H 'Content-Type: application/json' \
          -H 'Authorization: Bearer peijiarui:<密码>' \
          -d '{"collectionName": "zhiliao_chunks"}'
     ```

2. 清空 PG 切片表（父+子切片全部失效，需重新切分）：
   ```bash
   docker exec -it postgres16-zhparser psql -U peijiarui -d zhiliao -c "TRUNCATE zl_chunk;"
   ```

3. 保留 `zl_document` 行与 MinIO 对象不变。

4. 部署新版并启动。collection 由 `zhiliao-vector` 在**启动期**自动创建
   （64 分区 + HNSW/COSINE + `partitionkey.isolation=true`）。
   应用日志应出现 `Milvus collection zhiliao_chunks created` 与 `Milvus store ready`。

5. 全量重新处理（重新解析、切分、embedding，`kb_id` 作为独立字段写入）：
   ```bash
   # 取 access token（管理员登录）
   TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"loginName":"admin","password":"<密码>"}' | jq -r '.data.accessToken')
   # 逐个触发 reprocess
   docker exec -it postgres16-zhparser psql -U peijiarui -d zhiliao -tAc \
        "SELECT id FROM zl_document WHERE status = 'COMPLETED';" | while read id; do
     curl -s -X POST "http://localhost:8080/api/admin/documents/$id/reprocess" \
          -H "Authorization: Bearer $TOKEN"
   done
   ```
   注意：reprocess 通过 RabbitMQ 异步处理，需要应用、RabbitMQ、MinIO 均在运行。

6. 观察处理完成：
   ```bash
   docker exec -it postgres16-zhparser psql -U peijiarui -d zhiliao -c \
        "SELECT status, COUNT(*) FROM zl_document GROUP BY status;"
   # 全部 COMPLETED 即完成
   ```

7. 抽查 `kb_id` 已落为独立字段（管理员登录后向知识库提问，能命中新写入的文档）。

## 注意

- 重建窗口内（步骤 1-5 之间）知识检索结果不全或为空，属预期，建议低峰执行。
- 若文档状态为 FAILED/UPLOADED 且 MinIO 对象缺失，reprocess 会再次失败——人工核对该文档是否可删。
- 若启动日志报 `Milvus collection schema mismatch`，说明 drop 未生效或连到了错误的 Milvus 实例。
- 仓库 `docker/local-dev.yml` 与本机实际容器不一致，**不要**按该文件操作。
