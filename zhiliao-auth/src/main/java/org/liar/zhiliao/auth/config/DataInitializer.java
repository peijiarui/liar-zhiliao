package org.liar.zhiliao.auth.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.mapper.SysUserMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时初始化用户。
 * 自动创建默认管理员（admin / admin123），确保首次部署有入口。
 * 同时更新已有用户的密码 hash（配合 BCrypt 编码）。
 */
@Slf4j
//@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        initUser("admin", "admin123", "管理员", "ADMIN");
        initUser("zhangsan", "123456", "张三", "USER");
        initUser("lisi", "123456", "李四", "USER");
    }

    private void initUser(String loginName, String rawPassword, String name, String role) {
        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getLoginName, loginName));
        if (user == null) {
            // 创建用户
            user = SysUser.builder()
                    .loginName(loginName)
                    .passwordHash(passwordEncoder.encode(rawPassword))
                    .name(name)
                    .role(role)
                    .deptId(1L)
                    .build();
            userMapper.insert(user);
            log.info("Created user: loginName={}, role={}", loginName, role);
        } else {
            // 更新密码 hash
            if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
                user.setPasswordHash(passwordEncoder.encode(rawPassword));
                userMapper.updateById(user);
                log.info("Updated password hash for user: {}", loginName);
            }
        }
    }
}
