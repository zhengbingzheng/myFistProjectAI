package com.example.erupt.service;

import com.example.erupt.model.dto.MenuRequest;
import com.example.erupt.model.dto.RoleRequest;
import com.example.erupt.model.vo.MenuVO;
import com.example.erupt.model.vo.RoleVO;

import java.util.List;

/**
 * 菜单与授权业务。
 *
 * <p>对外两件事：
 * <ul>
 *   <li><b>看</b>：{@link #selectMenusByUserId} 返回该用户被授权的菜单树，前端据此渲染菜单；</li>
 *   <li><b>拦</b>：{@link #hasMenuPathAccess} 判定某路径是否可访问，拦截器据此放行或拒绝。</li>
 * </ul>
 * 两者必须同源——都用 <code>countMenuPathForUser</code> 那条 SQL，
 * 否则会出现"菜单里没有但直接输地址能进"的洞。</p>
 */
public interface MenuService {

    /** 全量菜单树（管理端用） */
    List<MenuVO> menuTree();

    /** 当前用户被授权的菜单树 */
    List<MenuVO> selectMenusByUserId(String userId);

    MenuVO createMenu(MenuRequest request, String operator);

    MenuVO updateMenu(String id, MenuRequest request, String operator);

    /** 删除菜单；有子节点时拒绝，避免产生孤儿菜单 */
    void deleteMenu(String id);

    /* ---------- 角色 ---------- */

    List<RoleVO> listRoles();

    RoleVO createRole(RoleRequest request, String operator);

    RoleVO updateRole(String id, RoleRequest request);

    void deleteRole(String id);

    /** 给角色授权菜单（全量覆盖） */
    int grantMenusToRole(String roleId, List<String> menuIds, String operator);

    List<String> menuIdsOfRole(String roleId);

    /* ---------- 用户-角色 ---------- */

    List<RoleVO> rolesOfUser(String userId);

    /** 给用户分配角色（全量覆盖） */
    int grantRolesToUser(String userId, List<String> roleIds, String operator);

    /**
     * 该用户是否可访问指定路径。
     * 这是"未授权页面不可访问"的唯一判定入口。
     */
    boolean hasMenuPathAccess(String userId, String path);

    /** 该用户是否拥有指定权限标识（接口级鉴权） */
    boolean hasPermission(String userId, String permission);

    /** 用户的权限等级（取角色等级的极小值） */
    Integer userLevel(String userId);
}
