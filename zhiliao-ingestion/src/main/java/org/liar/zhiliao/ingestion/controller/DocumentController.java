package org.liar.zhiliao.ingestion.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.entity.Document;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.liar.zhiliao.ingestion.vo.response.DocumentRespVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
        Document doc = documentService.upload(file, kbId);
        log.info("Document uploaded: id={}, fileName={}, status={}",
                doc.getId(), doc.getFileName(), doc.getStatus());
        return ResponseEntity.ok(
                DocumentRespVO.builder()
                        .id(doc.getId())
                        .fileName(doc.getFileName())
                        .status(doc.getStatus())
                        .fileSize(doc.getFileSize())
                        .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDocument(@PathVariable Long id) {
        Document doc = documentService.getDocument(id);
        return ResponseEntity.ok(
                DocumentRespVO.builder()
                        .id(doc.getId())
                        .fileName(doc.getFileName())
                        .fileType(doc.getFileType())
                        .status(doc.getStatus())
                        .fileSize(doc.getFileSize())
                        .chunkCount(doc.getChunkCount())
                        .createdAt(doc.getCreatedAt())
                        .build()
        );
    }
}
