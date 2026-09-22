package org.liar.zhiliao.vector;

import com.google.gson.JsonObject;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MilvusMappersTest {

    // ---- buildKbExpr ----

    @Test
    void buildKbExprReturnsNullWhenKbIdsIsNull() {
        assertNull(MilvusMappers.buildKbExpr(null));
    }

    @Test
    void buildKbExprUsesEqualityForSingleKbId() {
        assertEquals("kb_id == 1", MilvusMappers.buildKbExpr(List.of(1L)));
    }

    @Test
    void buildKbExprUsesInForMultipleKbIds() {
        assertEquals("kb_id in [1,3]", MilvusMappers.buildKbExpr(List.of(1L, 3L)));
    }

    @Test
    void buildKbExprRejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> MilvusMappers.buildKbExpr(List.of()));
    }

    // ---- parseMetadata / toJson ----

    @Test
    void parseMetadataKeepsStringValuesAndHandlesNull() {
        JsonObject json = new JsonObject();
        json.addProperty("chunkId", "11");
        json.addProperty("parentId", "10");

        assertEquals(Map.of("chunkId", "11", "parentId", "10"), MilvusMappers.parseMetadata(json));
        assertEquals(Map.of(), MilvusMappers.parseMetadata(null));
    }

    @Test
    void toJsonRoundTripsMetadata() {
        JsonObject json = MilvusMappers.toJson(
                Metadata.from("chunkId", "11").put("parentId", "10"));

        assertEquals("11", json.get("chunkId").getAsString());
        assertEquals("10", json.get("parentId").getAsString());
    }

    // ---- toMatches ----

    @Test
    void toMatchesConvertsCosineToRelevanceScoreAtBoundary() {
        // cos = 0.4 -> (0.4 + 1) / 2 = 0.7，恰好等于阈值，必须保留
        List<EmbeddingMatch<TextSegment>> matches = MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.4, "文本", Map.of())), 0.7);

        assertEquals(1, matches.size());
        assertEquals(0.7, matches.get(0).score(), 1e-9);
        assertEquals("v1", matches.get(0).embeddingId());
        assertNull(matches.get(0).embedding());
        assertEquals("文本", matches.get(0).embedded().text());
    }

    @Test
    void toMatchesDropsHitsBelowMinScore() {
        // cos = 0.39 -> 0.695 < 0.7
        assertTrue(MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.39, "文本", Map.of())), 0.7).isEmpty());
    }

    @Test
    void toMatchesKeepsChunkAndParentIdWithoutKbId() {
        List<EmbeddingMatch<TextSegment>> matches = MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.9, "文本",
                        Map.of("chunkId", "11", "parentId", "10"))), 0.7);

        Metadata metadata = matches.get(0).embedded().metadata();
        assertEquals("11", metadata.getString("chunkId"));
        assertEquals("10", metadata.getString("parentId"));
        assertNull(metadata.getString("kbId"));
    }

    @Test
    void toMatchesReturnsNullSegmentForBlankText() {
        List<EmbeddingMatch<TextSegment>> matches = MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.9, "   ", Map.of())), 0.7);

        assertNull(matches.get(0).embedded());
    }
}
