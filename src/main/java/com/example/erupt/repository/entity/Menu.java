package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统菜单实体，对应表 {@code t_menu}。
 *
 * <p>多级结构靠 {@code parentId} 自关联实现，顶级节点的 parentId 为 {@code "0"}。
 * 同级顺序靠 {@code sortNum}。树在应用层组装，数据库只存扁平记录——
 * 这样调整层级只需改 parentId，不必移动整棵子树。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Menu {

    private String id;
    /** 父菜单ID，顶级为 0 */
    private String parentId;
    private String menuName;
    private String menuCode;
    /** 类型：1 目录，2 菜单，3 按钮 */
    private Integer menuType;
    /** 路由地址 */
    private String path;
    /** 前端组件 */
    private String component;
    private String icon;
    /** 同级排序号 */
    private Integer sortNum;
    /** 是否显示：0 隐藏，1 显示 */
    private Integer visible;
    /** 权限标识 */
    private String permission;
    private String remark;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
