package com.example.erupt.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 签名密钥启动守卫的单元测试。
 *
 * <p>核心安全性质：无论如何都不能用随代码分发的默认密钥继续签名。
 * local 档改为进程内随机密钥（多实例需显式注入），其他档直接拒绝启动。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthSecretGuardTest {

    @Mock
    private Environment environment;

    private AuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
    }

    @Test
    @DisplayName("local 档使用随包默认密钥_替换为随机密钥而不是继续沿用")
    void localProfile_replacesBundledSecret() {
        properties.setTokenSecret(AuthProperties.BUNDLED_DEFAULT_SECRET);
        when(environment.getProperty("spring.profiles.active")).thenReturn(null);
        when(environment.getProperty("spring.profiles.default")).thenReturn("local");

        new AuthSecretGuard(properties, environment).guardWeakSecret();

        assertThat(properties.getTokenSecret())
                .hasSize(64)
                .isNotEqualTo(AuthProperties.BUNDLED_DEFAULT_SECRET)
                .isNotEqualTo(AuthProperties.DEV_FALLBACK_SECRET);
    }

    @Test
    @DisplayName("非 local 档使用随包默认密钥_终止启动")
    void productionProfile_rejectsBundledSecret() {
        properties.setTokenSecret(AuthProperties.BUNDLED_DEFAULT_SECRET);
        when(environment.getProperty("spring.profiles.active")).thenReturn("prod");

        assertThatThrownBy(() -> new AuthSecretGuard(properties, environment).guardWeakSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_TOKEN_SECRET");
    }

    @Test
    @DisplayName("已配置的强密钥_原样保留")
    void strongSecret_keptUntouched() {
        String secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        properties.setTokenSecret(secret);

        new AuthSecretGuard(properties, environment).guardWeakSecret();

        assertThat(properties.getTokenSecret()).isEqualTo(secret);
    }
}
