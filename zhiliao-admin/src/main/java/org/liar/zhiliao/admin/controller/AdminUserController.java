package org.liar.zhiliao.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.mapper.SysUserMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户管理接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final SysUserMapper userMapper;

    @GetMapping
    public IPage<SysUser> page(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return userMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getCreatedAt));
    }

    @PutMapping("/{id}/role")
    public void updateRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String role = body.get("role");
        if (role != null && (role.equals("USER") || role.equals("ADMIN"))) {
            SysUser user = userMapper.selectById(id);
            if (user != null) {
                user.setRole(role);
                userMapper.updateById(user);
            }
        }
    }
}
