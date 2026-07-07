package org.liar.zhiliao.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.ingestion.entity.Document;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
