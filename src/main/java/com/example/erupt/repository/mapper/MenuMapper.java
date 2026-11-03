package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.Menu;
import com.example.erupt.repository.entity.Role;
import com.example.erupt.repository.entity.RoleMenu;
import com.example.erupt.repository.entity.UserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 菜单与授权的数据访问。
 *
 * <p>菜单 / 角色 / 两张关联表集中于此，理由同 ChatMapper：授权是一次性读写多张表的操作，
 * 放一起能让事务边界清楚落在 Service 层。</p>
 */
@Mapper
public interface MenuMapper {

    /* ---------- 菜单 ---------- */

    int insertMenu(Menu menu);

    int updateMenu(Menu menu);

    Menu selectMenuById(@Param("id") String id);

    Menu selectMenuByCode(@Param("menuCode") String menuCode);

    /** 全量菜单（扁平），按层级与排序号，树在应用层组装 */
    List<Menu> selectAllMenus();

    /** 子菜单数量：删除前的叶子/空目录校验 */
    int countChildren(@Param("parentId") String parentId);

    /* ---------- 角色 ---------- */

    int insertRole(Role role);

    int updateRole(Role role);

    Role selectRoleById(@Param("id") String id);

    Role selectRoleByCode(@Param("roleCode") String roleCode);

    List<Role> selectAllRoles();

    /* ---------- 用户-角色 ---------- */

    int insertUserRole(UserRole userRole);

    List<String> selectRoleIdsByUserId(@Param("userId") String userId);

    /** 清空某用户的全部角色后再重建——全量覆盖式授权，避免逐个 diff */
    int deleteUserRolesByUserId(@Param("userId") String userId);

    List<Role> selectRolesByUserId(@Param("userId") String userId);

    /* ---------- 角色-菜单 ---------- */

    int insertRoleMenu(RoleMenu roleMenu);

    List<String> selectMenuIdsByRoleId(@Param("roleId") String roleId);

    int deleteRoleMenusByRoleId(@Param("roleId") String roleId);

    /** 删除某菜单与所有角色的授权关系：菜单删除时必须清理，否则角色里会残留失效菜单ID */
    int deleteRoleMenusByMenuId(@Param("menuId") String menuId);

    /**
     * 某用户被授权的全部可见菜单（扁平，去重）。
     * 走 user_role → role_menu → menu 三级联查，未授权菜单根本不会出现在结果里。
     */
    List<Menu> selectMenusByUserId(@Param("userId") String userId);

    /**
     * 该用户是否被授权了指定路径。
     * 这是后端"未授权页面不可访问"的判定依据——前端隐藏菜单只是体验，
     * 真正的边界必须在这一层。
     */
    int countMenuPathForUser(@Param("userId") String userId, @Param("path") String path);

    /**
     * 该用户是否拥有指定权限标识。
     * 接口级鉴权用：菜单前端路由地址（/knowledge/list）与 API 地址（/api/knowledge）
     * 不是一回事，靠 permission 标识把两者关联起来。
     */
    int countPermissionForUser(@Param("userId") String userId, @Param("permission") String permission);

    /** 用户的最高权限等级（角色等级取最小值） */
    Integer selectUserMaxLevel(@Param("userId") String userId);
}
