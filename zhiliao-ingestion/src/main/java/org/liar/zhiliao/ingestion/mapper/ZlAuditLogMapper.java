package org.liar.zhiliao.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.ingestion.entity.ZlAuditLog;

/**
 * 审计日志 Mapper。
 */
@Mapper
public interface ZlAuditLogMapper extends BaseMapper<ZlAuditLog> {
}
