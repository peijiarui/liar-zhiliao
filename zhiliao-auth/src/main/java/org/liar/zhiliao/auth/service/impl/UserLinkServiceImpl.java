package org.liar.zhiliao.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.liar.zhiliao.auth.entity.SysOauthLink;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.mapper.SysOauthLinkMapper;
import org.liar.zhiliao.auth.mapper.SysUserMapper;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
import org.liar.zhiliao.auth.service.UserLinkService;
import org.springframework.stereotype.Service;
import static org.liar.zhiliao.auth.enums.OAuth2ProviderEnum.DINGTALK;

import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserLinkServiceImpl implements UserLinkService {

    private final SysOauthLinkMapper oauthLinkMapper;
    private final SysUserMapper userMapper;

    @Override
    @Transactional
    public SysUser linkOrCreate(OAuth2UserInfo userInfo, String provider) {
        // 1. 查 sys_oauth_link 是否有绑定
        SysOauthLink existingLink = oauthLinkMapper.selectOne(
                Wrappers.<SysOauthLink>lambdaQuery()
                        .eq(SysOauthLink::getProvider, provider)
                        .eq(SysOauthLink::getProviderUserId, userInfo.providerUserId()));
        if (existingLink != null) {
            SysUser user = userMapper.selectById(existingLink.getUserId());
            if (user != null) {
                return user;
            }
            log.warn("OAuth link exists but user {} was deleted, will recreate", existingLink.getUserId());
            oauthLinkMapper.deleteById(existingLink.getId());
        }

        // 2a. 钉钉：手机号自动关联
        if (DINGTALK.getProvider().equals(provider) && StringUtils.isNotBlank(userInfo.phone())) {
            SysUser matched = userMapper.selectOne(
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, userInfo.phone()));
            if (matched != null) {
                createOauthLink(matched.getId(), provider, userInfo);
                log.info("Auto-linked dingtalk user {} to existing user {}", userInfo.providerUserId(), matched.getId());
                return matched;
            }
        }

        // 2b. GitHub 或钉钉未匹配：创建新用户
        return createUserWithOauthLink(userInfo, provider);
    }

    private SysUser createUserWithOauthLink(OAuth2UserInfo userInfo, String provider) {
        String loginName = provider + "_" + userInfo.providerUserId();
        SysUser newUser = SysUser.builder()
                .loginName(loginName)
                .passwordHash(null)
                .name(userInfo.name())
                .email(userInfo.email())
                .phone(userInfo.phone())
                .role("USER")
                .tenantId("default")
                .deptId(1L)
                .build();
        userMapper.insert(newUser);

        createOauthLink(newUser.getId(), provider, userInfo);
        log.info("Created new user from OAuth: provider={}, loginName={}", provider, loginName);
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
