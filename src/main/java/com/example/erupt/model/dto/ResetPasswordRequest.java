package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员重置他人密码。
 *
 * <p>刻意<b>不</b>校验目标用户的原密码——这正是"重置"与"改密"的差别：
 * 管理员本就无法知道用户的原密码，要求它等于让这个功能不可用。</p>
 */
public record ResetPasswordRequest(

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需为 6~64 个字符")
        String newPassword
) {
}
