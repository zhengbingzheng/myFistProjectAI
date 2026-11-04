package com.example.erupt.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单树节点（递归结构）。
 *
 * <p>数据库里菜单是扁平的（只有 parent_id 自关联），树在应用层组装后一次性下发：
 * 前端拿到即可直接渲染多层菜单，不必自己做扁平转树的逻辑，
 * 也避免了"前后端两棵树算法不一致导致顺序不同"的问题。</p>
 */
public record MenuVO(
        String id,
        String parentId,
        String menuName,
        String menuCode,
        Integer menuType,
        String path,
        String component,
        String icon,
        Integer sortNum,
        Integer visible,
        String permission,
        String remark,
        LocalDateTime createTime,
        List<MenuVO> children
) {
}
