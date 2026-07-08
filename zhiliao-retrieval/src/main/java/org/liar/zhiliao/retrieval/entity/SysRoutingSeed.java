package org.liar.zhiliao.retrieval.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 查询路由种子 — 用于 EmbeddingQueryRouter 的意图分类。
 * <p>
 * chat 类别种子的质心用于判断查询是否属于闲聊；
 * knowledge 类别种子用于判断是否属于知识检索。
 *
 * @author Pei
 * @since 2026-07-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("sys_routing_seed")
public class SysRoutingSeed {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 类别：chat / knowledge
     */
    private String category;

    /**
     * 种子文本
     */
    private String content;

    /**
     * 排序
     */
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
