package org.liar.zhiliao.retrieval.tools;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.retrieval.records.RetrievalPrincipal;
import org.liar.zhiliao.retrieval.records.RankedChunk;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;
import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.liar.zhiliao.retrieval.service.Reranker;
import org.liar.zhiliao.retrieval.service.RetrievalCacheService;
import org.liar.zhiliao.retrieval.service.RetrievalMetrics;
import org.liar.zhiliao.retrieval.service.SparseSearcher;
import org.liar.zhiliao.vector.KbAwareEmbeddingStore;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalToolTest {

    @Mock EmbeddingModel embeddingModel;
    @Mock KbAwareEmbeddingStore milvusEmbeddingStore;
    @Mock SparseSearcher sparseSearcher;
    @Mock Reranker reranker;
    @Mock ChunkRepository chunkRepository;
    @Mock ChatModel chatModel;
    @Mock RetrievalCacheService retrievalCacheService;

    // 真实 metrics（SimpleMeterRegistry）：工具 happy path 会调用 startTimer().stop()
    // 与 getEmptyResult().increment()，mock 默认返回 null 会 NPE
    RetrievalMetrics retrievalMetrics = new RetrievalMetrics(new SimpleMeterRegistry());

    KnowledgeRetrievalTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeRetrievalTool(embeddingModel, milvusEmbeddingStore, sparseSearcher,
                reranker, chunkRepository, chatModel, retrievalCacheService, retrievalMetrics);
    }

    private void stubHappyPath() {
        when(retrievalCacheService.getRewrite(anyString())).thenReturn("请假流程\n年假天数");
        // 显式打桩缓存未命中为 null：Mockito 对 List 返回值默认给空列表，
        // 而工具把非 null 视为缓存命中直接返回，会导致检索路径被跳过
        when(retrievalCacheService.getCachedRetrieval(anyString(), anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(milvusEmbeddingStore.search(any(EmbeddingSearchRequest.class), any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        when(sparseSearcher.search(anyString(), anyInt(), any()))
                .thenReturn(List.<SparseSearchResult>of());
        when(reranker.rerank(anyString(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void shouldDenyRetrievalWhenSessionMissing() {
        when(chunkRepository.findPrincipalByMemoryId("conv-x")).thenReturn(null);

        String result = tool.retrieveKnowledge("conv-x", "请假流程");

        assertEquals("", result);
        verifyNoInteractions(embeddingModel, milvusEmbeddingStore, sparseSearcher);
    }

    @Test
    void shouldDenyRetrievalWhenNoVisibleKb() {
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of());

        String result = tool.retrieveKnowledge("conv-1", "请假流程");

        assertEquals("", result);
        verifyNoInteractions(embeddingModel, milvusEmbeddingStore, sparseSearcher);
    }

    @Test
    void userRetrievalShouldFilterByVisibleKbIds() {
        stubHappyPath();
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of(1L, 3L));

        tool.retrieveKnowledge("conv-1", "请假流程");

        ArgumentCaptor<EmbeddingSearchRequest> req =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(milvusEmbeddingStore, atLeastOnce()).search(req.capture(), eq(List.of(1L, 3L)));
        // 过滤改由 kbIds 参数承载，请求本身不再挂 metadata Filter
        assertNull(req.getValue().filter());
        // 稀疏路按部门过滤
        verify(sparseSearcher, atLeastOnce()).search(anyString(), anyInt(), eq(List.of(2L)));
        // 缓存后缀为部门 ID
        verify(retrievalCacheService, atLeastOnce())
                .getCachedRetrieval(anyString(), eq("2"));
    }

    @Test
    void adminRetrievalShouldSkipFiltering() {
        stubHappyPath();
        when(chunkRepository.findPrincipalByMemoryId("conv-a"))
                .thenReturn(new RetrievalPrincipal(1L, "ADMIN", 1L));

        tool.retrieveKnowledge("conv-a", "请假流程");

        verify(milvusEmbeddingStore, atLeastOnce())
                .search(any(EmbeddingSearchRequest.class), isNull());
        verify(sparseSearcher, atLeastOnce()).search(anyString(), anyInt(), isNull());
        verify(retrievalCacheService, atLeastOnce())
                .getCachedRetrieval(anyString(), eq("all"));
    }

    @Test
    void singleVisibleKbPassesSingleElementList() {
        stubHappyPath();
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of(7L));

        tool.retrieveKnowledge("conv-1", "请假流程");

        verify(milvusEmbeddingStore, atLeastOnce())
                .search(any(EmbeddingSearchRequest.class), eq(List.of(7L)));
    }

    @Test
    void cachedRetrievalShouldBypassSearch() {
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of(1L));
        when(retrievalCacheService.getCachedRetrieval(anyString(), eq("2")))
                .thenReturn(List.of(new RankedChunk(11L, "内容", null, 0.9)));

        String context = tool.retrieveKnowledge("conv-1", "请假流程");

        assertEquals("内容", context);
        verifyNoInteractions(embeddingModel, milvusEmbeddingStore, sparseSearcher);
    }
}
