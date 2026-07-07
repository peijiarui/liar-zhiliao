package org.liar.zhiliao.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.auth.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
