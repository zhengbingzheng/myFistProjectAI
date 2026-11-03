package com.example.erupt.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增用户请求参数（视图层入参模型）。
 */
public record UserCreateRequest(
        @NotBlank(message = "账号不能为空")
        @Size(min = 4, max = 32, message = "账号长度需为 4~32 个字符")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "账号只能包含字母、数字和下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需为 6~64 个字符")
        String password,

        @Size(max = 32, message = "昵称不能超过 32 个字符")
        String nickname,

        @Size(max = 512, message = "头像地址不能超过 512 个字符")
        String avatar,

        @Email(message = "邮箱格式不正确")
        @Size(max = 64, message = "邮箱不能超过 64 个字符")
        String email,

        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
    /** 默认启用。 */
    public static final int DEFAULT_STATUS = 1;
}
