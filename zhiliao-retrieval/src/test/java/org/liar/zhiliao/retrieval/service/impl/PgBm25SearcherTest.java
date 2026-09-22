package org.liar.zhiliao.retrieval.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgBm25SearcherTest {

    @Mock ChunkRepository chunkRepository;
    PgBm25Searcher searcher;

    @BeforeEach
    void setUp() {
        searcher = new PgBm25Searcher(chunkRepository);
    }

    @Test
    void nullDeptIdsMeansUnfilteredSearch() {
        searcher.search("q", 10, null);
        verify(chunkRepository).searchBm25("q", 10);
        verify(chunkRepository, never()).searchBm25WithDeptFilter(anyString(), anyInt(), anyList());
    }

    @Test
    void emptyDeptIdsMeansEmptyResult() {
        var result = searcher.search("q", 10, List.of());
        assert result.isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    void nonEmptyDeptIdsMeansFilteredSearch() {
        searcher.search("q", 10, List.of(2L));
        verify(chunkRepository).searchBm25WithDeptFilter("q", 10, List.of(2L));
    }
}
