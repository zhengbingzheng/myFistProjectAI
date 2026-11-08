package com.example.erupt.service.impl;

import com.example.erupt.common.RequestScopeCache;
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
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.MenuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final UserMapper userMapper;

    public MenuServiceImpl(MenuMapper menuMapper, UserMapper userMapper) {
        this.menuMapper = menuMapper;
        this.userMapper = userMapper;
    }

    @Override
    public List<MenuVO> menuTree() {
        return buildTree(menuMapper.selectAllMenus());
    }

    @Override
    public List<MenuVO> selectMenusByUserId(String userId) {
        List<Menu> authorized = menuMapper.selectMenusByUserId(userId);
        return buildTree(fillMissingAncestors(authorized));
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
        requireAffected(menuMapper.insertMenu(menu), "菜单新增失败");
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
        if (request.menuCode() != null && !request.menuCode().isBlank()) {
            Menu duplicated = menuMapper.selectMenuByCode(request.menuCode());
            if (duplicated != null && !duplicated.getId().equals(id)) {
                throw new BusinessException(ResultCode.CONFLICT, "菜单编码已存在");
            }
            existing.setMenuCode(request.menuCode());
        }
        if (request.menuType() != null) {
            existing.setMenuType(request.menuType());
        }
        // 与其余字段一致按 null 跳过：XML 的 <if> 同样过滤 null，
        // 此前这里的无条件 set 只是无效赋值，还让人误以为传 null 能清空字段
        if (request.path() != null) {
            existing.setPath(request.path());
        }
        if (request.component() != null) {
            existing.setComponent(request.component());
        }
        if (request.icon() != null) {
            existing.setIcon(request.icon());
        }
        if (request.sortNum() != null) {
            existing.setSortNum(request.sortNum());
        }
        if (request.visible() != null) {
            existing.setVisible(request.visible());
        }
        if (request.permission() != null) {
            existing.setPermission(request.permission());
        }
        if (request.remark() != null) {
            existing.setRemark(request.remark());
        }
        existing.setUpdateBy(operator);
        requireAffected(menuMapper.updateMenu(existing), "菜单更新失败");
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
        requireAffected(menuMapper.updateMenu(menu), "菜单删除失败");
        log.info("删除菜单 id={}", id);
    }

    /* ---------- 角色 ---------- */

    @Override
    public List<RoleVO> listRoles() {
        List<Role> roles = menuMapper.selectAllRoles();
        // 一次取回全部角色-菜单关系在内存分组，避免逐角色查一次的 N+1
        Map<String, List<String>> menuIdsByRole = new HashMap<>();
        for (RoleMenu relation : menuMapper.selectAllRoleMenus()) {
            menuIdsByRole.computeIfAbsent(relation.getRoleId(), k -> new ArrayList<>())
                    .add(relation.getMenuId());
        }
        List<RoleVO> result = new ArrayList<>(roles.size());
        for (Role role : roles) {
            result.add(new RoleVO(role.getId(), role.getRoleCode(), role.getRoleName(),
                    role.getRoleLevel(), role.getDescription(), role.getCreateTime(),
                    menuIdsByRole.getOrDefault(role.getId(), List.of())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO createRole(RoleRequest request, String operatorId, String operator) {
        Role duplicated = menuMapper.selectRoleByCode(request.roleCode());
        if (duplicated != null) {
            throw new BusinessException(ResultCode.CONFLICT, "角色编码已存在");
        }
        int level = request.roleLevel() == null ? 10 : request.roleLevel();
        requireNoEscalation(level, operatorId);
        Role role = new Role();
        role.setId(newId());
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName());
        role.setRoleLevel(level);
        role.setDescription(request.description());
        role.setDelFlag("0");
        role.setCreateBy(operator);
        requireAffected(menuMapper.insertRole(role), "角色新增失败");
        return new RoleVO(role.getId(), role.getRoleCode(), role.getRoleName(),
                role.getRoleLevel(), role.getDescription(), role.getCreateTime(), List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateRole(String id, RoleRequest request, String operatorId, String operator) {
        Role existing = requireRole(id);
        // 不能修改比自己等级更高的角色。旧实现只校验「新等级」(requireNoEscalation)，
        // 不看角色「现有等级」——level=1 的管理员可以把超级管理员角色(0)改成 10，
        // 再通过 grantRolesToUser 分配给自己：后者只看角色的「当前」等级，改完就一路放行，
        // 整条等级防线被绕过。deleteRole 对 0 级角色有保护，编辑接口同样必须有。
        int ownLevel = userLevel(operatorId);
        int existingLevel = existing.getRoleLevel() == null ? Integer.MAX_VALUE : existing.getRoleLevel();
        if (existingLevel < ownLevel) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "无权修改该角色（角色等级高于当前操作者）");
        }
        // roleCode 是程序内引用的稳定标识，编辑接口不接受修改
        existing.setRoleName(request.roleName());
        if (request.roleLevel() != null) {
            requireNoEscalation(request.roleLevel(), operatorId);
            existing.setRoleLevel(request.roleLevel());
        }
        if (request.description() != null) {
            existing.setDescription(request.description());
        }
        existing.setUpdateBy(operator);
        requireAffected(menuMapper.updateRole(existing), "角色更新失败");
        Role updated = menuMapper.selectRoleById(id);
        return new RoleVO(updated.getId(), updated.getRoleCode(), updated.getRoleName(),
                updated.getRoleLevel(), updated.getDescription(), updated.getCreateTime(),
                menuMapper.selectMenuIdsByRoleId(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(String id) {
        Role existing = requireRole(id);
        if (Integer.valueOf(0).equals(existing.getRoleLevel())) {
            // 超级管理员角色不可删：删掉后没有任何人有权限恢复，系统直接进入不可用状态
            throw new BusinessException(ResultCode.BAD_REQUEST, "超级管理员角色不可删除");
        }
        menuMapper.deleteRoleMenusByRoleId(id);
        // 用户-角色关联一并清掉：角色已软删除，关联行留着只会越积越多
        menuMapper.deleteUserRolesByRoleId(id);
        Role role = new Role();
        role.setId(id);
        role.setDelFlag("1");
        requireAffected(menuMapper.updateRole(role), "角色删除失败");
        log.info("删除角色 id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grantMenusToRole(String roleId, List<String> menuIds, String operatorId, String operator) {
        Role role = requireRole(roleId);
        int ownLevel = userLevel(operatorId);
        // 二次鉴权：操作者不能给比自己等级更高的角色授权菜单（数字越小越高，0=超管）。
        // 漏了这道防线，普通管理员就能借"给超管角色配菜单"变相提权
        int roleLevel = role.getRoleLevel() == null ? Integer.MAX_VALUE : role.getRoleLevel();
        if (roleLevel < ownLevel) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "无权给该角色授权菜单（角色等级高于当前操作者）");
        }
        Set<String> validMenuIds = new HashSet<>();
        for (Menu menu : menuMapper.selectAllMenus()) {
            validMenuIds.add(menu.getId());
        }
        // 只能授出自己实际拥有的菜单：菜单 = 模块 API 的访问权，若允许同级管理员
        // 随意勾选，持有「菜单/角色管理」的人可以把自己的角色扩成全部模块，
        // 等级限制形同虚设。顶级管理员（level=0）不受限，负责初始授权。
        if (ownLevel > 0 && menuIds != null && !menuIds.isEmpty()) {
            Set<String> ownMenuIds = new HashSet<>();
            for (Menu menu : menuMapper.selectMenusByUserId(operatorId)) {
                ownMenuIds.add(menu.getId());
            }
            List<String> unauthorized = menuIds.stream()
                    .filter(id -> id != null && validMenuIds.contains(id))
                    .filter(id -> !ownMenuIds.contains(id))
                    .distinct()
                    .toList();
            if (!unauthorized.isEmpty()) {
                throw new BusinessException(ResultCode.FORBIDDEN,
                        "不能授予自己未拥有的菜单权限：" + String.join(",", unauthorized));
            }
        }
        menuMapper.deleteRoleMenusByRoleId(roleId);
        if (menuIds == null || menuIds.isEmpty()) {
            return 0;
        }
        List<RoleMenu> relations = new ArrayList<>();
        for (String menuId : new HashSet<>(menuIds)) {
            if (menuId == null || menuId.isBlank() || !validMenuIds.contains(menuId)) {
                continue;
            }
            RoleMenu relation = new RoleMenu();
            relation.setId(newId());
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            relation.setCreateBy(operator);
            relations.add(relation);
        }
        if (!relations.isEmpty()) {
            requireAffected(menuMapper.insertRoleMenus(relations), "角色授权失败");
        }
        log.info("角色授权菜单 roleId={} 授权数量={} operator={}", roleId, relations.size(), operator);
        return relations.size();
    }

    @Override
    public List<String> menuIdsOfRole(String roleId) {
        // 与 grantMenusToRole 口径对齐：角色不存在就是 404，而不是悄悄返回空列表
        requireRole(roleId);
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
    public int grantRolesToUser(String userId, List<String> roleIds, String operatorId, String operator) {
        // 不校验用户存在的话，给不存在的 userId 授权会"成功"，留下一批谁也读不到的关联行
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        int ownLevel = userLevel(operatorId);
        // 目标用户当前的最高权限等级（无角色为 MAX_VALUE）：不能动比自己等级更高的用户的角色。
        // 这道防线必须盖住空列表路径，否则"不能给超管授权，却能用空列表把超管的角色清空"——
        // 下面的逐角色校验只看要授予的角色，管不住"清空"这个动作
        Integer targetLevel = menuMapper.selectUserMinRoleLevel(userId);
        if (targetLevel != null && targetLevel < ownLevel) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能修改比自己等级更高的用户的角色");
        }
        if (roleIds != null && !roleIds.isEmpty()) {
            // 一次取回全部角色：既是有效性过滤的白名单，也是等级越权校验的依据
            Map<String, Integer> levelByRole = new HashMap<>();
            for (Role role : menuMapper.selectAllRoles()) {
                levelByRole.put(role.getId(), role.getRoleLevel());
            }
            // 整批校验等级：任一角色比操作者等级更高则一条都不写
            for (String roleId : new HashSet<>(roleIds)) {
                Integer level = levelByRole.get(roleId);
                if (level != null && level < ownLevel) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "不能分配比自己等级更高的角色");
                }
            }
            menuMapper.deleteUserRolesByUserId(userId);
            List<UserRole> relations = new ArrayList<>();
            for (String roleId : new HashSet<>(roleIds)) {
                if (roleId == null || roleId.isBlank() || !levelByRole.containsKey(roleId)) {
                    continue;
                }
                UserRole relation = new UserRole();
                relation.setId(newId());
                relation.setUserId(userId);
                relation.setRoleId(roleId);
                relation.setDelFlag("0");
                relation.setCreateBy(operator);
                relations.add(relation);
            }
            if (!relations.isEmpty()) {
                requireAffected(menuMapper.insertUserRoles(relations), "用户授权失败");
            }
            log.info("用户分配角色 userId={} 角色数={} operator={}", userId, relations.size(), operator);
            return relations.size();
        }
        // 空列表 = 清空该用户全部角色，同样走"先删后插"的前半段
        menuMapper.deleteUserRolesByUserId(userId);
        return 0;
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
        Boolean cached = RequestScopeCache.permission(userId, permission);
        if (cached != null) {
            return cached;
        }
        boolean allowed = menuMapper.countPermissionForUser(userId, permission) > 0;
        RequestScopeCache.rememberPermission(userId, permission, allowed);
        return allowed;
    }

    @Override
    public Integer userLevel(String userId) {
        Integer level = menuMapper.selectUserMinRoleLevel(userId);
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
     *
     * <p>先在已授权集合内把每条祖先链走到底：链上任何一个ID不在集合里即为缺失，
     * 只有确有缺失时才值得再查一次全表来补齐。</p>
     */
    private List<Menu> fillMissingAncestors(List<Menu> authorized) {
        Map<String, Menu> owned = new HashMap<>();
        for (Menu menu : authorized) {
            owned.put(menu.getId(), menu);
        }
        boolean missing = false;
        for (Menu menu : authorized) {
            String parentId = menu.getParentId();
            while (parentId != null && !ROOT_PARENT_ID.equals(parentId)) {
                Menu parent = owned.get(parentId);
                if (parent == null) {
                    missing = true;
                    break;
                }
                parentId = parent.getParentId();
            }
            if (missing) {
                break;
            }
        }
        if (!missing) {
            return authorized;
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
                if (owned.containsKey(parentId) || added.contains(parentId)) {
                    break;
                }
                result.add(all.get(parentId));
                added.add(parentId);
                parentId = all.get(parentId).getParentId();
            }
        }
        return result;
    }

    /** 判断 candidateId 是否是 ancestorId 的子孙（一次载入全表后在内存回溯 parent 链） */
    private boolean isDescendant(String candidateId, String ancestorId) {
        Map<String, Menu> all = new HashMap<>();
        for (Menu menu : menuMapper.selectAllMenus()) {
            all.put(menu.getId(), menu);
        }
        String current = candidateId;
        // 最多追溯 32 层：既是防止数据成环时死循环，正常业务也不会有这么深的菜单
        for (int i = 0; i < 32 && current != null && !ROOT_PARENT_ID.equals(current); i++) {
            if (ancestorId.equals(current)) {
                return true;
            }
            Menu node = all.get(current);
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

    private Role requireRole(String id) {
        Role role = menuMapper.selectRoleById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    /**
     * 项目约定：所有写操作都要校验数据库返回的受影响行数，
     * 不符即判定业务失败并回滚，绝不返回"部分成功"这种无法判断结果的状态。
     */
    private static void requireAffected(int affected, String message) {
        if (affected < 1) {
            log.warn("{} affected=0", message);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, message);
        }
    }

    /**
     * 角色等级越权校验：等级数字越小权限越高，
     * 操作者不能创建/修改"比自己等级更高"（数字更小）的角色——
     * 否则持有菜单管理权的管理员给自己造一个超级管理员角色就能一步提权，
     * t_role 建表注释里"防止越权给自个儿提权"的意图由这里落地。
     * 无角色（含 operatorId 为空）按最低权限处理，任何角色都创建不了。
     */
    private void requireNoEscalation(int level, String operatorId) {
        if (level < userLevel(operatorId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "角色等级不能高于操作者自身等级");
        }
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
