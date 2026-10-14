package com.trainereval.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌签发的安全边界测试。
 * <p>
 * 重点锁住三件曾经出过问题的事（都写在 {@link JwtUtils} 的注释里）：
 * ① access / refresh 必须能区分 —— 否则已登出的 access token 可以当 refresh token 用，登出被绕过；
 * ② access token 必须带会话代次 —— 否则改密之后旧 token 还能继续用到自然过期；
 * ③ 篡改与过期必须被拒 —— 且是返回 false，不是抛异常把过滤器打挂。
 */
class JwtUtilsTest {

    /** HS256 要求密钥至少 32 字节。 */
    private static final String RAW_SECRET = "trainer-eval-unit-test-secret-key-32bytes!!";

    private JwtUtils jwt;

    @BeforeEach
    void setUp() {
        jwt = newJwtUtils(RAW_SECRET, 3600L, 7200L);
    }

    @Test
    @DisplayName("access token 往返：主体、类型、代次、jti、签发时间全部可还原")
    void accessTokenRoundTrip() {
        String token = jwt.generateAccessToken(42L, "alice", 7L);

        assertThat(jwt.validateToken(token)).isTrue();
        assertThat(jwt.validateToken(token, JwtUtils.TYPE_ACCESS)).isTrue();
        assertThat(jwt.getUserIdFromToken(token)).isEqualTo(42L);
        assertThat(jwt.getTypeFromToken(token)).isEqualTo(JwtUtils.TYPE_ACCESS);
        assertThat(jwt.getSessionGenerationFromToken(token)).isEqualTo(7L);
        assertThat(jwt.getJtiFromToken(token)).isNotBlank();
        assertThat(jwt.getIssuedAtEpochSecond(token)).isPositive();
    }

    @Test
    @DisplayName("refresh token 不能冒充 access token（登出绕过就是这个漏洞）")
    void refreshTokenIsRejectedAsAccessToken() {
        String refresh = jwt.generateRefreshToken(42L, 7L);

        assertThat(jwt.validateToken(refresh)).isTrue();
        assertThat(jwt.validateToken(refresh, JwtUtils.TYPE_REFRESH)).isTrue();
        assertThat(jwt.validateToken(refresh, JwtUtils.TYPE_ACCESS)).isFalse();
        assertThat(jwt.getTypeFromToken(refresh)).isEqualTo(JwtUtils.TYPE_REFRESH);
    }

    @Test
    @DisplayName("access token 也不能拿去做 refresh")
    void accessTokenIsRejectedAsRefreshToken() {
        String access = jwt.generateAccessToken(42L, "alice", 7L);

        assertThat(jwt.validateToken(access, JwtUtils.TYPE_REFRESH)).isFalse();
    }

    @Test
    @DisplayName("兼容重载默认代次为 0")
    void legacyOverloadDefaultsToGenerationZero() {
        String token = jwt.generateAccessToken(1L, "bob");

        assertThat(jwt.getSessionGenerationFromToken(token)).isZero();
    }

    @Test
    @DisplayName("每次签发的 jti 都不同，可按 jti 精确注销单个令牌")
    void everyTokenGetsAUniqueJti() {
        String first = jwt.generateAccessToken(1L, "bob", 0L);
        String second = jwt.generateAccessToken(1L, "bob", 0L);

        assertThat(jwt.getJtiFromToken(first)).isNotEqualTo(jwt.getJtiFromToken(second));
    }

    @Test
    @DisplayName("过期令牌被拒")
    void expiredTokenIsRejected() {
        ReflectionTestUtils.setField(jwt, "accessTokenTtl", -1L);
        String expired = jwt.generateAccessToken(1L, "bob", 0L);

        assertThat(jwt.validateToken(expired)).isFalse();
        assertThat(jwt.validateToken(expired, JwtUtils.TYPE_ACCESS)).isFalse();
    }

    @Test
    @DisplayName("签名被篡改的令牌被拒")
    void tamperedTokenIsRejected() {
        String token = jwt.generateAccessToken(1L, "bob", 0L);
        String tampered = token.substring(0, token.length() - 3)
                + (token.endsWith("AAA") ? "BBB" : "AAA");

        assertThat(jwt.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("换密钥签发的令牌被拒")
    void tokenFromAnotherSecretIsRejected() {
        JwtUtils other = newJwtUtils("another-unit-test-secret-key-long-enough-32", 3600L, 7200L);
        String foreign = other.generateAccessToken(1L, "bob", 0L);

        assertThat(jwt.validateToken(foreign)).isFalse();
    }

    @Test
    @DisplayName("畸形输入返回 false 而不是抛异常（过滤器不能因此打挂）")
    void malformedInputReturnsFalseInsteadOfThrowing() {
        assertThat(jwt.validateToken(null)).isFalse();
        assertThat(jwt.validateToken("")).isFalse();
        assertThat(jwt.validateToken("not-a-jwt")).isFalse();
        assertThat(jwt.validateToken("a.b.c")).isFalse();
        assertThat(jwt.validateToken("a.b.c", JwtUtils.TYPE_ACCESS)).isFalse();
    }

    // ---------------------------------------------------------------- 辅助

    private static JwtUtils newJwtUtils(String rawSecret, long accessTtl, long refreshTtl) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "secret",
                Base64.getEncoder().encodeToString(rawSecret.getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.setField(utils, "accessTokenTtl", accessTtl);
        ReflectionTestUtils.setField(utils, "refreshTokenTtl", refreshTtl);
        return utils;
    }
}
