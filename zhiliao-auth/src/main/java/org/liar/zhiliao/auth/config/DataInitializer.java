package org.liar.zhiliao.auth.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.ZlUser;
import org.liar.zhiliao.auth.mapper.ZlUserMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时初始化测试用户密码，确保 BCrypt hash 始终与明文密码一致。
 * 避免 data.sql 中硬编码 hash 不匹配的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final ZlUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        Map<String, String> testUsers = Map.of(
                "admin", "admin123",
                "zhangsan", "123456",
                "lisi", "123456"
        );

        testUsers.forEach((username, rawPassword) -> {
            ZlUser user = userMapper.selectOne(
                    Wrappers.<ZlUser>lambdaQuery().eq(ZlUser::getUsername, username));
            if (user == null) {
                log.warn("User {} not found in database, skipping password initialization", username);
                return;
            }
            // 验证现有 hash 是否匹配，不匹配则更新
            if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
                String newHash = passwordEncoder.encode(rawPassword);
                user.setPasswordHash(newHash);
                userMapper.updateById(user);
                log.info("Updated password hash for user: {}", username);
            }
        });
    }
}
