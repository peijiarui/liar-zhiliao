package org.liar.ai.liarrag.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
public class LocalFileStore {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String FILE_EXT = ".json";
    private static final String SUB_DIR = "memory";
    private static final String SAFE_CHARS = "[^a-zA-Z0-9._-]";

    private final ObjectMapper objectMapper;
    private final Path storageDir;
    private final Duration ttl;

    public LocalFileStore() {
        this(Paths.get("data", SUB_DIR), DEFAULT_TTL, createDefaultObjectMapper());
    }

    public LocalFileStore(Path storageDir, Duration ttl, ObjectMapper objectMapper) {
        this.storageDir = storageDir;
        this.ttl = ttl;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
    }

    private static ObjectMapper createDefaultObjectMapper() {
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("dev.langchain4j.data.message.")
                .allowIfSubType("java.util.")
                .build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);
        return mapper;
    }

    static String safeFileName(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        String sanitized = id.replaceAll("[/\\\\]", "_")
                .replaceAll(SAFE_CHARS, "_");
        // 防止目录遍历或特殊文件名
        if (sanitized.equals(".") || sanitized.equals("..") || sanitized.isBlank()) {
            sanitized = "unnamed";
        }
        return sanitized + FILE_EXT;
    }

    private Path filePath(String id) {
        return storageDir.resolve(safeFileName(id));
    }

    private boolean isExpired(Path file) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        Instant lastModified = Files.getLastModifiedTime(file).toInstant();
        return Duration.between(lastModified, Instant.now()).compareTo(ttl) > 0;
    }

    public <T> void save(String id, T data) {
        try {
            ensureDir();
            Path file = filePath(id);
            objectMapper.writeValue(file.toFile(), data);
            log.debug("Saved file: {}", file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save file for id: " + id, e);
        }
    }

    public <T> Optional<T> read(String id, Class<T> type) {
        try {
            Path file = filePath(id);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            if (isExpired(file)) {
                Files.deleteIfExists(file);
                log.debug("Deleted expired file: {}", file);
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(file.toFile(), type));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for id: " + id, e);
        }
    }

    public <T> Optional<T> read(String id, TypeReference<T> typeRef) {
        try {
            Path file = filePath(id);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            if (isExpired(file)) {
                Files.deleteIfExists(file);
                log.debug("Deleted expired file: {}", file);
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(file.toFile(), typeRef));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file for id: " + id, e);
        }
    }

    public void delete(String id) {
        try {
            Path file = filePath(id);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file for id: " + id, e);
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
