package com.example.erupt.service;

import com.example.erupt.config.AuthProperties;
import com.example.erupt.repository.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TokenService} 单元测试。
 *
 * <p>覆盖三类安全边界：伪造的 Token 必须被拒、已吊销的 Token 必须被拒、
 * Redis 故障时降级而不是抛异常。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenService 单元测试")
class TokenServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private AuthProperties properties;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.setTokenSecret("unit-test-secret-for-hmac");
        properties.setTokenTtl(Duration.ofHours(2));
        tokenService = new TokenService(properties, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private User sampleUser() {
        User user = new User();
        user.setId("u-1");
        user.setUsername("admin");
        return user;
    }

    @Test
    @DisplayName("签发的 Token 能被验证，且身份信息正确")
    void issuedToken_verifiesWithIdentity() {
        String token = tokenService.issue(sampleUser());
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        Optional<TokenService.TokenIdentity> identity = tokenService.verify(token);

        assertThat(identity).isPresent();
        assertThat(identity.get().userId()).isEqualTo("u-1");
        assertThat(identity.get().username()).isEqualTo("admin");
    }

    @Test
    @DisplayName("Token 结构为 payload.signature 两段")
    void tokenStructure_isPayloadDotSignature() {

        String token = tokenService.issue(sampleUser());

        assertThat(token).contains(".");
        assertThat(token.split("\\.")).hasSize(2);
    }

    @Test
    @DisplayName("签名被篡改的 Token 必须被拒绝")
    void tamperedSignature_isRejected() {
        String token = tokenService.issue(sampleUser());
        String[] parts = token.split("\\.");
        String forged = parts[0] + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        assertThat(tokenService.verify(forged)).isEmpty();
    }

    @Test
    @DisplayName("载荷被篡改（换成 hacker 用户）必须被拒绝")
    void tamperedPayload_isRejected() {
        String token = tokenService.issue(sampleUser());

        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "u-1|hacker|9999999999".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = payload + "." + token.split("\\.")[1];

        assertThat(tokenService.verify(forged)).isEmpty();
    }

    @Test
    @DisplayName("格式非法的 Token 都应被拒绝且不抛异常")
    void malformedToken_isRejectedQuietly() {
        assertThat(tokenService.verify(null)).isEmpty();
        assertThat(tokenService.verify("")).isEmpty();
        assertThat(tokenService.verify("noSeparator")).isEmpty();
        // 真实踩过的坑：非法 Base64 抛 IllegalArgumentException 导致 500
        assertThat(tokenService.verify("abc.def")).isEmpty();
    }

    @Test
    @DisplayName("Redis 中无会话（已登出）则校验失败")
    void revokedToken_isRejected() {
        String token = tokenService.issue(sampleUser());
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("登出会删除 Redis 会话")
    void revoke_deletesSessionKey() {
        tokenService.revoke("some.token.value");

        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("Redis 异常时降级为仅验签，不抛出异常")
    void redisDown_fallsBackToSignatureOnly() {
        String token = tokenService.issue(sampleUser());
        when(redisTemplate.hasKey(anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThat(tokenService.verify(token)).isPresent();
    }

    @Test
    @DisplayName("Redis 写入会话失败不影响签发")
    void redisWriteFails_stillIssuesToken() {
        // set(...) 返回 void，stub 异常必须用 doThrow(...).when(...) 语法，
        // 直接写 when(valueOps.set(...)) 编译不过
        doThrow(new RuntimeException("down")).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));

        String token = tokenService.issue(sampleUser());

        assertThat(token).isNotBlank();
    }

    @Test
    @DisplayName("空 Token 登出不应触发 Redis 操作")
    void revoke_null_doesNothing() {
        tokenService.revoke(null);
        tokenService.revoke("");

        verify(redisTemplate, never()).delete(anyString());
    }
}
