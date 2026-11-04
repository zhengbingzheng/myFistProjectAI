package com.example.erupt.common;

import com.example.erupt.service.TokenService;

/**
 * 当前请求的登录身份上下文。
 *
 * <p>由 {@code AuthInterceptor} 在请求进入时写入、请求结束时清理，
 * 让 Controller / Service 无需层层传参就能拿到"当前是谁"。</p>
 *
 * <p><b>务必在 finally 中调用 {@link #clear()}。</b>线程池会复用线程，
 * 残留的 ThreadLocal 会被下一个请求读到——那意味着 A 用户可能以 B 用户的身份
 * 执行操作，这类 bug 极难复现。</p>
 */
public final class AuthContext {

    private static final ThreadLocal<TokenService.TokenIdentity> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(TokenService.TokenIdentity identity) {
        HOLDER.set(identity);
    }

    /** 获取当前登录身份，未登录时返回 {@code null}。 */
    public static TokenService.TokenIdentity get() {
        return HOLDER.get();
    }

    /** 当前登录用户主键，未登录时返回 {@code null}。 */
    public static String currentUserId() {
        TokenService.TokenIdentity identity = HOLDER.get();
        return identity == null ? null : identity.userId();
    }

    /** 当前登录账号，未登录时返回 {@code null}。 */
    public static String currentUsername() {
        TokenService.TokenIdentity identity = HOLDER.get();
        return identity == null ? null : identity.username();
    }

    public static boolean isAuthenticated() {
        return HOLDER.get() != null;
    }

    /** 请求结束时清理，防止线程复用导致身份串号。 */
    public static void clear() {
        HOLDER.remove();
    }
}