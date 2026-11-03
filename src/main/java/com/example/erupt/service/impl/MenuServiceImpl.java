package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.MenuRequest;
import com.example.erupt.model.dto.RoleRequest;
import com.example.erupt.model.vo.MenuVO;
import com.example.erupt.model.vo.RoleVO;
import com.example.erupt.repository.entity.Menu;
import com.example.erupt.repository.entity.Role;
import com.example.erupt.repository.entity.RoleMenu;
import com.example.erupt.repository.entity.UserRole;
import com.example.erupt.repository.mapper.MenuMapper;
import com.example.erupt.service.MenuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 菜单与授权业务实现。
 *
 * <p><b>多级菜单的存储与树的组装</b>：库里只存扁平记录和 {@code parentId}，
 * 树在 {@link #buildTree(List)} 里一次性组装。
 * 采用「按父分组 → 逐层挂子」而不是对每个节点递归查库：
 * 后者在菜单量大时会退化成 N 次查询（经典的 N+1）。</p>
 */
@Slf4j
@Service
public class MenuServiceImpl implements MenuService {

    /** 顶级菜单的父ID约定值 */
    private static final String ROOT_PARENT_ID = "0";

    private final MenuMapper menuMapper;

    public MenuServiceImpl(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    @Override
    public List<MenuVO> menuTree() {
        return buildTree(menuMapper.selectAllMenus());
    }

    @Override
    public List<MenuVO> selectMenusByUserId(String userId) {
        List<Menu> authorized = menuMapper.selectMenusByUserId(userId);
        return buildTree(pruneNorParented(authorized));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MenuVO createMenu(MenuRequest request, String operator) {
        String parentId = normalizeParent(request.parentId());
        if (!ROOT_PARENT_ID.equals(parentId)) {
            Menu parent = menuMapper.selectMenuById(parentId);
            if (parent == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "父菜单不存在");
            }
        }
        if (request.menuCode() != null && !request.menuCode().isBlank()) {
            Menu duplicated = menuMapper.selectMenuByCode(request.menuCode());
            if (duplicated != null) {
                throw new BusinessException(ResultCode.CONFLICT, "菜单编码已存在");
            }
        }
        Menu menu = new Menu();
        menu.setId(newId());
        menu.setParentId(parentId);
        menu.setMenuName(request.menuName());
        menu.setMenuCode(request.menuCode());
        menu.setMenuType(request.menuType() == null ? 2 : request.menuType());
        menu.setPath(request.path());
        menu.setComponent(request.component());
        menu.setIcon(request.icon());
        menu.setSortNum(request.sortNum() == null ? 0 : request.sortNum());
        menu.setVisible(request.visible() == null ? 1 : request.visible());
        menu.setPermission(request.permission());
        menu.setRemark(request.remark());
        menu.setDelFlag("0");
        menu.setCreateBy(operator);
        menuMapper.insertMenu(menu);
        log.info("新建菜单 id={} name={} parent={} operator={}", menu.getId(), menu.getMenuName(), parentId, operator);
        return toVO(menuMapper.selectMenuById(menu.getId()), List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MenuVO updateMenu(String id, MenuRequest request, String operator) {
        Menu existing = requireMenu(id);
        if (request.parentId() != null && !request.parentId().isBlank()) {
            String parentId = request.parentId();
            // 禁止把父节点指到自己或自己的子孙——那会立刻形成环，树组装时死循环
            if (parentId.equals(id)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "父菜单不能是自己");
            }
            if (isDescendant(parentId, id)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不能将菜单移动到自己的子菜单下");
            }
            if (!ROOT_PARENT_ID.equals(parentId) && menuMapper.selectMenuById(parentId) == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "父菜单不存在");
            }
            existing.setParentId(parentId);
        }
        if (request.menuName() != null) {
            existing.setMenuName(request.menuName());
        }
        if (request.menuCode() != null) {
            Menu duplicated = menuMapper.selectMenuByCode(request.menuCode());
            if (duplicated != null && !duplicated.getId().equals(id)) {
                throw new BusinessException(ResultCode.CONFLICT, "菜单编码已存在");
            }
            existing.setMenuCode(request.menuCode());
        }
        if (request.menuType() != null) {
            existing.setMenuType(request.menuType());
        }
        existing.setPath(request.path());
        existing.setComponent(request.component());
        existing.setIcon(request.icon());
        if (request.sortNum() != null) {
            existing.setSortNum(request.sortNum());
        }
        if (request.visible() != null) {
            existing.setVisible(request.visible());
        }
        existing.setPermission(request.permission());
        existing.setRemark(request.remark());
        existing.setUpdateBy(operator);
        menuMapper.updateMenu(existing);
        return toVO(menuMapper.selectMenuById(id), List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(String id) {
        requireMenu(id);
        int children = menuMapper.countChildren(id);
        if (children > 0) {
            // 拒绝级联删除：菜单授权关系也会一并消失，属于误操作成本很高的一类删除
            throw new BusinessException(ResultCode.BAD_REQUEST, "存在 " + children + " 个子菜单，请先删除子菜单");
        }
        menuMapper.deleteRoleMenusByMenuId(id);
        // 软删除菜单本体
        Menu menu = new Menu();
        menu.setId(id);
        menu.setDelFlag("1");
        menuMapper.updateMenu(menu);
        log.info("删除菜单 id={}", id);
    }

    /* ---------- 角色 ---------- */

    @Override
    public List<RoleVO> listRoles() {
        List<RoleVO> result = new ArrayList<>();
        for (Role role : menuMapper.selectAllRoles()) {
            result.add(new RoleVO(role.getId(), role.getRoleCode(), role.getRoleName(),
                    role.getRoleLevel(), role.getDescription(), role.getCreateTime(),
                    menuMapper.selectMenuIdsByRoleId(role.getId())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO createRole(RoleRequest request, String operator) {
        Role duplicated = menuMapper.selectRoleByCode(request.roleCode());
        if (duplicated != null) {
            throw new BusinessException(ResultCode.CONFLICT, "角色编码已存在");
        }
        Role role = new Role();
        role.setId(newId());
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName());
        role.setRoleLevel(request.roleLevel() == null ? 10 : request.roleLevel());
        role.setDescription(request.description());
        role.setDelFlag("0");
        role.setCreateBy(operator);
        menuMapper.insertRole(role);
        return new RoleVO(role.getId(), role.getRoleCode(), role.getRoleName(),
                role.getRoleLevel(), role.getDescription(), role.getCreateTime(), List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateRole(String id, RoleRequest request) {
        Role existing = menuMapper.selectRoleById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        existing.setRoleName(request.roleName());
        if (request.roleLevel() != null) {
            existing.setRoleLevel(request.roleLevel());
        }
        existing.setDescription(request.description());
        menuMapper.updateRole(existing);
        Role updated = menuMapper.selectRoleById(id);
        return new RoleVO(updated.getId(), updated.getRoleCode(), updated.getRoleName(),
                updated.getRoleLevel(), updated.getDescription(), updated.getCreateTime(),
                menuMapper.selectMenuIdsByRoleId(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(String id) {
        Role existing = menuMapper.selectRoleById(id);
        if (existing != null && Integer.valueOf(0).equals(existing.getRoleLevel())) {
            // 超级管理员角色不可删：删掉后没有任何人有权限恢复，系统直接进入不可用状态
            throw new BusinessException(ResultCode.BAD_REQUEST, "超级管理员角色不可删除");
        }
        menuMapper.deleteRoleMenusByRoleId(id);
        Role role = new Role();
        role.setId(id);
        role.setDelFlag("1");
        menuMapper.updateRole(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grantMenusToRole(String roleId, List<String> menuIds, String operator) {
        if (menuMapper.selectRoleById(roleId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        menuMapper.deleteRoleMenusByRoleId(roleId);
        if (menuIds == null || menuIds.isEmpty()) {
            return 0;
        }
        Set<String> validMenuIds = new HashSet<>();
        for (Menu menu : menuMapper.selectAllMenus()) {
            validMenuIds.add(menu.getId());
        }
        int count = 0;
        for (String menuId : new HashSet<>(menuIds)) {
            if (menuId == null || menuId.isBlank() || !validMenuIds.contains(menuId)) {
                continue;
            }
            RoleMenu relation = new RoleMenu();
            relation.setId(newId());
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            relation.setCreateBy(operator);
            menuMapper.insertRoleMenu(relation);
            count++;
        }
        log.info("角色授权菜单 roleId={} 授权数量={} operator={}", roleId, count, operator);
        return count;
    }

    @Override
    public List<String> menuIdsOfRole(String roleId) {
        return menuMapper.selectMenuIdsByRoleId(roleId);
    }

    /* ---------- 用户-角色 ---------- */

    @Override
    public List<RoleVO> rolesOfUser(String userId) {
        List<RoleVO> result = new ArrayList<>();
        for (Role role : menuMapper.selectRolesByUserId(userId)) {
            result.add(new RoleVO(role.getId(), role.getRoleCode(), role.getRoleName(),
                    role.getRoleLevel(), role.getDescription(), role.getCreateTime(), List.of()));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grantRolesToUser(String userId, List<String> roleIds, String operator) {
        menuMapper.deleteUserRolesByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return 0;
        }
        Set<String> validRoleIds = new HashSet<>();
        for (Role role : menuMapper.selectAllRoles()) {
            validRoleIds.add(role.getId());
        }
        int count = 0;
        for (String roleId : new HashSet<>(roleIds)) {
            if (roleId == null || roleId.isBlank() || !validRoleIds.contains(roleId)) {
                continue;
            }
            UserRole relation = new UserRole();
            relation.setId(newId());
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            relation.setDelFlag("0");
            relation.setCreateBy(operator);
            menuMapper.insertUserRole(relation);
            count++;
        }
        log.info("用户分配角色 userId={} 角色数={} operator={}", userId, count, operator);
        return count;
    }

    @Override
    public boolean hasMenuPathAccess(String userId, String path) {
        if (userId == null || path == null || path.isBlank()) {
            return false;
        }
        return menuMapper.countMenuPathForUser(userId, path) > 0;
    }

    @Override
    public boolean hasPermission(String userId, String permission) {
        if (userId == null || permission == null || permission.isBlank()) {
            return false;
        }
        return menuMapper.countPermissionForUser(userId, permission) > 0;
    }

    @Override
    public Integer userLevel(String userId) {
        Integer level = menuMapper.selectUserMaxLevel(userId);
        return level == null ? Integer.MAX_VALUE : level;
    }

    /* ---------- 内部方法 ---------- */

    /**
     * 扁平列表组装成树。
     *
     * <p>两趟扫描：先建好所有节点的 VO（children 用可变列表），再按 parent_id 挂接。
     * 复杂度 O(n) —— 数据已全在内存，不存在逐节点查库的 N+1 问题。</p>
     *
     * <p>只把能挂到父节点的节点放进树：万一出现 parent 记录缺失（数据被手工改过），
     * 那些孤儿节点会作为顶级节点呈现，而不是悄无声息地消失。</p>
     */
    private List<MenuVO> buildTree(List<Menu> flat) {
        Map<String, MenuVO> nodeById = new LinkedHashMap<>();
        for (Menu menu : flat) {
            nodeById.put(menu.getId(), toVO(menu, new ArrayList<>()));
        }
        List<MenuVO> roots = new ArrayList<>();
        for (Menu menu : flat) {
            MenuVO node = nodeById.get(menu.getId());
            MenuVO parent = menu.getParentId() == null ? null : nodeById.get(menu.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children().add(node);
            }
        }
        sortRecursively(roots);
        return roots;
    }

    private void sortRecursively(List<MenuVO> nodes) {
        nodes.sort(Comparator.comparing(MenuVO::sortNum, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(MenuVO::createTime, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (MenuVO node : nodes) {
            if (node.children() != null && !node.children().isEmpty()) {
                sortRecursively(node.children());
            }
        }
    }

    /**
     * 授权结果里可能存在"子菜单被授权但父目录未被授权"的情况。
     * 若直接按 parent_id 组装，子节点会因找不到父节点被当作顶级，
     * 出现一个孤零零的子菜单。这里统一补上缺失的祖先，
     * 保证前端拿到的树是完整可渲染的。
     */
    private List<Menu> pruneNorParented(List<Menu> authorized) {
        Set<String> ownedIds = new HashSet<>();
        for (Menu menu : authorized) {
            ownedIds.add(menu.getId());
        }
        Map<String, Menu> all = new LinkedHashMap<>();
        for (Menu menu : menuMapper.selectAllMenus()) {
            all.put(menu.getId(), menu);
        }
        List<Menu> result = new ArrayList<>(authorized);
        Set<String> added = new HashSet<>();
        for (Menu menu : authorized) {
            String parentId = menu.getParentId();
            while (parentId != null && !ROOT_PARENT_ID.equals(parentId) && all.containsKey(parentId)) {
                if (ownedIds.contains(parentId) || added.contains(parentId)) {
                    break;
                }
                result.add(all.get(parentId));
                added.add(parentId);
                parentId = all.get(parentId).getParentId();
            }
        }
        return result;
    }

    /** 判断 candidateId 是否是 ancestorId 的子孙（向上追溯 parent 链） */
    private boolean isDescendant(String candidateId, String ancestorId) {
        String current = candidateId;
        // 最多追溯 32 层：既是防止数据成环时死循环，正常业务也不会有这么深的菜单
        for (int i = 0; i < 32 && current != null && !ROOT_PARENT_ID.equals(current); i++) {
            if (ancestorId.equals(current)) {
                return true;
            }
            Menu node = menuMapper.selectMenuById(current);
            if (node == null) {
                return false;
            }
            current = node.getParentId();
        }
        return false;
    }

    private Menu requireMenu(String id) {
        Menu menu = menuMapper.selectMenuById(id);
        if (menu == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "菜单不存在");
        }
        return menu;
    }

    private static String normalizeParent(String parentId) {
        return parentId == null || parentId.isBlank() ? ROOT_PARENT_ID : parentId;
    }

    private static MenuVO toVO(Menu menu, List<MenuVO> children) {
        return new MenuVO(menu.getId(), menu.getParentId(), menu.getMenuName(), menu.getMenuCode(),
                menu.getMenuType(), menu.getPath(), menu.getComponent(), menu.getIcon(),
                menu.getSortNum(), menu.getVisible(), menu.getPermission(), menu.getRemark(),
                menu.getCreateTime(), children);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
