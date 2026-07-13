package org.liar.zhiliao.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.chat.entity.Conversation;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
