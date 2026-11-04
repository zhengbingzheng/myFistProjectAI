package com.example.erupt.model.vo;

/**
 * 登录失败提示：除错误信息外，附带"是否需要输入验证码"以及"还剩多少秒可重试"。
 *
 * <p>这两个字段让前端可以在不发起第二次请求的情况下，精准地：
 * <ul>
 *   <li>显示/隐藏验证码输入框（依据 {@code needCaptcha}）</li>
 *   <li>展示倒计时（依据 {@code retryAfter}，单位秒）</li>
 * </ul>
 * </p>
 */
public record LoginFailHintVO(boolean needCaptcha, long retryAfter) {
}