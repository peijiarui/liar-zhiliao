package org.liar.zhiliao.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.admin.entity.ZlKnowledgeBase;
import org.liar.zhiliao.admin.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库管理接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    public IPage<ZlKnowledgeBase> page(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return knowledgeBaseService.page(page, size);
    }

    @GetMapping("/{id}")
    public ZlKnowledgeBase getById(@PathVariable Long id) {
        return knowledgeBaseService.getById(id);
    }

    @PostMapping
    public void create(@RequestBody ZlKnowledgeBase kb) {
        knowledgeBaseService.create(kb);
    }

    @PutMapping("/{id}")
    public void update(@PathVariable Long id, @RequestBody ZlKnowledgeBase kb) {
        kb.setId(id);
        knowledgeBaseService.update(kb);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
    }
}
