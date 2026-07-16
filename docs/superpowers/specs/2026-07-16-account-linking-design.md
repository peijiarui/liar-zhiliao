# 账号关联设计 —— OAuth2 登录与本地账号绑定方案

## 背景

zhiliao-auth 模块支持三种登录方式：账号密码、钉钉扫码、GitHub OAuth。需要设计一套账号关联方案，解决「同一用户通过不同方式登录时，如何识别为同一账号」的问题。

## 方案决策

### 核心原则

- **钉钉**：利用企业 IM 返回的手机号自动关联已有账号
- **GitHub**：每次授权视为独立账号，不尝试与本地账号合并
- 两种策略分开，互不影响

### 关联策略明细

#### 钉钉扫码

```
请求链路：
  GET /oauth2/dingtalk/authorize → 302 钉钉授权页
  → 用户扫码 → 回调 /oauth2/dingtalk/callback?code=xxx&state=xxx
  → DingTalkAuthenticator.authenticate(code)
    → 换 token → 获取用户信息（含 mobile 手机号）
  → UserLinkService.linkOrCreate(userInfo, "dingtalk")
    → 查 sys_oauth_link(provider="dingtalk", provider_user_id=unionId)
      ├─ 已绑定 → 返回关联的本地用户
      └─ 未绑定 → 查 sys_user.phone
          ├─ 匹配 → 创建 sys_oauth_link → 返回已有用户（自动关联）
          └─ 不匹配 → 创建新用户（phone 写入）→ 创建绑定
  → 签发 token → 302 重定向前端
```

**关键点**：
- 手机号匹配成功时，自动创建 `sys_oauth_link`，下次扫码直接走绑定记录
- 新用户创建时写入 `sys_user.phone`，后续密码注册同手机号时能对齐
- 一个 `sys_user` 可以绑定多个钉钉账号（虽然极少发生，但表结构支持）

#### GitHub OAuth

```
请求链路：
  GET /oauth2/github → 302 GitHub 授权页
  → 用户授权 → 回调 /oauth2/github/callback?code=xxx&state=xxx
  → GitHubAuthenticator.authenticate(code)
    → 换 token → 获取用户信息（id, login, email）
  → UserLinkService.linkOrCreate(userInfo, "github")
    → 查 sys_oauth_link(provider="github", provider_user_id=github_id)
      ├─ 已绑定 → 返回关联的本地用户
      └─ 未绑定 → 创建新用户 + 创建绑定
  → 签发 token → 302 重定向前端
```

**关键点**：
- GitHub 不返回手机号，email 也不可靠（可设为私密）
- 不做任何自动合并，每次首次授权都创建独立账号
- 后续同一 GitHub 账号扫码走 `sys_oauth_link` 匹配

### 数据库设计

已有 `sys_oauth_link` 表，无需新增表：

```sql
CREATE TABLE sys_oauth_link (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES sys_user(id),
    provider       VARCHAR(20) NOT NULL,          -- 'github' | 'dingtalk' | 'wechat'
    provider_user_id VARCHAR(100) NOT NULL,        -- GitHub id / 钉钉 unionId
    provider_email VARCHAR(200),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider, provider_user_id)
);
```

`sys_user` 表已有 `phone` 字段，钉钉关联时依赖它做手机号匹配。

## 改动清单

### 1. UserLinkServiceImpl.linkOrCreate()

当前方法体需要按 provider 分流：

```java
public SysUser linkOrCreate(OAuth2UserInfo userInfo, String provider) {
    // 1. 统一入口：查 sys_oauth_link
    SysOauthLink existing = queryOauthLink(provider, userInfo.providerUserId());
    if (existing != null) return userMapper.selectById(existing.getUserId());

    if ("dingtalk".equals(provider)) {
        // 2a. 钉钉：手机号匹配
        if (userInfo.phone() != null) {
            SysUser matched = userMapper.selectOne(lambdaQuery().eq(SysUser::getPhone, userInfo.phone()));
            if (matched != null) {
                createOauthLink(matched.getId(), provider, userInfo);
                return matched;
            }
        }
        // 3a. 创建新用户（带手机号）
        return createUserWithOauthLink(userInfo, provider);
    }

    // 2b. GitHub：直接创建
    return createUserWithOauthLink(userInfo, provider);
}
```

### 2. 测试场景

| 场景 | 操作 | 期望结果 |
|------|------|---------|
| 钉钉首次 + 手机号匹配已有用户 | 扫码 | 登录已有账号，自动绑定 |
| 钉钉首次 + 手机号无匹配 | 扫码 | 创建新账号，绑定钉钉 |
| 钉钉再次扫码 | 扫码 | 走绑定记录，直接登录 |
| GitHub 首次 | 授权 | 创建新账号，绑定 GitHub |
| GitHub 再次 | 授权 | 走绑定记录，直接登录 |

### 新用户创建规则

| 字段 | 钉钉 | GitHub |
|------|------|--------|
| `login_name` | `dingtalk_{unionId}` | `github_{providerUserId}` |
| `name` | 钉钉 `nick` | GitHub `login` |
| `phone` | 钉钉 `mobile` | 无 |
| `password_hash` | 空字符串 | 空字符串 |
| `email` | 如有则填 | GitHub 主邮箱 |
| `dept_id` | 1（默认部门） | 1 |
| `role` | USER | USER |
| `tenant_id` | default | default |

### 关联步骤总结

```
linkOrCreate(userInfo, provider):
  1. 查 sys_oauth_link(provider, providerUserId)
     ├─ 匹配 → 返回关联用户（通用步骤）
     └─ 不匹配 → 按 provider 分流

      钉钉:
        2a. 查 sys_user.phone
          ├─ 匹配 → 创建绑定 → 返回已有用户（自动关联）
          └─ 不匹配 → 创建新用户（写入 phone）+ 创建绑定
      
      GitHub:
        2b. 直接创建新用户 + 创建绑定（不做任何匹配）
```

## 改动清单

### 1. UserLinkServiceImpl.linkOrCreate()

重写当前方法，移除 bug 的邮箱匹配逻辑（当前按 loginName 查），改为 provider-based 分流：

```java
public SysUser linkOrCreate(OAuth2UserInfo userInfo, String provider) {
    // 1. 统一入口：查 sys_oauth_link
    SysOauthLink existing = oauthLinkMapper.selectOne(
        lambdaQuery().eq(SysOauthLink::getProvider, provider)
                     .eq(SysOauthLink::getProviderUserId, userInfo.providerUserId()));
    if (existing != null) return userMapper.selectById(existing.getUserId());

    if ("dingtalk".equals(provider)) {
        // 2a. 钉钉：手机号匹配
        if (StringUtils.isNotBlank(userInfo.phone())) {
            SysUser matched = userMapper.selectOne(
                lambdaQuery().eq(SysUser::getPhone, userInfo.phone()));
            if (matched != null) {
                createOauthLink(matched.getId(), provider, userInfo);
                return matched;
            }
        }
    }

    // 2b. GitHub 或钉钉手机号未匹配：创建新用户
    return createUserWithOauthLink(userInfo, provider);
}

private SysUser createUserWithOauthLink(OAuth2UserInfo userInfo, String provider) {
    String loginName = provider + "_" + userInfo.providerUserId();
    SysUser user = SysUser.builder()
        .loginName(loginName)
        .passwordHash("")
        .name(userInfo.name())
        .email(userInfo.email())
        .phone(userInfo.phone())
        .role("USER").tenantId("default").deptId(1L)
        .build();
    userMapper.insert(user);
    createOauthLink(user.getId(), provider, userInfo);
    return user;
}
```

### 2. OAuth2UserInfo phone 字段

确认 `DingTalkUserInfoResp.mobile` → `OAuth2UserInfo.phone()` 映射正常。

### 3. 测试场景

| 场景 | 操作 | 期望结果 |
|------|------|---------|
| 钉钉 + 手机号匹配已有用户 | 扫码 | 登录已有账号，自动绑定 |
| 钉钉 + 手机号无匹配 | 扫码 | 创建新账号，绑定钉钉 |
| 钉钉再次扫码 | 扫码 | 走绑定记录，直接登录 |
| GitHub 首次 | 授权 | 创建新账号，绑定 GitHub |
| GitHub 再次 | 授权 | 走绑定记录，直接登录 |

## 不做

- 不提供「账号设置页主动绑定/解绑」功能（后续迭代）
- 不做邮箱匹配（不可靠）
- 不合并已有账号（GitHub 和密码账号视为不同用户）
- 不解绑逻辑（MVP 不涉及）
