package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 菜单新建/编辑请求。
 *
 * <p><b>多级菜单怎么建</b>：靠 {@code parentId} 指认父节点——
 * 传 {@code "0"} 或不传表示顶级菜单；传已有菜单ID 则成为它的子菜单。
 * 层级深度不做限制，由 parentId 链自然形成树。</p>
 *
 * <p>之所以不在菜单表里加 {@code level} / {@code path_ids} 这类冗余字段：
 * 一旦父节点移动，所有子孙的冗余值都要跟着改，容易写漏。
 * 宁可在查询时多一层递归，也不维护会腐烂的派生数据。</p>
 */
public record MenuRequest(
        /** 父菜单ID，顶级传 "0" */
        String parentId,

        @NotBlank(message = "菜单名称不能为空")
        @Size(max = 64, message = "菜单名称不能超过 64 个字符")
        String menuName,

        @Size(max = 64, message = "菜单编码不能超过 64 个字符")
        String menuCode,

        /** 类型：1 目录，2 菜单，3 按钮 */
        Integer menuType,

        @Size(max = 255, message = "路由地址不能超过 255 个字符")
        String path,

        @Size(max = 255, message = "组件路径不能超过 255 个字符")
        String component,

        @Size(max = 64, message = "图标不能超过 64 个字符")
        String icon,

        Integer sortNum,

        /** 是否显示：0 隐藏，1 显示 */
        Integer visible,

        @Size(max = 128, message = "权限标识不能超过 128 个字符")
        String permission,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String remark
) {
}
