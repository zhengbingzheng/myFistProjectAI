package com.example.erupt.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/**
 * Token 签名密钥的启动守卫。
 *
 * <p>问题背景：{@code token-secret} 有两个"随代码分发"的兜底值
 * （{@link AuthProperties#DEV_FALLBACK_SECRET} 代码内默认，
 * {@link AuthProperties#BUNDLED_DEFAULT_SECRET} 曾写死在 application.yml）。
 * 生产环境若忘记注入 {@code AUTH_TOKEN_SECRET}，任何人都能用源码里的这串值
 * 伪造出合法 Token——等于没有鉴权。</p>
 *
 * <p>策略分两层：</p>
 * <ol>
 *   <li><b>local/dev/test 档</b>：检测到弱默认值时<b>不直接沿用</b>，而是替换成本进程随机生成的
 *       32 字节密钥。这样即使部署时忘了切 profile（{@code spring.profiles.default=local}
 *       会兜住零配置启动），攻击者也无法用公开值离线伪造 Token。代价是多实例部署必须显式
 *       注入 {@code AUTH_TOKEN_SECRET}，否则各实例各签各的、互不认账——这一点写在日志里。</li>
 *   <li><b>其他档位</b>：检测到弱默认值直接终止启动，把配置错误挡在上线之前。</li>
 * </ol>
 *
 * <p>独立成类而不是塞进 TokenService：守卫关注的是"配置合法性"，
 * TokenService 关注的是"签发与校验"，也让 TokenService 的构造函数保持稳定
 * （避免为守卫扩参导致既有单元测试连锁改动）。</p>
 */
@Slf4j
@Component
public class AuthSecretGuard {

    /** 允许弱默认密钥运行的档位 */
    private static final Set<String> TOLERATED_PROFILES = Set.of("local", "dev", "test");

    private final AuthProperties properties;
    private final Environment environment;

    public AuthSecretGuard(AuthProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @jakarta.annotation.PostConstruct
    void guardWeakSecret() {
        if (!isWeak(properties.getTokenSecret())) {
            return;
        }
        String reason = weakReason(properties.getTokenSecret());
        if (isToleratedProfile()) {
            // 关键：弱值不能继续用。此前只打 ERROR 就放行，于是"忘了切生产 profile + Redis 不可用"
            // 两个常见情况叠加时，源码里的公开密钥就能伪造任意用户 Token。
            properties.setTokenSecret(randomSecret());
            log.error("⚠️ {}：已为本次进程生成随机签名密钥。"
                    + "多实例部署必须显式注入 AUTH_TOKEN_SECRET，否则各实例无法互认 Token；"
                    + "生产环境请通过 AUTH_TOKEN_SECRET 注入 ≥{} 字符的独立随机密钥。",
                    reason, AuthProperties.MIN_SECRET_LENGTH);
            return;
        }
        throw new IllegalStateException(
                reason + "。非本地环境禁止使用随代码分发的默认密钥，启动中止——"
                        + "请通过环境变量 AUTH_TOKEN_SECRET 注入 ≥"
                        + AuthProperties.MIN_SECRET_LENGTH + " 字符的独立随机密钥。");
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private boolean isWeak(String secret) {
        return weakReason(secret) != null;
    }

    private String weakReason(String secret) {
        if (secret == null || secret.isBlank()) {
            return "Token 签名密钥为空";
        }
        if (AuthProperties.DEV_FALLBACK_SECRET.equals(secret)) {
            return "Token 签名密钥正在使用代码内兜底默认值";
        }
        if (AuthProperties.BUNDLED_DEFAULT_SECRET.equals(secret)) {
            return "Token 签名密钥正在使用随 application.yml 分发的默认值";
        }
        if (secret.length() < AuthProperties.MIN_SECRET_LENGTH) {
            return "Token 签名密钥长度不足 " + AuthProperties.MIN_SECRET_LENGTH + " 字符";
        }
        return null;
    }

    private boolean isToleratedProfile() {
        // 先看显式 active 档；未设置时退回 application.yml 里的 spring.profiles.default，
        // 因为本地零配置启动（外层的默认档=local）下 getActiveProfiles() 是空的，
        // 只查 active 会让本机开发也启动失败
        String active = environment.getProperty("spring.profiles.active");
        String effective = (active == null || active.isBlank())
                ? environment.getProperty("spring.profiles.default")
                : active;
        if (effective == null || effective.isBlank()) {
            return false;
        }
        return Arrays.stream(effective.split(","))
                .map(String::trim)
                .anyMatch(TOLERATED_PROFILES::contains);
    }
}
