package com.example.erupt.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色（含其已授权的菜单ID），供授权页面一次取全。
 */
public record RoleVO(
        String id,
        String roleCode,
        String roleName,
        Integer roleLevel,
        String description,
        LocalDateTime createTime,
        List<String> menuIds
) {
}
