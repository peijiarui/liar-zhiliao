package org.liar.zhiliao.ingestion.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.ingestion.model.Chunk;

@Mapper
public interface ChunkMapper extends BaseMapper<Chunk> {
}
