package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 导航实体（数据层），与库表 {@code erupt-example.t_navigation} 一一对应。
 *
 * <p>库表列 {@code desc} 为 MySQL 保留字，实体属性命名为 {@code description}，
 * 由 {@code resources/mapper/NavigationMapper.xml} 的 resultMap 完成映射。</p>
 *
 * <p>{@code createUserId} 为审计字段：新增与导入时由服务层从 {@code AuthContext}
 * 取当前登录用户 id 写入，列表展示时再由查询 LEFT JOIN {@code t_user} 带出账号名。</p>
 *
 * <p>getter/setter 等样板代码由 Lombok 在编译期生成。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Navigation {

    /** 主键，32 位字符串（应用层生成的 UUID 去横线），不使用数据库自增 */
    private String id;
    /** 图标（navigation_img） */
    private String navigationImg;
    /** 名称（navigation_name） */
    private String navigationName;
    /** 链接地址（navigation_url） */
    private String navigationUrl;
    /** 分类（cat） */
    private String cat;
    /** 描述（desc） */
    private String description;
    /** 标签（tag） */
    private String tag;
    /**
     * 创建人 ID（create_user_id），即 {@code t_user.id}（varchar(64) 字符串主键）。
     *
     * <p>类型必须是 String 而不是 Long：t_user 主键是随机串而非数字，
     * 用 Long 既装不下也没法与 t_user 关联。</p>
     */
    private String createUserId;

    /** 更新人 ID（update_user_id），同为 {@code t_user.id}（32 位字符串） */
    private String updateUserId;

    /**
     * 创建人账号（非库表字段）。
     *
     * <p>仅列表查询通过 LEFT JOIN {@code t_user} 填充，用于给前端展示可读的创建人；
     * 其余查询（详情 / 按分类取数）不带这个 join，此字段保持 null。
     * 直接展示 createUserId 是一串无意义的随机字符，对使用者没有价值。</p>
     */
    private String createUserName;

    /** 创建时间（create_time） */
    private LocalDateTime createTime;
    /** 更新时间（update_time） */
    private LocalDateTime updateTime;
}
