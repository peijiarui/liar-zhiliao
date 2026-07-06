package org.liar.zhiliao.ingestion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.ingestion.model.Document;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
