package org.liar.zhiliao.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 文档管理接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/documents")
public class AdminDocumentController {

    private final ZlDocumentMapper documentMapper;

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

    @PostMapping("/{id}/reprocess")
    public void reprocess(@PathVariable Long id) {
        ZlDocument doc = documentMapper.selectById(id);
        if (doc != null) {
            doc.setStatus("UPLOADED");
            documentMapper.updateById(doc);
        }
    }
}
