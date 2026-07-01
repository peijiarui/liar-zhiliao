package org.liar.ai.liarrag.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Slf4j
@Component
public class LocalFileStore {

    private static final String FILE_EXT = ".json";
    private static final String SUB_DIR = "memory";

    private final Path storageDir;

    public LocalFileStore() {
        this(Paths.get("data", SUB_DIR));
    }

    public LocalFileStore(Path storageDir) {
        this.storageDir = storageDir;
    }

    private Path filePath(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        return storageDir.resolve(memoryId + FILE_EXT);
    }

    public void save(String memoryId, String content) {
        try {
            ensureDir();
            Path file = filePath(memoryId);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("Saved file: {}", file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save file for memoryId: " + memoryId, e);
        }
    }

    public Optional<String> read(String memoryId) {
        Path file = filePath(memoryId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for memoryId: " + memoryId, e);
        }
    }

    public void delete(String memoryId) {
        try {
            Path file = filePath(memoryId);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file for memoryId: " + memoryId, e);
        }
    }

    private void ensureDir() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory: " + storageDir, e);
        }
    }
}
