package org.liar.zhiliao.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.DescIndexResponseWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 负责 zhiliao_chunks 的建表、建索引、load 与启动期 schema 校验。
 * 绝不自动 drop：schema 不符一律抛异常交由运维按 docs/ops/rebuild-vectors.md 处理。
 */
@Slf4j
class MilvusCollectionManager {

    private static final int ID_MAX_LENGTH = 36;
    private static final int TEXT_MAX_LENGTH = 65535;
    /** 已移除的建表属性：isolation=true 时服务端强制 search/delete 的 expr 必须含分区键，与 admin 全量检索冲突；仅用于检测旧 collection 遗留 */
    private static final String ISOLATION_PROPERTY = "partitionkey.isolation";
    private static final String HNSW_EXTRA_PARAM = "{\"M\":16,\"efConstruction\":200}";

    private final MilvusServiceClient client;
    private final String collectionName;
    private final int dimension;
    private final int numPartitions;

    MilvusCollectionManager(MilvusServiceClient client, String collectionName, int dimension, int numPartitions) {
        this.client = client;
        this.collectionName = collectionName;
        this.dimension = dimension;
        this.numPartitions = numPartitions;
    }

    void ensure() {
        if (hasCollection()) {
            validateExisting();
        } else {
            createCollection();
            createVectorIndex();
            log.info("Milvus collection {} created: dimension={}, numPartitions={}, vectorIndex=HNSW/COSINE, partitionKey={}",
                    collectionName, dimension, numPartitions, MilvusSchema.KB_ID_FIELD);
        }
        MilvusResponses.check(client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName).build()), "loadCollection");
    }

    /**
     * 校验既有 collection：必须有 kb_id 分区键字段，且向量维度与配置一致。
     */
    static void validateSchema(List<FieldType> fields, int expectedDimension, String collectionName) {
        FieldType kbField = fields.stream()
                .filter(field -> MilvusSchema.KB_ID_FIELD.equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        mismatch(collectionName, "missing field '" + MilvusSchema.KB_ID_FIELD + "'")));

        if (!kbField.isPartitionKey()) {
            throw new IllegalStateException(
                    mismatch(collectionName, "field '" + MilvusSchema.KB_ID_FIELD + "' is not a partition key"));
        }

        FieldType vectorField = fields.stream()
                .filter(field -> MilvusSchema.VECTOR_FIELD.equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        mismatch(collectionName, "missing field '" + MilvusSchema.VECTOR_FIELD + "'")));

        if (vectorField.getDimension() != expectedDimension) {
            throw new IllegalStateException(mismatch(collectionName,
                    "vector dimension is " + vectorField.getDimension() + " but configured " + expectedDimension));
        }
    }

    private static String mismatch(String collectionName, String detail) {
        return "Milvus collection " + collectionName + " schema mismatch: " + detail
                + ". 请先 drop collection 后重启（见 docs/ops/rebuild-vectors.md）";
    }

    private boolean hasCollection() {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName).build());
        MilvusResponses.check(response, "hasCollection");
        return Boolean.TRUE.equals(response.getData());
    }

    private void createCollection() {
        CreateCollectionParam request = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withSchema(CollectionSchemaParam.newBuilder()
                        .addFieldType(FieldType.newBuilder()
                                .withName(MilvusSchema.ID_FIELD).withDataType(DataType.VarChar)
                                .withMaxLength(ID_MAX_LENGTH)
                                .withPrimaryKey(true).withAutoID(false).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(MilvusSchema.TEXT_FIELD).withDataType(DataType.VarChar)
                                .withMaxLength(TEXT_MAX_LENGTH).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(MilvusSchema.METADATA_FIELD).withDataType(DataType.JSON).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(MilvusSchema.VECTOR_FIELD).withDataType(DataType.FloatVector)
                                .withDimension(dimension).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(MilvusSchema.KB_ID_FIELD).withDataType(DataType.Int64)
                                .withPartitionKey(true).build())
                        .build())
                .withPartitionsNum(numPartitions)
                .build();
        MilvusResponses.check(client.createCollection(request), "createCollection");
    }

    private void createVectorIndex() {
        MilvusResponses.check(client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(MilvusSchema.VECTOR_FIELD)
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam(HNSW_EXTRA_PARAM)
                .build()), "createIndex");
    }

    private void validateExisting() {
        R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(collectionName).build());
        MilvusResponses.check(response, "describeCollection");
        validateSchema(new DescCollResponseWrapper(response.getData()).getFields(), dimension, collectionName);

        if (response.getData().getPropertiesList().stream()
                .anyMatch(kv -> ISOLATION_PROPERTY.equals(kv.getKey())
                        && "true".equalsIgnoreCase(kv.getValue()))) {
            throw new IllegalStateException(mismatch(collectionName,
                    "legacy '" + ISOLATION_PROPERTY + "'=true (it forces server-side expr to contain kb_id, "
                            + "breaking admin unfiltered search and delete-by-id)"));
        }

        if (isVectorIndexMissing()) {
            log.warn("Milvus collection {} has no index on {}; creating", collectionName, MilvusSchema.VECTOR_FIELD);
            createVectorIndex();
        }
    }

    private boolean isVectorIndexMissing() {
        R<DescribeIndexResponse> response = client.describeIndex(
                DescribeIndexParam.newBuilder().withCollectionName(collectionName).build());
        if (response == null || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            return true;
        }
        return new DescIndexResponseWrapper(response.getData())
                .getIndexDescByFieldName(MilvusSchema.VECTOR_FIELD) == null;
    }
}
