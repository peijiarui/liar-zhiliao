package org.liar.zhiliao.admin.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.admin.entity.ZlKnowledgeBase;
import org.liar.zhiliao.admin.mapper.ZlKnowledgeBaseMapper;
import org.liar.zhiliao.admin.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;

/**
 * 知识库管理服务实现。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final ZlKnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public IPage<ZlKnowledgeBase> page(int pageNum, int pageSize) {
        return knowledgeBaseMapper.selectPage(new Page<>(pageNum, pageSize), null);
    }

    @Override
    public ZlKnowledgeBase getById(Long id) {
        return knowledgeBaseMapper.selectById(id);
    }

    @Override
    public void create(ZlKnowledgeBase kb) {
        knowledgeBaseMapper.insert(kb);
    }

    @Override
    public void update(ZlKnowledgeBase kb) {
        knowledgeBaseMapper.updateById(kb);
    }

    @Override
    public void delete(Long id) {
        knowledgeBaseMapper.deleteById(id);
    }
}
