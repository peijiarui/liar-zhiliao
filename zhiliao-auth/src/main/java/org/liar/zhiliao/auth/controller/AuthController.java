package org.liar.zhiliao.auth.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.User;
import org.liar.zhiliao.auth.service.UserService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String password = request.get("password");

            User user = userService.authenticate(username, password);
            CurrentUser currentUser = new CurrentUser(user.getId(), user.getUsername(), user.getDeptId());
            String token = jwtUtil.generateToken(currentUser);

            log.info("===== 登录成功 =====\nToken: {}", token);

            return ResponseEntity.ok(Map.of("token", token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }
}
