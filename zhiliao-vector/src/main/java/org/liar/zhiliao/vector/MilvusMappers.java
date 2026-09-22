package org.liar.zhiliao.vector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.RelevanceScore;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE;

/**
 * Milvus 表达式构造与检索结果映射。纯函数，便于单测。
 * 结果映射语义逐字对齐 langchain4j-milvus 的 Mapper，避免替换后检索行为漂移。
 */
final class MilvusMappers {

    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(LONG_OR_DOUBLE).create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private MilvusMappers() {}

    /** 一条原始检索命中，尚未换算分数 */
    record RawHit(String id, double rawScore, String text, Map<String, Object> metadata) {}

    /**
     * @param kbIds null 表示不过滤
     * @return null 表示无 expr；否则 {@code kb_id == x} 或 {@code kb_id in [x,y]}
     * @throws IllegalArgumentException kbIds 为空列表（调用方必须提前短路）
     */
    static String buildKbExpr(List<Long> kbIds) {
        if (kbIds == null) {
            return null;
        }
        if (kbIds.isEmpty()) {
            throw new IllegalArgumentException("kbIds must not be empty; caller must short-circuit");
        }
        if (kbIds.size() == 1) {
            return MilvusSchema.KB_ID_FIELD + " == " + kbIds.get(0);
        }
        return MilvusSchema.KB_ID_FIELD + " in ["
                + kbIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    static Map<String, Object> parseMetadata(JsonObject json) {
        return json == null ? Map.of() : GSON.fromJson(json, MAP_TYPE);
    }

    static JsonObject toJson(Metadata metadata) {
        return GSON.toJsonTree(metadata.toMap()).getAsJsonObject();
    }

    /**
     * 换算 relevance score 并按 minScore 过滤。
     * COSINE 度量下 Milvus 返回余弦相似度，langchain4j 统一按 (cos + 1) / 2 映射到 [0,1]。
     */
    static List<EmbeddingMatch<TextSegment>> toMatches(List<RawHit> hits, double minScore) {
        return hits.stream()
                .map(hit -> new EmbeddingMatch<>(
                        RelevanceScore.fromCosineSimilarity(hit.rawScore()),
                        hit.id(),
                        null,
                        toTextSegment(hit)))
                .filter(match -> match.score() >= minScore)
                .toList();
    }

    private static TextSegment toTextSegment(RawHit hit) {
        if (hit.text() == null || hit.text().isBlank()) {
            return null;
        }
        return TextSegment.from(hit.text(), Metadata.from(hit.metadata()));
    }
}
