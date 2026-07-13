package org.liar.zhiliao.ingestion.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.liar.zhiliao.ingestion.vo.response.DocumentRespVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping
    public ResponseEntity<List<DocumentRespVO>> list(
            @RequestParam(value = "kbId", required = false) Long kbId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        List<ZlDocument> docs = documentService.listDocuments(kbId, page, pageSize);
        List<DocumentRespVO> result = docs.stream()
                .map(doc -> DocumentRespVO.builder()
                        .id(doc.getId())
                        .fileName(doc.getFileName())
                        .fileType(doc.getFileType())
                        .status(doc.getStatus())
                        .fileSize(doc.getFileSize())
                        .chunkCount(doc.getChunkCount())
                        .createdAt(doc.getCreatedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbId", defaultValue = "1") Long kbId) {
        ZlDocument doc = documentService.upload(file, kbId);
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
        ZlDocument doc = documentService.getDocument(id);
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
