package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.ChangePasswordRequest;
import com.example.erupt.model.dto.ResetPasswordRequest;
import com.example.erupt.model.dto.UserCreateRequest;
import com.example.erupt.model.dto.UserQueryRequest;
import com.example.erupt.model.dto.UserSelfUpdateRequest;
import com.example.erupt.model.dto.UserUpdateRequest;
import com.example.erupt.model.vo.RoleVO;
import com.example.erupt.model.vo.UserVO;
import com.example.erupt.service.MenuService;
import com.example.erupt.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户接口（视图层）：仅负责参数绑定、校验与响应封装，业务逻辑下沉到 {@link UserService}。
 */
@RestController
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService userService;
    /**
     * 用户角色的读写放在本控制器而非 MenuController，原因见 {@link #grantUserRoles} 的说明：
     * 路径决定了它被拦截器归入哪个权限模块。
     */
    private final MenuService menuService;

    public UserController(UserService userService, MenuService menuService) {
        this.userService = userService;
        this.menuService = menuService;
    }

    @PostMapping
    public ResponseEntity<Result<Void>> create(@Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<Void>> update(@PathVariable String id,
                                               @Valid @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.update(id, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<UserVO>> detail(@PathVariable String id) {
        return ResponseEntity.ok(Result.success(userService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<UserVO>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @Min(value = 0, message = "状态取值只能为 0（禁用）或 1（启用）")
            @Max(value = 1, message = "状态取值只能为 0（禁用）或 1（启用）")
            Integer status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        UserQueryRequest query = new UserQueryRequest(keyword, status, page, pageSize);
        return ResponseEntity.ok(Result.success(userService.page(query)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Void>> delete(@PathVariable String id) {
        return ResponseEntity.ok(userService.delete(id));
    }

    /* ---------- 自助：个人信息（任何登录用户可用，不参与菜单鉴权） ---------- */

    /**
     * 当前登录用户的个人资料。
     *
     * <p>身份取自 Token，不接受任何入参，因此不存在越权查看他人资料的可能。</p>
     */
    @GetMapping("/me")
    public ResponseEntity<Result<UserVO>> me() {
        return ResponseEntity.ok(Result.success(userService.current()));
    }

    /**
     * 修改自己的资料。
     *
     * <p>入参是 {@link UserSelfUpdateRequest} 而不是管理端的 {@link UserUpdateRequest}：
     * 自助接口在类型层面就拿不到 status / remark。请求里即便带了这两个字段也会被丢弃。</p>
     */
    @PutMapping("/me")
    public ResponseEntity<Result<Void>> updateMe(@Valid @RequestBody UserSelfUpdateRequest request) {
        return ResponseEntity.ok(userService.updateSelf(request));
    }

    /** 修改自己的密码，需校验原密码 */
    @PutMapping("/me/password")
    public ResponseEntity<Result<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(userService.changePassword(request));
    }

    /* ---------- 管理员操作 ---------- */

    /** 重置指定用户的密码，不校验原密码 */
    @PutMapping("/{id}/password")
    public ResponseEntity<Result<Void>> resetPassword(@PathVariable String id,
                                                      @Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(userService.resetPassword(id, request));
    }

    /* ---------- 用户-角色 ---------- */

    /**
     * 查询某用户当前拥有的角色。
     *
     * <p>这两个端点原先挂在 {@code /api/menu/users/{userId}/roles} 下，会被
     * {@link com.example.erupt.config.MenuAccessInterceptor} 按 URI 第二段归入 {@code menu} 模块鉴权，
     * 于是"有权管理用户却无权给它分配角色"——权限口径错位。
     * 移到本控制器后归入 {@code users} 模块，与"用户管理"菜单的权限保持一致。</p>
     */
    @GetMapping("/{id}/roles")
    public ResponseEntity<Result<List<RoleVO>>> userRoles(@PathVariable String id) {
        return ResponseEntity.ok(Result.success(menuService.rolesOfUser(id)));
    }

    /**
     * 给用户分配角色（全量覆盖）。
     *
     * <p>采用全量覆盖而非增量增删：页面提交的是"最终应该拥有哪些角色"，
     * 覆盖式写入天然幂等，重复提交不会产生副作用。</p>
     */
    @PostMapping("/{id}/roles")
    public ResponseEntity<Result<Integer>> grantUserRoles(@PathVariable String id,
                                                          @RequestBody List<String> roleIds) {
        int count = menuService.grantRolesToUser(id, roleIds, AuthContext.currentUsername());
        return ResponseEntity.ok(Result.success(count));
    }
}
