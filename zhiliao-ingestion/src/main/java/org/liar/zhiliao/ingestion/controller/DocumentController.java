package org.liar.zhiliao.ingestion.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.liar.zhiliao.ingestion.vo.response.DocumentResponse;
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
    public List<DocumentResponse> list(
            @RequestParam(value = "kbId", required = false) Long kbId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        return documentService.listDocuments(kbId, page, pageSize).stream()
                .map(DocumentResponse::of)
                .toList();
    }

    @PostMapping("/upload")
    public DocumentResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbId", defaultValue = "1") Long kbId) {
        ZlDocument doc = documentService.upload(file, kbId);
        log.info("Document uploaded: id={}, fileName={}, status={}",
                doc.getId(), doc.getFileName(), doc.getStatus());
        return DocumentResponse.of(doc);
    }

    @GetMapping("/{id}")
    public DocumentResponse getDocument(@PathVariable Long id) {
        ZlDocument doc = documentService.getDocument(id);
        return DocumentResponse.of(doc);
    }
}
