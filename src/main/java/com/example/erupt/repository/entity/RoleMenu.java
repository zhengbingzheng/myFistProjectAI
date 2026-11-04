package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 角色-菜单关联实体，对应表 {@code t_role_menu}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleMenu {

    private String id;
    private String roleId;
    private String menuId;
    private String createBy;
    private LocalDateTime createTime;
}
