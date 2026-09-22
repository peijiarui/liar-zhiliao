# 向量数据一次性重建（kbId metadata 补写后）

> 背景：2026-09 起入库向量 metadata 携带 kbId，检索按可见知识库过滤。
> 旧向量无 kbId，必须重建，否则：普通用户检索不到旧文档；admin 检索会命中已删除文档的孤儿向量。
> 前置：部署包含本次改动的新版本（metadata 已带 kbId、reprocess 已发 MQ）。

## 步骤

1. 清空 PG 切片表（父+子切片全部失效，需重新切分）：
   ```bash
   docker exec -it zhiliao-postgres psql -U <user> -d zhiliao -c "TRUNCATE zl_chunk;"
   ```
   注：`<user>` 需按实际部署确认，两个候选值：docker/local-dev.yml 中 `POSTGRES_USER: zhiliao`，application.yaml 中 `username: peijiarui`。

2. drop Milvus collection（旧向量全部失效）。临时开放 HTTP 端口：
   ```bash
   # 编辑 docker/local-dev.yml，取消 zhiliao-milvus 9091 端口映射注释，然后：
   docker compose -f docker/local-dev.yml up -d milvus
   curl -X POST http://localhost:9091/v2/vectordb/collections/drop \
        -H 'Content-Type: application/json' \
        -d '{"collectionName": "zhiliao_chunks"}'
   # 恢复 9091 注释后再次 up -d（可选）
   ```
   注：compose 服务名为 `milvus`（容器名 zhiliao-milvus），up 命令须用服务名。
   collection 由 langchain4j starter 在应用下次写入时自动重建。

3. 全量重新处理（重新解析、切分、embedding，metadata 带 kbId）：
   ```bash
   # 取 access token（管理员登录）
   TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"loginName":"admin","password":"<密码>"}' | jq -r '.data.accessToken')
   # 逐个触发 reprocess
   docker exec -it zhiliao-postgres psql -U <user> -d zhiliao -tAc \
        "SELECT id FROM zl_document WHERE status = 'COMPLETED';" | while read id; do
     curl -s -X POST "http://localhost:8080/api/admin/documents/$id/reprocess" \
          -H "Authorization: Bearer $TOKEN"
   done
   ```

4. 观察处理完成：
   ```bash
   docker exec -it zhiliao-postgres psql -U <user> -d zhiliao -c \
        "SELECT status, COUNT(*) FROM zl_document GROUP BY status;"
   # 全部 COMPLETED 即完成
   ```

## 注意

- 重建窗口内（步骤 2-4 之间）知识检索结果不全或为空，属预期，建议低峰执行。
- 若文档状态为 FAILED/UPLOADED 且 MinIO 对象缺失，reprocess 会再次失败——人工核对该文档是否可删。
