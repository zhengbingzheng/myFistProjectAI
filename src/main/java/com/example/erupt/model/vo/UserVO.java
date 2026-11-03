package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 用户视图对象（视图层出参模型），不直接暴露数据层实体结构。
 *
 * <p>出于安全考虑不返回 {@code password} 与软删除相关的 {@code delFlag} 等字段。</p>
 */
public record UserVO(String id, String username, String nickname, String avatar, String email, String phone,
                     Integer status, String remark, LocalDateTime lastLoginTime,
                     LocalDateTime createTime, LocalDateTime updateTime) {
}
