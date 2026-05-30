package com.example.sai.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册 / 登录 / 当前用户。跟 Python 版接口一致：
 *   POST /auth/register  {username, password} → {access_token, token_type, username}
 *   POST /auth/login     {username, password} → 同上
 *   GET  /auth/me        (Authorization: Bearer) → {id, username}
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public TokenDto register(@RequestBody AuthRequest req) {
        User u = auth.register(req.username(), req.password());
        return new TokenDto(auth.issueToken(u), "bearer", u.getUsername());
    }

    @PostMapping("/login")
    public TokenDto login(@RequestBody AuthRequest req) {
        User u = auth.login(req.username(), req.password());
        return new TokenDto(auth.issueToken(u), "bearer", u.getUsername());
    }

    @GetMapping("/me")
    public MeDto me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        User u = auth.requireUser(authHeader);
        return new MeDto(u.getId(), u.getUsername());
    }

    public record AuthRequest(String username, String password) {}
    public record TokenDto(String access_token, String token_type, String username) {}
    public record MeDto(Long id, String username) {}
}
