package org.liar.zhiliao.vector;

import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储实现：kb_id 为顶层 Int64 分区键字段，检索按 kbId 收窄分区。
 * 取代 langchain4j-milvus 的 MilvusEmbeddingStore（其 schema 固定 4 字段、不支持分区键）。
 */
public class MilvusKbEmbeddingStore implements KbAwareEmbeddingStore {

    static final String KB_ID_REQUIRED_MESSAGE =
            "kbId required: use addAll(embeddings, segments, kbId)";

    private final MilvusServiceClient client;
    private final String collectionName;

    public MilvusKbEmbeddingStore(MilvusServiceClient client, String collectionName) {
        this.client = client;
        this.collectionName = collectionName;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, long kbId) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field(MilvusSchema.ID_FIELD, ids),
                new InsertParam.Field(MilvusSchema.TEXT_FIELD, texts(segments, embeddings.size())),
                new InsertParam.Field(MilvusSchema.METADATA_FIELD, metadataJsons(segments, embeddings.size())),
                new InsertParam.Field(MilvusSchema.VECTOR_FIELD,
                        embeddings.stream().map(Embedding::vectorAsList).toList()),
                new InsertParam.Field(MilvusSchema.KB_ID_FIELD,
                        Collections.nCopies(embeddings.size(), kbId)));

        MilvusResponses.check(client.insert(InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build()), "insert");

        return ids;
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String expr = MilvusSchema.ID_FIELD + " in [" + ids.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]";

        MilvusResponses.check(client.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build()), "delete");
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (request.filter() != null) {
            throw new UnsupportedOperationException(
                    "metadata Filter is not supported by this store: use search(request, kbIds)");
        }
        return search(request, null);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request, List<Long> kbIds) {
        if (kbIds != null && kbIds.isEmpty()) {
            return new EmbeddingSearchResult<>(List.of());
        }

        R<SearchResults> response = client.search(buildSearchParam(request, kbIds));
        MilvusResponses.check(response, "search");

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        return new EmbeddingSearchResult<>(
                MilvusMappers.toMatches(toRawHits(wrapper), request.minScore()));
    }

    /** 包可见以便单测直接断言 expr 与 outFields，无需真连 Milvus。 */
    SearchParam buildSearchParam(EmbeddingSearchRequest request, List<Long> kbIds) {
        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withFloatVectors(List.of(request.queryEmbedding().vectorAsList()))
                .withVectorFieldName(MilvusSchema.VECTOR_FIELD)
                .withTopK(request.maxResults())
                .withMetricType(MetricType.COSINE)
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .withOutFields(List.of(MilvusSchema.ID_FIELD, MilvusSchema.TEXT_FIELD,
                        MilvusSchema.METADATA_FIELD));

        String expr = MilvusMappers.buildKbExpr(kbIds);
        if (expr != null) {
            builder.withExpr(expr);
        }
        return builder.build();
    }

    private static List<MilvusMappers.RawHit> toRawHits(SearchResultsWrapper wrapper) {
        // RowRecord 实际类型是 QueryResultsWrapper.RowRecord，用 var 避免额外导入。
        // 用带参重载 getRowRecords(0)：无参重载已 @Deprecated（内部即委托 0），带参形式与之等价且无编译告警。
        var rows = wrapper.getRowRecords(0);
        if (rows.isEmpty()) {
            return List.of();
        }

        var idScores = wrapper.getIDScore(0);
        List<MilvusMappers.RawHit> hits = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object textField = rows.get(i).get(MilvusSchema.TEXT_FIELD);
            Object metadataField = rows.get(i).get(MilvusSchema.METADATA_FIELD);
            Map<String, Object> metadata = metadataField instanceof JsonObject json
                    ? MilvusMappers.parseMetadata(json)
                    : Map.of();
            hits.add(new MilvusMappers.RawHit(
                    idScores.get(i).getStrID(),
                    idScores.get(i).getScore(),
                    textField == null ? null : textField.toString(),
                    metadata));
        }
        return hits;
    }

    private static List<String> texts(List<TextSegment> segments, int size) {
        if (segments == null || segments.isEmpty()) {
            return Collections.nCopies(size, "");
        }
        return segments.stream().map(TextSegment::text).toList();
    }

    private static List<JsonObject> metadataJsons(List<TextSegment> segments, int size) {
        if (segments == null || segments.isEmpty()) {
            return Collections.nCopies(size, new JsonObject());
        }
        return segments.stream().map(segment -> MilvusMappers.toJson(segment.metadata())).toList();
    }

    // ---- 无法携带 kbId 的写入入口一律拒绝，避免分区键缺值 ----

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }
}
