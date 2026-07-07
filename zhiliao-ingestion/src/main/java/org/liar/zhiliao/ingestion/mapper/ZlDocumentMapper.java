package org.liar.zhiliao.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.ingestion.entity.ZlDocument;

@Mapper
public interface ZlDocumentMapper extends BaseMapper<ZlDocument> {
}
