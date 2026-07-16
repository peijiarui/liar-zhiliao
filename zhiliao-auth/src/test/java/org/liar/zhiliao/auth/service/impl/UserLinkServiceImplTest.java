package org.liar.zhiliao.auth.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.auth.entity.SysOauthLink;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.mapper.SysOauthLinkMapper;
import org.liar.zhiliao.auth.mapper.SysUserMapper;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserLinkServiceImplTest {

    @Mock SysOauthLinkMapper oauthLinkMapper;
    @Mock SysUserMapper userMapper;
    UserLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserLinkServiceImpl(oauthLinkMapper, userMapper);
    }

    @Test
    void shouldReturnExistingUserWhenOauthLinkFound() {
        var userInfo = new OAuth2UserInfo("123", "a@b.com", null, null, "Alice");
        var existingLink = SysOauthLink.builder().id(1L).userId(1L).provider("github").providerUserId("123").build();
        when(oauthLinkMapper.selectOne(any())).thenReturn(existingLink);
        when(userMapper.selectById(1L)).thenReturn(SysUser.builder().id(1L).build());

        SysUser result = service.linkOrCreate(userInfo, "github");

        assertEquals(1L, result.getId());
        verify(oauthLinkMapper).selectOne(any());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void shouldCreateNewUserForGithubWhenNoLink() {
        var userInfo = new OAuth2UserInfo("456", "b@b.com", null, "gh_user", "Bob");
        when(oauthLinkMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(i -> {
            SysUser u = i.getArgument(0);
            u.setId(2L);
            return 1;
        });

        SysUser result = service.linkOrCreate(userInfo, "github");

        assertEquals(2L, result.getId());
        assertEquals("github_456", result.getLoginName());
        assertNull(result.getPhone());

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        assertEquals("github_456", userCaptor.getValue().getLoginName());
    }

    @Test
    void shouldLinkDingtalkByPhoneWhenMatch() {
        var userInfo = new OAuth2UserInfo("789", null, "13800138000", null, "Charlie");
        var existingUser = SysUser.builder().id(3L).phone("13800138000").build();
        when(oauthLinkMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectOne(argThat(q -> true))).thenReturn(existingUser);

        SysUser result = service.linkOrCreate(userInfo, "dingtalk");

        assertEquals(3L, result.getId());
        ArgumentCaptor<SysOauthLink> linkCaptor = ArgumentCaptor.forClass(SysOauthLink.class);
        verify(oauthLinkMapper).insert(linkCaptor.capture());
        assertEquals(3L, linkCaptor.getValue().getUserId());
        assertEquals("dingtalk", linkCaptor.getValue().getProvider());
    }

    @Test
    void shouldCreateNewUserForDingtalkWhenPhoneNotMatch() {
        var userInfo = new OAuth2UserInfo("000", null, "13900139000", null, "Dave");
        when(oauthLinkMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectOne(argThat(q -> true))).thenReturn(null);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(i -> {
            SysUser u = i.getArgument(0);
            u.setId(4L);
            return 1;
        });

        SysUser result = service.linkOrCreate(userInfo, "dingtalk");

        assertEquals(4L, result.getId());
        assertEquals("13900139000", result.getPhone());
        assertEquals("dingtalk_000", result.getLoginName());
    }
}
