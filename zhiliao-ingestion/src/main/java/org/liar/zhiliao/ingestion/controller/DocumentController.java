package org.liar.zhiliao.ingestion.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.model.Document;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbId", defaultValue = "1") Long kbId) {
        try {
            Document doc = documentService.upload(file, kbId);
            log.info("Document uploaded: id={}, fileName={}, status={}",
                    doc.getId(), doc.getFileName(), doc.getStatus());
            return ResponseEntity.ok(Map.of(
                    "id", doc.getId(),
                    "fileName", doc.getFileName(),
                    "status", doc.getStatus(),
                    "fileSize", doc.getFileSize()
            ));
        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDocument(@PathVariable Long id) {
        try {
            Document doc = documentService.getDocument(id);
            return ResponseEntity.ok(Map.of(
                    "id", doc.getId(),
                    "fileName", doc.getFileName(),
                    "fileType", doc.getFileType(),
                    "status", doc.getStatus(),
                    "fileSize", doc.getFileSize(),
                    "chunkCount", doc.getChunkCount(),
                    "createdAt", doc.getCreatedAt()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
