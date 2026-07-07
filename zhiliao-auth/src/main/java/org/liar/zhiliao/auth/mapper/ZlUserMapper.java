package org.liar.zhiliao.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.auth.entity.ZlUser;

@Mapper
public interface ZlUserMapper extends BaseMapper<ZlUser> {
}
