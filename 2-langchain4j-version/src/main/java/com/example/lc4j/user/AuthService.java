package com.example.lc4j.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 注册 / 登录 / 从请求头解析当前用户。
 * 密码用 BCrypt（跟 Python 版 bcrypt 兼容，同一张 users 表互通）。
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    public User register(String username, String password) {
        if (users.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已被占用");
        }
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        return users.save(u);
    }

    public User login(String username, String password) {
        User u = users.findByUsername(username).orElse(null);
        if (u == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return u;
    }

    public String issueToken(User user) {
        return jwt.create(user);
    }

    /** 必须登录：没带/带错 token → 401。 */
    public User requireUser(String authHeader) {
        User u = optionalUser(authHeader);
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return u;
    }

    /** 可选登录：没带 token 返回 null；带了就校验并返回用户。 */
    public User optionalUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        try {
            Long uid = jwt.parseUserId(token);
            return users.findById(uid).orElse(null);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token 无效或已过期");
        }
    }
}
