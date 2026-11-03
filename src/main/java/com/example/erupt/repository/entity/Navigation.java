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
 * <p>{@code createUserId} / {@code updateUserId} 为审计字段，当前不在应用层填充，
 * 待接入登录态后由拦截器或切面统一赋值。</p>
 *
 * <p>getter/setter 等样板代码由 Lombok 在编译期生成。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Navigation {

    /** 主键，自增 */
    private Long id;
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
    /** 创建人 ID（create_user_id） */
    private Long createUserId;
    /** 更新人 ID（update_user_id） */
    private Long updateUserId;
    /** 创建时间（create_time） */
    private LocalDateTime createTime;
    /** 更新时间（update_time） */
    private LocalDateTime updateTime;
}
