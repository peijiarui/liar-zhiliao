package org.liar.zhiliao.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.auth.entity.ZlUser;
import org.liar.zhiliao.auth.mapper.ZlUserMapper;
import org.liar.zhiliao.auth.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final ZlUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public ZlUser authenticate(String username, String password) {
        ZlUser user = userMapper.selectOne(Wrappers.<ZlUser>lambdaQuery().eq(ZlUser::getUsername, username));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid password");
        }

        return user;
    }
}
