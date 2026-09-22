package org.liar.zhiliao.retrieval.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.retrieval.records.RetrievalPrincipal;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkRepositoryTest {

    @Mock JdbcTemplate jdbcTemplate;
    ChunkRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChunkRepository(jdbcTemplate);
    }

    @Test
    void findPrincipalShouldReturnPrincipalWhenSessionExists() {
        when(jdbcTemplate.query(anyString(), any(DataClassRowMapper.class), eq("conv-1")))
                .thenReturn(List.of(new RetrievalPrincipal(1L, "ADMIN", 1L)));

        RetrievalPrincipal principal = repository.findPrincipalByMemoryId("conv-1");

        assertNotNull(principal);
        assertTrue(principal.isAdmin());
        assertEquals(1L, principal.deptId());
    }

    @Test
    void findPrincipalShouldReturnNullWhenSessionMissing() {
        when(jdbcTemplate.query(anyString(), any(DataClassRowMapper.class), eq("conv-x")))
                .thenReturn(List.of());

        assertNull(repository.findPrincipalByMemoryId("conv-x"));
    }

    @Test
    void findVisibleKbIdsShouldReturnKbIds() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(2L)))
                .thenReturn(List.of(1L, 3L));

        assertEquals(List.of(1L, 3L), repository.findVisibleKbIds(2L));
    }
}
