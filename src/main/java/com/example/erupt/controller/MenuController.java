package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.MenuRequest;
import com.example.erupt.model.dto.RoleRequest;
import com.example.erupt.model.vo.MenuVO;
import com.example.erupt.model.vo.RoleVO;
import com.example.erupt.service.MenuService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单与角色授权接口（视图层）。
 *
 * <p>菜单树的新建与层级调整都收敛在 {@code parentId} 一个参数上：
 * 顶级传 {@code "0"}，建在谁下面就传谁的ID。树的形状由父子链决定，
 * 不需要"移动菜单""调整层级"这类额外接口。</p>
 */
@RestController
@RequestMapping("/api/menu")
@Validated
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /** 全部菜单（树形），管理端用 */
    @GetMapping("/tree")
    public ResponseEntity<Result<List<MenuVO>>> tree() {
        List<MenuVO> tree = menuService.menuTree();
        return ResponseEntity.ok(Result.success(tree));
    }

    /**
     * 当前登录用户被授权的菜单（树形）。
     * 前端侧边栏/顶栏据此渲染——用户看不到任何一个未被授权的菜单。
     */
    @GetMapping("/mine")
    public ResponseEntity<Result<List<MenuVO>>> mine() {
        List<MenuVO> menus = menuService.selectMenusByUserId(AuthContext.currentUserId());
        return ResponseEntity.ok(Result.success(menus));
    }

    /** 新建菜单。parentId 传 "0" 或不传表示顶级 */
    @PostMapping
    public ResponseEntity<Result<MenuVO>> create(@Valid @RequestBody MenuRequest request) {
        MenuVO created = menuService.createMenu(request, AuthContext.currentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<MenuVO>> update(@PathVariable String id,
                                                 @Valid @RequestBody MenuRequest request) {
        MenuVO updated = menuService.updateMenu(id, request, AuthContext.currentUsername());
        return ResponseEntity.ok(Result.success(updated));
    }

    /** 删除菜单。存在子菜单时会拒绝，需先删子节点 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@PathVariable String id) {
        menuService.deleteMenu(id);
        return ResponseEntity.ok(Result.success(null, "删除成功"));
    }

    /* ---------- 角色 ---------- */

    @GetMapping("/roles")
    public ResponseEntity<Result<List<RoleVO>>> roles() {
        return ResponseEntity.ok(Result.success(menuService.listRoles()));
    }

    @PostMapping("/roles")
    public ResponseEntity<Result<RoleVO>> createRole(@Valid @RequestBody RoleRequest request) {
        RoleVO created = menuService.createRole(request, AuthContext.currentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(created));
    }

    @PutMapping("/roles/{id}")
    public ResponseEntity<Result<RoleVO>> updateRole(@PathVariable String id,
                                                     @Valid @RequestBody RoleRequest request) {
        return ResponseEntity.ok(Result.success(menuService.updateRole(id, request)));
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Result<Void>> deleteRole(@PathVariable String id) {
        menuService.deleteRole(id);
        return ResponseEntity.ok(Result.success(null, "删除成功"));
    }

    /** 查询角色的菜单授权 */
    @GetMapping("/roles/{roleId}/menus")
    public ResponseEntity<Result<List<String>>> roleMenus(@PathVariable String roleId) {
        return ResponseEntity.ok(Result.success(menuService.menuIdsOfRole(roleId)));
    }

    /** 给角色授权菜单（全量覆盖） */
    @PostMapping("/roles/{roleId}/menus")
    public ResponseEntity<Result<Integer>> grantRoleMenus(@PathVariable String roleId,
                                                          @RequestBody List<String> menuIds) {
        int count = menuService.grantMenusToRole(roleId, menuIds, AuthContext.currentUsername());
        return ResponseEntity.ok(Result.success(count));
    }
}
