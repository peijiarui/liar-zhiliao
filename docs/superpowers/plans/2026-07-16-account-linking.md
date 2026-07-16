# 账号关联 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 `UserLinkServiceImpl.linkOrCreate()`，实现钉钉手机号自动关联、GitHub 独立账号的策略

**Architecture:** 在现有 `UserLinkServiceImpl` 中按 provider 分流：钉钉走手机号匹配后关联/创建，GitHub 直接创建新用户。已有 `sys_oauth_link` 表做绑定持久化。

**Tech Stack:** Java 21, Spring Boot 3, MyBatis-Plus, PostgreSQL

## Global Constraints

- 不改数据库表结构（已有 `sys_oauth_link` + `sys_user.phone`）
- 不改前端代码
- Provider 字符串常量：`"dingtalk"`、`"github"`
- 新建用户 `loginName` 格式：`{provider}_{providerUserId}`

---

### Task 1: 重写 UserLinkServiceImpl.linkOrCreate()

**Files:**
- Modify: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/service/impl/UserLinkServiceImpl.java`
- Test: `zhiliao-auth/src/test/java/org/liar/zhiliao/auth/service/impl/UserLinkServiceImplTest.java`

**Interfaces:**
- Consumes: `OAuth2UserInfo(providerUserId, email, phone, loginName, name)`, provider `String`
- Produces: `SysUser` — 已有用户或新创建用户

- [ ] **Step 1: 写测试**

```java
package org.liar.zhiliao.auth.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.auth.entity.SysOauthLink;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.mapper.SysOauthLinkMapper;
import org.liar.zhiliao.auth.mapper.SysUserMapper;
import org.liar.zhiliao.auth.record.OAuth2UserInfo;
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
        when(oauthLinkMapper.selectOne(any())).thenReturn(new SysOauthLink());
        when(userMapper.selectById(any())).thenReturn(SysUser.builder().id(1L).build());

        SysUser result = service.linkOrCreate(userInfo, "github");

        assertEquals(1L, result.getId());
        verify(oauthLinkMapper).selectOne(any());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void shouldCreateNewUserForGithubWhenNoLink() {
        var userInfo = new OAuth2UserInfo("456", "b@b.com", null, "gh_user", "Bob");
        when(oauthLinkMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any())).thenAnswer(i -> {
            SysUser u = i.getArgument(0);
            u.setId(2L);
            return 1;
        });

        SysUser result = service.linkOrCreate(userInfo, "github");

        assertEquals(2L, result.getId());
        assertEquals("github_456", result.getLoginName());
        assertNull(result.getPhone());
        verify(userMapper).insert(argThat(u -> u.getLoginName().equals("github_456")));
    }

    @Test
    void shouldLinkDingtalkByPhoneWhenMatch() {
        var userInfo = new OAuth2UserInfo("789", null, "13800138000", null, "Charlie");
        var existingUser = SysUser.builder().id(3L).phone("13800138000").build();
        when(oauthLinkMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectOne(argThat(q -> true))).thenReturn(existingUser);

        SysUser result = service.linkOrCreate(userInfo, "dingtalk");

        assertEquals(3L, result.getId());
        verify(oauthLinkMapper).insert(argThat(link ->
                link.getUserId().equals(3L) && link.getProvider().equals("dingtalk")));
    }

    @Test
    void shouldCreateNewUserForDingtalkWhenPhoneNotMatch() {
        var userInfo = new OAuth2UserInfo("000", null, "13900139000", null, "Dave");
        when(oauthLinkMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectOne(argThat(q -> true))).thenReturn(null);
        when(userMapper.insert(any())).thenAnswer(i -> {
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
```

- [ ] **Step 2: 确认测试失败（没有实现）**

Run: `mvn test -pl zhiliao-auth -DskipTests=false -Dtest=UserLinkServiceImplTest 2>&1`
Expected: BUILD FAILURE（找不到测试类，因为没有测试目录或测试类）

如果测试基础结构没有，先建目录：
```bash
mkdir -p zhiliao-auth/src/test/java/org/liar/zhiliao/auth/service/impl
```
然后把测试文件写进去。再跑一次。

- [ ] **Step 3: 重写 linkOrCreate() 方法**

将整个实现替换为：

```java
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
            return userMapper.selectById(existingLink.getUserId());
        }

        // 2a. 钉钉：手机号自动关联
        if ("dingtalk".equals(provider) && StringUtils.isNotBlank(userInfo.phone())) {
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
                .passwordHash("")
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
```

- [ ] **Step 4: 跑测试验证**

Run: `mvn test -pl zhiliao-auth -DskipTests=false -Dtest=UserLinkServiceImplTest 2>&1`
Expected: BUILD SUCCESS, 4 tests passed

- [ ] **Step 5: 编译验证主项目**

Run: `mvn compile -pl zhiliao-auth -am -q 2>&1`
Expected: 无输出（编译成功）

- [ ] **Step 6: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/service/impl/UserLinkServiceImpl.java
git add zhiliao-auth/src/test/java/org/liar/zhiliao/auth/service/impl/UserLinkServiceImplTest.java
git commit -m "feat(auth): 重构账号关联逻辑，钉钉走手机号匹配，GitHub 独立账号"
```

### Task 2: 清理 OAuth2UserInfo 中 DingTalk 工厂方法

**Files:**
- Modify: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/record/OAuth2UserInfo.java:27`

**Interfaces:**
- Consumes: `DingTalkUserInfoResp(mobile, unionId, nick, email)` — 外部输入
- Produces: `OAuth2UserInfo(providerUserId, email, phone, loginName, name)` — `loginName` 不再用 phone 填充

**背景:** 当前第 27 行 `userInfo.mobile()` 被填入 `loginName` 字段。虽然新逻辑中 `loginName` 不被用于匹配，但语义上 `loginName` 不应该是手机号。

- [ ] **Step 1: 修改 DingTalk 工厂方法**

```java
public static OAuth2UserInfo of(DingTalkUserInfoResp userInfo) {
    return new OAuth2UserInfo(
            userInfo.unionId(),
            userInfo.email(),
            userInfo.mobile(),
            userInfo.nick(),     // ← 从 mobile 改为 nick，loginName 用 nick 更合理
            userInfo.nick());
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl zhiliao-auth -am -q 2>&1`
Expected: 无输出

- [ ] **Step 3: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/record/OAuth2UserInfo.java
git commit -m "fix(auth): DingTalk OAuth2UserInfo loginName 使用 nick 替代 mobile"
```
