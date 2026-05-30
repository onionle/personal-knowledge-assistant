package com.example.sai.user;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * 签发 / 校验 JWT。
 * 跟 Python 版完全一致：HS256 + 同一个 JWT_SECRET，claims 里放 sub=用户id、username。
 * 所以三个后端的 token 互认（Python 注册的账号，这里也能用 token 访问）。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.ttl-seconds:604800}") long ttlSeconds
    ) {
        // 密钥用 UTF-8 字节，跟 Python PyJWT 的 HS256 一致（密钥需 ≥ 32 字节）
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String create(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名/过期，返回用户 id。无效/过期会抛异常。 */
    public Long parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.valueOf(claims.getSubject());
    }
}
