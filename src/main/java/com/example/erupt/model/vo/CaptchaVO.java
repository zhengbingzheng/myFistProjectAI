package com.example.erupt.model.vo;

/**
 * 验证码响应。
 *
 * <p>{@code captchaKey} 是答案在 Redis 的索引，前端提交登录时必须原样回传；
 * {@code image} 是已经编码为 data URI 的 PNG，可以直接赋给 {@code <img src>}。</p>
 */
public record CaptchaVO(String captchaKey, String image) {
}