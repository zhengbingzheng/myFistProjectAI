package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户自助修改密码。
 *
 * <p>必须校验原密码：仅凭一个有效 Token 就能改密意味着 XSS 或会话劫持后
 * 攻击者可以直接锁定账号（把密码改掉），原密码这一环能挡住这一步。</p>
 */
public record ChangePasswordRequest(

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需为 6~64 个字符")
        String newPassword
) {
}
