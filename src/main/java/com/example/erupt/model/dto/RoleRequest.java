package com.example.erupt.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 角色新建/编辑请求。
 *
 * @param roleLevel 角色等级，数字越小权限越高（0 为超级管理员）
 */
public record RoleRequest(
        @NotBlank(message = "角色编码不能为空")
        @Size(max = 64, message = "角色编码不能超过 64 个字符")
        String roleCode,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称不能超过 64 个字符")
        String roleName,

        @Min(value = 0, message = "角色等级不能为负数")
        Integer roleLevel,

        @Size(max = 500, message = "描述不能超过 500 个字符")
        String description
) {
}
