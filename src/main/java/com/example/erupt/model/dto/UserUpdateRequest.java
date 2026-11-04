package com.example.erupt.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新用户请求参数（视图层入参模型）。
 */
public record UserUpdateRequest(
        @Size(max = 32, message = "昵称不能超过 32 个字符")
        String nickname,

        @Size(max = 512, message = "头像地址不能超过 512 个字符")
        String avatar,

        @Email(message = "邮箱格式不正确")
        @Size(max = 64, message = "邮箱不能超过 64 个字符")
        String email,

        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Min(value = 0, message = "状态取值只能为 0（禁用）或 1（启用）")
        @Max(value = 1, message = "状态取值只能为 0（禁用）或 1（启用）")
        Integer status,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
