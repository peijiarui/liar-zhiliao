package org.liar.zhiliao.common.event;

import java.util.Set;

/**
 * 文档更新事件，知识库文档处理完成或更新时发布。
 * 监听器消费此事件后执行缓存全量淘汰等副作用操作。
 */
public record DocumentUpdateEvent(Set<Long> docIds) {
}
