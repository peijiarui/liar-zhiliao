package org.liar.zhiliao.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.liar.zhiliao.ingestion.vo.response.DocumentResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理接口（仅管理员，由 AdminFilter 校验角色）。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/documents")
public class AdminDocumentController {

    private final ZlDocumentMapper documentMapper;
    private final DocumentService documentService;

    @GetMapping
    public IPage<ZlDocument> page(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) Long kbId,
                                  @RequestParam(required = false) String status) {
        var wrapper = new LambdaQueryWrapper<ZlDocument>();
        if (kbId != null) {
            wrapper.eq(ZlDocument::getKbId, kbId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(ZlDocument::getStatus, status);
        }
        wrapper.orderByDesc(ZlDocument::getCreatedAt);
        return documentMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @PostMapping("/upload")
    public DocumentResponse upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam("kbId") Long kbId) {
        ZlDocument doc = documentService.upload(file, kbId);
        log.info("Document uploaded: id={}, fileName={}, status={}",
                doc.getId(), doc.getFileName(), doc.getStatus());
        return DocumentResponse.of(doc);
    }

    @GetMapping("/{id}")
    public DocumentResponse detail(@PathVariable Long id) {
        return DocumentResponse.of(documentService.getDocument(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        documentService.delete(id);
    }

    @PostMapping("/{id}/reprocess")
    public void reprocess(@PathVariable Long id) {
        documentService.reprocess(id);
    }
}
