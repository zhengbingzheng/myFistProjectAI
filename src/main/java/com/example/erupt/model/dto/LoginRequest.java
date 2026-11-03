package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求参数（视图层入参模型）。
 *
 * <p><b>关于密码长度上限的说明</b>：这里限制为 6~64 位而不是"越长越好"。
 * 上限尤其重要——BCrypt 只处理前 72 字节，超长密码会被静默截断，
 * 两个不同的超长密码可能得到相同哈希，形成可绕过的等价类。</p>
 */
public record LoginRequest(

        @NotBlank(message = "账号不能为空")
        @Size(min = 4, max = 32, message = "账号长度需为 4~32 个字符")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "账号只能包含字母、数字和下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需为 6~64 个字符")
        String password,

        /**
         * 验证码答案索引，与 {@code captchaCode} 配套。
         * 后端按"是否需要验证码"决定是否强制校验这两个字段。
         */
        @Size(max = 64, message = "验证码标识不合法")
        String captchaKey,

        @Size(max = 10, message = "验证码长度不合法")
        String captchaCode
) {
}