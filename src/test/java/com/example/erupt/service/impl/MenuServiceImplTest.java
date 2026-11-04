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
import com.example.erupt.repository.mapper.MenuMapper;
import com.example.erupt.repository.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜单与授权业务的单元测试。
 *
 * <p>重点覆盖会破坏数据一致性的分支：成环校验、孤儿清理、
 * 对不存在资源的删除/授权，以及树组装时缺失祖先的补齐。</p>
 */
@ExtendWith(MockitoExtension.class)
class MenuServiceImplTest {

    @Mock
    private MenuMapper menuMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private MenuServiceImpl menuService;

    /* ---------- 菜单 ---------- */

    @Test
    @DisplayName("createMenu_父菜单不存在_400且不写入")
    void createMenu_parentMissing_throwsBadRequest() {
        when(menuMapper.selectMenuById("nope")).thenReturn(null);

        assertThatThrownBy(() -> menuService.createMenu(new MenuRequest(
                "nope", "子菜单", null, 2, null, null, null, null, null, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(menuMapper, never()).insertMenu(any());
    }

    @Test
    @DisplayName("createMenu_菜单编码重复_409")
    void createMenu_duplicatedCode_throwsConflict() {
        when(menuMapper.selectMenuByCode("dashboard")).thenReturn(menu("m01", "0"));

        assertThatThrownBy(() -> menuService.createMenu(new MenuRequest(
                "0", "概览", "dashboard", 2, null, null, null, null, null, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("updateMenu_把父菜单移到自己子菜单下_拒绝成环")
    void updateMenu_moveIntoOwnSubtree_rejected() {
        // P 是顶级，C 是 P 的子菜单；把 P 的 parent 改成 C 就成环了
        Menu parent = menu("m-parent", "0");
        Menu child = menu("m-child", "m-parent");
        when(menuMapper.selectMenuById("m-parent")).thenReturn(parent);
        when(menuMapper.selectAllMenus()).thenReturn(List.of(parent, child));

        assertThatThrownBy(() -> menuService.updateMenu("m-parent", new MenuRequest(
                "m-child", "目录", null, 1, null, null, null, null, null, null, null), "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(menuMapper, never()).updateMenu(any());
    }

    @Test
    @DisplayName("deleteMenu_存在子菜单_拒绝并保留授权关系")
    void deleteMenu_withChildren_rejected() {
        when(menuMapper.selectMenuById("m-parent")).thenReturn(menu("m-parent", "0"));
        when(menuMapper.countChildren("m-parent")).thenReturn(2);

        assertThatThrownBy(() -> menuService.deleteMenu("m-parent"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(menuMapper, never()).deleteRoleMenusByMenuId(anyString());
        verify(menuMapper, never()).updateMenu(any());
    }

    @Test
    @DisplayName("deleteMenu_叶子菜单_清理角色授权后软删除")
    void deleteMenu_leaf_cleansRoleMenusThenSoftDeletes() {
        when(menuMapper.selectMenuById("m-leaf")).thenReturn(menu("m-leaf", "0"));
        when(menuMapper.countChildren("m-leaf")).thenReturn(0);
        when(menuMapper.updateMenu(any(Menu.class))).thenReturn(1);

        menuService.deleteMenu("m-leaf");

        verify(menuMapper).deleteRoleMenusByMenuId("m-leaf");
        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuMapper).updateMenu(captor.capture());
        assertThat(captor.getValue().getDelFlag()).isEqualTo("1");
    }

    @Test
    @DisplayName("selectMenusByUserId_子菜单被授权而父目录未授权_补齐祖先成完整树")
    void selectMenusByUserId_fillsMissingAncestors() {
        Menu directory = menu("m-dir", "0");
        Menu item = menu("m-item", "m-dir");
        when(menuMapper.selectMenusByUserId("u-1")).thenReturn(List.of(item));
        when(menuMapper.selectAllMenus()).thenReturn(List.of(directory, item));

        List<MenuVO> tree = menuService.selectMenusByUserId("u-1");

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).id()).isEqualTo("m-dir");
        assertThat(tree.get(0).children()).extracting(MenuVO::id).containsExactly("m-item");
    }

    @Test
    @DisplayName("selectMenusByUserId_授权已含全部祖先_不再回查全表")
    void selectMenusByUserId_completeChain_skipsFullScan() {
        Menu directory = menu("m-dir", "0");
        Menu item = menu("m-item", "m-dir");
        when(menuMapper.selectMenusByUserId("u-1")).thenReturn(List.of(directory, item));

        List<MenuVO> tree = menuService.selectMenusByUserId("u-1");

        assertThat(tree).extracting(MenuVO::id).containsExactly("m-dir");
        verify(menuMapper, never()).selectAllMenus();
    }

    /* ---------- 角色 ---------- */

    @Test
    @DisplayName("listRoles_一次取回全部角色-菜单关系_不逐角色查询")
    void listRoles_groupsMenuIdsInOneQuery() {
        Role r1 = role("r1", 10);
        Role r2 = role("r2", 20);
        when(menuMapper.selectAllRoles()).thenReturn(List.of(r1, r2));
        when(menuMapper.selectAllRoleMenus()).thenReturn(List.of(
                relation("rm-1", "r1", "m01"), relation("rm-2", "r1", "m02")));

        List<RoleVO> roles = menuService.listRoles();

        assertThat(roles).extracting(RoleVO::id).containsExactly("r1", "r2");
        assertThat(roles.get(0).menuIds()).containsExactlyInAnyOrder("m01", "m02");
        assertThat(roles.get(1).menuIds()).isEmpty();
        verify(menuMapper, never()).selectMenuIdsByRoleId(anyString());
    }

    @Test
    @DisplayName("deleteRole_角色不存在_404")
    void deleteRole_notFound_throwsNotFound() {
        when(menuMapper.selectRoleById("nope")).thenReturn(null);

        assertThatThrownBy(() -> menuService.deleteRole("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        verify(menuMapper, never()).updateRole(any());
    }

    @Test
    @DisplayName("deleteRole_超级管理员角色_拒绝删除")
    void deleteRole_superAdmin_rejected() {
        when(menuMapper.selectRoleById("r_super")).thenReturn(role("r_super", 0));

        assertThatThrownBy(() -> menuService.deleteRole("r_super"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);

        verify(menuMapper, never()).deleteRoleMenusByRoleId(anyString());
    }

    @Test
    @DisplayName("deleteRole_普通角色_清理角色-菜单与用户-角色关联后软删除")
    void deleteRole_cleansUpBothRelations() {
        when(menuMapper.selectRoleById("r_user")).thenReturn(role("r_user", 10));
        when(menuMapper.updateRole(any(Role.class))).thenReturn(1);

        menuService.deleteRole("r_user");

        verify(menuMapper).deleteRoleMenusByRoleId("r_user");
        verify(menuMapper).deleteUserRolesByRoleId("r_user");
        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(menuMapper).updateRole(captor.capture());
        assertThat(captor.getValue().getDelFlag()).isEqualTo("1");
    }

    @Test
    @DisplayName("createRole_创建比自己等级更高的角色_403且不写入")
    void createRole_morePowerfulThanOperator_rejected() {
        // 操作者自身等级 10（数字越大权限越低），想造一个 0 级角色就是一步登天
        when(menuMapper.selectRoleByCode("ROLE_NEW")).thenReturn(null);
        when(menuMapper.selectUserMinRoleLevel("op-1")).thenReturn(10);

        assertThatThrownBy(() -> menuService.createRole(new RoleRequest(
                "ROLE_NEW", "超级管理员", 0, null), "op-1", "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.FORBIDDEN);

        verify(menuMapper, never()).insertRole(any());
    }

    @Test
    @DisplayName("updateRole_把角色改成比自己等级更高_403")
    void updateRole_escalateLevel_rejected() {
        when(menuMapper.selectRoleById("r_user")).thenReturn(role("r_user", 10));
        when(menuMapper.selectUserMinRoleLevel("op-1")).thenReturn(10);

        assertThatThrownBy(() -> menuService.updateRole("r_user", new RoleRequest(
                "ROLE_USER", "普通用户", 0, null), "op-1", "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.FORBIDDEN);

        verify(menuMapper, never()).updateRole(any());
    }

    @Test
    @DisplayName("updateRole_记录操作者到update_by审计字段")
    void updateRole_recordsUpdateBy() {
        when(menuMapper.selectRoleById("r_user")).thenReturn(role("r_user", 10));
        when(menuMapper.selectUserMinRoleLevel("op-1")).thenReturn(10);
        when(menuMapper.updateRole(any(Role.class))).thenReturn(1);

        menuService.updateRole("r_user", new RoleRequest(
                "ROLE_USER", "普通用户改名", 10, "描述"), "op-1", "admin");

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(menuMapper).updateRole(captor.capture());
        assertThat(captor.getValue().getUpdateBy()).isEqualTo("admin");
        assertThat(captor.getValue().getRoleName()).isEqualTo("普通用户改名");
    }

    @Test
    @DisplayName("menuIdsOfRole_角色不存在_404")
    void menuIdsOfRole_notFound_throwsNotFound() {
        when(menuMapper.selectRoleById("nope")).thenReturn(null);

        assertThatThrownBy(() -> menuService.menuIdsOfRole("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    /* ---------- 用户-角色授权 ---------- */

    @Test
    @DisplayName("grantRolesToUser_用户不存在_404且不动任何数据")
    void grantRolesToUser_userNotFound_throwsNotFound() {
        when(userMapper.selectById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> menuService.grantRolesToUser("ghost", List.of("r_user"), "op-1", "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        verify(menuMapper, never()).deleteUserRolesByUserId(anyString());
        verify(menuMapper, never()).insertUserRoles(anyList());
    }

    @Test
    @DisplayName("grantRolesToUser_分配比自己等级更高的角色_403且整批拒绝")
    void grantRolesToUser_assignMorePowerfulRole_rejected() {
        when(userMapper.selectById("u-1")).thenReturn(new com.example.erupt.repository.entity.User());
        when(menuMapper.selectUserMinRoleLevel("u-1")).thenReturn(null); // 目标用户暂无角色
        when(menuMapper.selectAllRoles()).thenReturn(List.of(role("r_user", 10), role("r_super", 0)));
        when(menuMapper.selectUserMinRoleLevel("op-1")).thenReturn(10);

        assertThatThrownBy(() -> menuService.grantRolesToUser(
                "u-1", List.of("r_user", "r_super"), "op-1", "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.FORBIDDEN);

        // 整批拒绝：一条都不能写
        verify(menuMapper, never()).deleteUserRolesByUserId(anyString());
        verify(menuMapper, never()).insertUserRoles(anyList());
    }

    @Test
    @DisplayName("grantRolesToUser_去重并过滤无效角色ID_批量写入")
    void grantRolesToUser_filtersAndDeduplicates_thenBatchInserts() {
        when(userMapper.selectById("u-1")).thenReturn(new com.example.erupt.repository.entity.User());
        when(menuMapper.selectUserMinRoleLevel("u-1")).thenReturn(null); // 目标用户暂无角色
        when(menuMapper.selectAllRoles()).thenReturn(List.of(role("r_user", 10)));
        when(menuMapper.selectUserMinRoleLevel("op-1")).thenReturn(10);
        when(menuMapper.insertUserRoles(anyList())).thenReturn(1);

        int count = menuService.grantRolesToUser(
                "u-1", List.of("r_user", "r_user", "ghost", " "), "op-1", "admin");

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<List<com.example.erupt.repository.entity.UserRole>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(menuMapper).insertUserRoles(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getRoleId()).isEqualTo("r_user");
    }

    /* ---------- 测试数据 ---------- */

    private static Menu menu(String id, String parentId) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuName("菜单" + id);
        menu.setMenuType(2);
        menu.setSortNum(1);
        menu.setVisible(1);
        menu.setDelFlag("0");
        return menu;
    }

    private static Role role(String id, int level) {
        Role role = new Role();
        role.setId(id);
        role.setRoleCode("ROLE_" + id);
        role.setRoleName("角色" + id);
        role.setRoleLevel(level);
        role.setDelFlag("0");
        return role;
    }

    private static RoleMenu relation(String id, String roleId, String menuId) {
        RoleMenu relation = new RoleMenu();
        relation.setId(id);
        relation.setRoleId(roleId);
        relation.setMenuId(menuId);
        return relation;
    }
}
