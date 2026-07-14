package org.liar.zhiliao.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String loginName;

    private String passwordHash;

    private String name;

    private String email;

    private String phone;

    @Builder.Default
    private Long deptId = 1L;

    @Builder.Default
    private String role = "USER";

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
