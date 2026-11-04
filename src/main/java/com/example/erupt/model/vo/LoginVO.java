package com.example.erupt.model.vo;

/**
 * 登录成功响应。前端拿到这个对象后应把 {@code token} 存入 localStorage，
 * 后续所有需要鉴权的请求在 {@code Authorization: Bearer <token>} 头里带回。
 *
 * <p>{@code expiresIn} 用相对秒数而不是绝对时间戳——
 * 客户端时钟可能与服务器不一致，相对值不受此影响。</p>
 */
public record LoginVO(String token, UserVO user, long expiresIn) {
}