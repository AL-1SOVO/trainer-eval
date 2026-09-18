package com.trainereval.common.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-ttl}")
    private long accessTokenTtl;

    @Value("${jwt.refresh-token-ttl}")
    private long refreshTokenTtl;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
    }

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    /**
     * 签发 access token。
     *
     * <h3>为什么要带 sessionGeneration</h3>
     * 原来 access token 不带代次，过滤器也只校验签名 + Redis 里 jti 是否存在，
     * 导致：<b>改密/登出之后，此前签发的 access token 仍能继续用到自然过期</b>。
     * 实测踩到过 —— 改完密码，旧 token 请求 /auth/userinfo 依然返回 200。
     * refresh token 那边早就用"代次自增"解决了，access token 漏了同一道防线。
     * <p>
     * 用代次而不是比较签发时间：JWT 的 {@code iat} 只有秒级精度，
     * 同一秒内"登录 + 改密"会让时间比较失效（这个坑之前踩过，见 AuthController 的注释）。
     */
    public String generateAccessToken(Long userId, String username, long sessionGeneration) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + accessTokenTtl * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("typ", TYPE_ACCESS)
                .claim("gen", sessionGeneration)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(exp)
                .signWith(getKey())
                .compact();
    }

    /** 兼容旧调用：代次取 0。新代码请显式传入当前代次。 */
    public String generateAccessToken(Long userId, String username) {
        return generateAccessToken(userId, username, 0L);
    }

    public String generateRefreshToken(Long userId, long sessionGeneration) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + refreshTokenTtl * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("typ", TYPE_REFRESH)
                .claim("gen", sessionGeneration)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(exp)
                .signWith(getKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 校验 token 且要求其类型与期望一致。
     * 修复前 access/refresh 用同一密钥且都不带类型声明，导致两者不可区分，
     * 已登出的 access token 可以直接当 refresh token 用（登出被绕过）。
     */
    public boolean validateToken(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser().verifyWith(getKey()).build()
                    .parseSignedClaims(token).getPayload();
            return expectedType.equals(claims.get("typ", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** token 类型；旧 token 无该声明时返回 null（会被 refresh 拒绝，属预期的强制重新登录）。 */
    public String getTypeFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload();
        return claims.get("typ", String.class);
    }

    /** 签发时间（秒），用于登出后的"按签发时间作废"。 */
    public long getIssuedAtEpochSecond(String token) {
        Claims claims = Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload();
        return claims.getIssuedAt().toInstant().getEpochSecond();
    }

    /**
     * 会话代次。登出/改密时服务端对该用户代次自增，旧 refresh token 因代次不匹配而失效。
     * 比"按 iat 时间戳比较"可靠 —— JWT 的 iat 只有秒级精度，
     * 同一秒内完成"登录 + 登出"会让时间比较失效。
     */
    public long getSessionGenerationFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload();
        Long gen = claims.get("gen", Long.class);
        return gen != null ? gen : 0L;
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload();
        return Long.valueOf(claims.getSubject());
    }

    public String getJtiFromToken(String token) {
        return Jwts.parser().verifyWith(getKey()).build()
                .parseSignedClaims(token).getPayload().getId();
    }
}
