package org.liar.zhiliao.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.liar.zhiliao.admin.entity.ZlKnowledgeBase;

/**
 * 知识库管理服务。
 */
public interface KnowledgeBaseService {

    IPage<ZlKnowledgeBase> page(int pageNum, int pageSize);

    ZlKnowledgeBase getById(Long id);

    void create(ZlKnowledgeBase kb);

    void update(ZlKnowledgeBase kb);

    void delete(Long id);
}
