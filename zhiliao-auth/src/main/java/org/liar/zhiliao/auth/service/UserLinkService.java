package org.liar.zhiliao.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.liar.zhiliao.auth.entity.SysOauthLink;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.mapper.SysOauthLinkMapper;
import org.liar.zhiliao.auth.mapper.SysUserMapper;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth2 用户关联服务。
 * 负责邮箱合并逻辑：同一邮箱的多个 OAuth 账号自动关联到同一本地用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLinkService {

    private final SysOauthLinkMapper oauthLinkMapper;
    private final SysUserMapper userMapper;

    /**
     * 根据 OAuth 用户信息关联或创建本地用户。
     *
     * @param userInfo OAuth 返回的用户信息
     * @param provider OAuth 提供商名称
     * @return 关联或新建的本地用户
     */
    @Transactional
    public SysUser linkOrCreate(OAuth2UserInfo userInfo, String provider) {
        // 1. 已有 OAuth 关联记录 → 直接返回对应用户
        SysOauthLink existingLink = oauthLinkMapper.selectOne(
                Wrappers.<SysOauthLink>lambdaQuery()
                        .eq(SysOauthLink::getProvider, provider)
                        .eq(SysOauthLink::getProviderUserId, userInfo.providerUserId()));
        if (existingLink != null) {
            return userMapper.selectById(existingLink.getUserId());
        }

        // 2. 邮箱非空 → 尝试按邮箱合并
        if (StringUtils.isNotBlank(userInfo.email())) {
            SysUser userByEmail = userMapper.selectOne(
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getLoginName, userInfo.name()));
            if (userByEmail != null) {
                createOauthLink(userByEmail.getId(), provider, userInfo);
                return userByEmail;
            }
        }

        // 3. 创建新用户，登录名用邮箱或 provider+providerUserId
        String loginName = StringUtils.isNotBlank(userInfo.name()) ? userInfo.name() : provider + "_" + userInfo.providerUserId();
        SysUser newUser = SysUser.builder()
                .loginName(loginName)
                .passwordHash("")  // OAuth 用户无密码
                .name(userInfo.name())
                .email(userInfo.email())
                .role("USER")
                .tenantId("default")
                .deptId(1L)
                .build();
        userMapper.insert(newUser);

        createOauthLink(newUser.getId(), provider, userInfo);
        log.info("Created new user from OAuth: provider={}, loginName={}, name={}", provider, loginName, userInfo.name());
        return newUser;
    }

    private void createOauthLink(Long userId, String provider, OAuth2UserInfo userInfo) {
        SysOauthLink link = SysOauthLink.builder()
                .userId(userId)
                .provider(provider)
                .providerUserId(userInfo.providerUserId())
                .providerEmail(userInfo.email())
                .build();
        oauthLinkMapper.insert(link);
    }
}
