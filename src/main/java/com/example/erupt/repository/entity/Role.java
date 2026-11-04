package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统角色实体，对应表 {@code t_role}。
 *
 * <p>角色即"用户分级"：{@code roleLevel} 数字越小权限越高（0 为超级管理员）。
 * 分级两个用途：一是授权时的数据范围（低等级不能给高等级配权），
 * 二是防止管理员误把自己降权后无法恢复。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    private String id;
    private String roleCode;
    private String roleName;
    /** 角色等级，数字越小权限越高 */
    private Integer roleLevel;
    private String description;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
