package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户-角色关联实体，对应表 {@code t_user_role}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRole {

    private String id;
    private String userId;
    private String roleId;
    /** 删除标记：0 正常，1 已解除 */
    private String delFlag;
    private String createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
