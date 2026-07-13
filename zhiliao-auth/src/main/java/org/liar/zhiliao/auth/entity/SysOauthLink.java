package org.liar.zhiliao.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * OAuth 关联实体：记录第三方平台账号到本地用户的绑定关系。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("sys_oauth_link")
public class SysOauthLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 sys_user.id */
    private Long userId;

    /** OAuth 提供商：github / dingtalk / wechat */
    private String provider;

    /** 第三方平台中的用户唯一ID */
    private String providerUserId;

    /** OAuth 返回的邮箱 */
    private String providerEmail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
