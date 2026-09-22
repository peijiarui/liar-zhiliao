package org.liar.zhiliao.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilvusKbEmbeddingStoreTest {

    @Mock MilvusServiceClient client;

    MilvusKbEmbeddingStore store;

    @BeforeEach
    void setUp() {
        store = new MilvusKbEmbeddingStore(client, "zhiliao_chunks");
    }

    private static EmbeddingSearchRequest request() {
        return EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f}))
                .maxResults(10)
                .minScore(0.7)
                .build();
    }

    // ---- expr 构造 ----

    @Test
    void searchWithoutKbFilterHasNoExpr() {
        assertEquals("", store.buildSearchParam(request(), null).getExpr());
    }

    @Test
    void searchWithSingleKbIdUsesEquality() {
        assertEquals("kb_id == 1", store.buildSearchParam(request(), List.of(1L)).getExpr());
    }

    @Test
    void searchWithMultipleKbIdsUsesIn() {
        assertEquals("kb_id in [1,3]", store.buildSearchParam(request(), List.of(1L, 3L)).getExpr());
    }

    @Test
    void buildSearchParamUsesExpectedTopKVectorFieldAndOutFields() {
        SearchParam param = store.buildSearchParam(request(), null);

        assertEquals(10, param.getTopK());
        assertEquals("vector", param.getVectorFieldName());
        assertEquals(List.of("id", "text", "metadata"), param.getOutFields());
    }

    // ---- 短路与防误用 ----

    @Test
    void searchWithEmptyKbIdsReturnsEmptyWithoutTouchingMilvus() {
        EmbeddingSearchResult<TextSegment> result = store.search(request(), List.of());

        assertTrue(result.matches().isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void searchWithMetadataFilterIsRejected() {
        EmbeddingSearchRequest withFilter = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f}))
                .maxResults(10)
                .minScore(0.7)
                .filter(metadataKey("kbId").isEqualTo("1"))
                .build();

        assertThrows(UnsupportedOperationException.class, () -> store.search(withFilter));
        verifyNoInteractions(client);
    }

    @Test
    void everyWriteEntryWithoutKbIdIsRejected() {
        Embedding embedding = Embedding.from(new float[]{0.1f});
        TextSegment segment = TextSegment.from("文本");

        assertThrows(UnsupportedOperationException.class, () -> store.add(embedding));
        assertThrows(UnsupportedOperationException.class, () -> store.add("v1", embedding));
        assertThrows(UnsupportedOperationException.class, () -> store.add(embedding, segment));
        assertThrows(UnsupportedOperationException.class, () -> store.addAll(List.of(embedding)));
        assertThrows(UnsupportedOperationException.class,
                () -> store.addAll(List.of("v1"), List.of(embedding), List.of(segment)));
        verifyNoInteractions(client);
    }

    // ---- 写入 ----

    @Test
    void addAllWritesKbIdAsSeparateFieldAndMetadataWithoutKbId() {
        when(client.insert(any(InsertParam.class))).thenReturn(R.success());

        List<String> ids = store.addAll(
                List.of(Embedding.from(new float[]{0.1f})),
                List.of(TextSegment.from("文本", Metadata.from("chunkId", "11").put("parentId", "10"))),
                7L);

        assertEquals(1, ids.size());
        ArgumentCaptor<InsertParam> captor = ArgumentCaptor.forClass(InsertParam.class);
        verify(client).insert(captor.capture());

        Map<String, List<?>> fields = captor.getValue().getFields().stream()
                .collect(Collectors.toMap(InsertParam.Field::getName, InsertParam.Field::getValues));

        assertEquals(List.of(7L), fields.get("kb_id"));
        assertEquals(List.of("文本"), fields.get("text"));
        assertEquals(ids, fields.get("id"));

        String metadataJson = String.valueOf(((List<?>) fields.get("metadata")).get(0));
        assertTrue(metadataJson.contains("chunkId"));
        assertFalse(metadataJson.contains("kbId"));
    }

    // ---- 删除 ----

    @Test
    void removeAllBuildsIdInExpression() {
        when(client.delete(any(DeleteParam.class))).thenReturn(R.success());

        store.removeAll(List.of("v1", "v2"));

        ArgumentCaptor<DeleteParam> captor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(client).delete(captor.capture());
        assertEquals("id in [\"v1\",\"v2\"]", captor.getValue().getExpr());
    }

    @Test
    void removeAllWithEmptyIdsTouchesNothing() {
        store.removeAll(List.of());

        verifyNoInteractions(client);
    }
}
