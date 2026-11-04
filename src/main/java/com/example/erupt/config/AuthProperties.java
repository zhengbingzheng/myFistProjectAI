package com.example.erupt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 认证与安全策略配置（绑定 {@code app.auth}）。
 *
 * <p>所有字段都支持环境变量覆盖，便于在不同环境使用不同的强度策略，
 * 同时避免把密钥明文写进配置文件。</p>
 *
 * <p><b>密钥说明</b>：{@code token-secret} 默认值仅用于本地开发。
 * 生产环境必须通过 {@code AUTH_TOKEN_SECRET} 注入足够随机的值，
 * 否则任何人都能伪造出合法 Token。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /**
     * 代码内兜底的弱密钥。仅用于本地零配置启动；
     * TokenService 启动时的守卫会在非 local/dev 档检测到它并直接终止启动。
     */
    public static final String DEV_FALLBACK_SECRET =
            "erupt-example-dev-secret-change-me-in-production";

    /**
     * application.yml 里写死过的默认密钥（已随代码分发，等同公开）。
     * 同样被启动守卫禁止用于生产。
     */
    public static final String BUNDLED_DEFAULT_SECRET =
            "23992b84f319d7c0754b8681654b2371128fdb74ff6b29923f05230ab47ce60b";

    /** 登录失败/锁定/验证码等 Redis key 的最短有效长度，低于此值视为弱密钥 */
    public static final int MIN_SECRET_LENGTH = 32;

    /** 登录态前缀，用于 Redis key 命名空间 */
    private String tokenPrefix = "auth:token:";

    /**
     * Token 签名密钥（HMAC-SHA256）。
     * 生产环境务必用环境变量注入：{@code AUTH_TOKEN_SECRET}
     */
    private String tokenSecret = DEV_FALLBACK_SECRET;

    /** Token 有效期 */
    private Duration tokenTtl = Duration.ofHours(2);

    /** 登录失败计数 key 前缀 */
    private String loginFailPrefix = "auth:login:fail:";

    /** 账号被锁定 key 前缀 */
    private String loginLockPrefix = "auth:login:lock:";

    /** 允许的最大连续失败次数，超过即锁定 */
    private int loginMaxFailures = 5;

    /** 连续失败计数的存活窗口（也是锁定时长） */
    private Duration loginFailWindow = Duration.ofMinutes(15);

    /** 验证码 key 前缀 */
    private String captchaPrefix = "auth:captcha:";

    /** 验证码有效期 */
    private Duration captchaTtl = Duration.ofMinutes(2);

    /**
     * 是否强制验证码。
     * 默认关闭：连续失败触发后才要求验证码，做到"正常用户无感、攻击者受限"。
     */
    private boolean captchaRequired = false;

    /** 是否开启鉴权拦截（关闭后所有接口放行，便于本地联调） */
    private boolean enabled = true;

    /**
     * 需要鉴权但允许匿名访问的路径（登录、验证码等）。
     *
     * <p>AuthInterceptor#isPermit 只精确命中本列表条目，或放行"条目 + / 开头的子路径"，
     * 与条目同名但结尾不同的路径（如 /api/auth/login-mock）会被照常鉴权。
     * 新增匿名端点时务必显式列全，不要依赖其它条目的前缀巧合来放行——
     * 过去 {@code /api/auth/login-hint} 没列进去，只因 {@code /api/auth/login}
     * 的前缀恰好能匹配到它才没出问题，这属于纯粹的运气。</p>
     */
    private String[] permitPaths = {
            "/api/auth/login",
            "/api/auth/login-hint",
            "/api/auth/captcha"
    };

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String getLoginFailPrefix() {
        return loginFailPrefix;
    }

    public void setLoginFailPrefix(String loginFailPrefix) {
        this.loginFailPrefix = loginFailPrefix;
    }

    public String getLoginLockPrefix() {
        return loginLockPrefix;
    }

    public void setLoginLockPrefix(String loginLockPrefix) {
        this.loginLockPrefix = loginLockPrefix;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public Duration getLoginFailWindow() {
        return loginFailWindow;
    }

    public void setLoginFailWindow(Duration loginFailWindow) {
        this.loginFailWindow = loginFailWindow;
    }

    public String getCaptchaPrefix() {
        return captchaPrefix;
    }

    public void setCaptchaPrefix(String captchaPrefix) {
        this.captchaPrefix = captchaPrefix;
    }

    public Duration getCaptchaTtl() {
        return captchaTtl;
    }

    public void setCaptchaTtl(Duration captchaTtl) {
        this.captchaTtl = captchaTtl;
    }

    public boolean isCaptchaRequired() {
        return captchaRequired;
    }

    public void setCaptchaRequired(boolean captchaRequired) {
        this.captchaRequired = captchaRequired;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String[] getPermitPaths() {
        return permitPaths;
    }

    public void setPermitPaths(String[] permitPaths) {
        this.permitPaths = permitPaths;
    }
}