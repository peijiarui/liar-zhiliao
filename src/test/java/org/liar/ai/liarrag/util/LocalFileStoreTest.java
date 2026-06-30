package org.liar.ai.liarrag.util;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class LocalFileStoreTest {

    @TempDir
    Path tempDir;

    private LocalFileStore store;

    @BeforeEach
    void setUp() {
        store = new LocalFileStore(tempDir, Duration.ofHours(24), null);
    }

    @Test
    void shouldSaveAndReadString() {
        store.save("test1", "hello world");
        Optional<String> result = store.read("test1", String.class);
        assertThat(result).hasValue("hello world");
    }

    @Test
    void shouldSaveAndReadList() {
        List<String> data = Arrays.asList("a", "b", "c");
        store.save("list-test", data);
        Optional<List<String>> result = store.read("list-test", new TypeReference<>() {});
        assertThat(result).hasValue(data);
    }

    @Test
    void shouldReturnEmptyWhenFileNotExists() {
        Optional<String> result = store.read("nonexistent", String.class);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldDeleteExpiredFileOnRead() throws IOException {
        // 写入文件
        store.save("expired", "data");
        Path file = tempDir.resolve("expired.json");
        assertThat(file).exists();

        // 手动修改文件最后修改时间为 25 小时前（模拟过期）
        Instant past = Instant.now().minus(Duration.ofHours(25));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(past));

        // 读取时应返回空并删除文件
        Optional<String> result = store.read("expired", String.class);
        assertThat(result).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void shouldSanitizeMemoryId() {
        store.save("../evil", "data");
        // 验证文件在 tempDir 下，而不是上级目录
        Path file = tempDir.resolve(".._evil.json");
        assertThat(file).exists();
    }

    @Test
    void shouldRejectBlankMemoryId() {
        assertThatThrownBy(() -> store.save("", "data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("   ", "data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(null, "data"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDeleteExistingFile() {
        store.save("to-delete", "data");
        store.delete("to-delete");
        assertThat(store.read("to-delete", String.class)).isEmpty();
    }

    @Test
    void shouldNotThrowWhenDeletingNonExistent() {
        assertThatCode(() -> store.delete("non-existent"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldCreateDirectoryOnFirstSave() {
        Path newDir = tempDir.resolve("sub/deep/nested");
        LocalFileStore nestedStore = new LocalFileStore(newDir, Duration.ofHours(24), null);
        nestedStore.save("test", "data");
        assertThat(newDir).exists();
        assertThat(newDir.resolve("test.json")).exists();
    }
}
