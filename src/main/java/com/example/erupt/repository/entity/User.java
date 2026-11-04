package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体（数据层），与表 {@code t_user} 一一对应。
 *
 * <p>主键 {@code id} 为字符串（32 位 UUID 去横线），由应用层生成，不使用数据库自增。
 * {@code delFlag} 决定用户是否被删除，删除只做软删除（置 1），不物理删除记录。</p>
 *
 * <p>getter/setter/无参构造/全参构造由 Lombok 在编译期生成，MyBatis 依赖无参构造器与
 * setter 完成结果映射与新增操作。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /** 主键，字符串（32 位 UUID 去横线） */
    private String id;
    /** 登录账号，仅未删除记录唯一 */
    private String username;
    /** 登录密码哈希（BCrypt），不存明文 */
    private String password;
    /** 昵称 */
    private String nickname;
    /** 头像 URL */
    private String avatar;
    /** 邮箱 */
    private String email;
    /** 手机号 */
    private String phone;
    /** 状态：1 启用，0 禁用 */
    private Integer status;
    /** 备注 */
    private String remark;
    /** 最近登录时间 */
    private LocalDateTime lastLoginTime;
    /** 删除标记：0 正常，1 已删除（软删除） */
    private String delFlag;
    /** 删除操作人（账号） */
    private String deleteBy;
    /** 删除时间 */
    private LocalDateTime deleteTime;
    /** 创建人（账号），接入登录态后填充 */
    private String createBy;
    /** 更新人（账号），接入登录态后填充 */
    private String updateBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
