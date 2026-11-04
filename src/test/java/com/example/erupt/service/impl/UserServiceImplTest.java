package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.UserCreateRequest;
import com.example.erupt.model.dto.UserQueryRequest;
import com.example.erupt.model.dto.UserSelfUpdateRequest;
import com.example.erupt.model.dto.UserUpdateRequest;
import com.example.erupt.model.vo.UserVO;
import com.example.erupt.repository.entity.User;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private com.example.erupt.service.MenuService menuService;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    @DisplayName("create_usernameDuplicated_throwsConflictException")
    void create_usernameDuplicated_throwsConflictException() {
        when(userMapper.selectByUsername("zhangsan")).thenReturn(mockUser("u-1", "zhangsan"));

        assertThatThrownBy(() -> userService.create(
                new UserCreateRequest("zhangsan", "123456", "张三", null, "z@test.com", "13800138000", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.CONFLICT);

        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("create_validRequest_returnsSuccessWithHashedPasswordAndStringId")
    void create_validRequest_returnsSuccessWithHashedPasswordAndStringId() {
        stubEncoder("lisi", "123456");
        when(userMapper.selectByUsername("lisi")).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenReturn(1);

        Result<Void> result = userService.create(
                new UserCreateRequest("lisi", "123456", "李四", "/a.jpg", "lisi@test.com", "13800138001", "备注"));

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("新增成功");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getId()).hasSize(32);
        assertThat(saved.getNickname()).isEqualTo("李四");
        assertThat(saved.getAvatar()).isEqualTo("/a.jpg");
        assertThat(saved.getStatus()).isEqualTo(UserCreateRequest.DEFAULT_STATUS);
        assertThat(saved.getDelFlag()).isEqualTo("0");
        assertThat(saved.getPassword()).startsWith("$2").isNotEqualTo("123456");
    }

    @Test
    @DisplayName("create_affectedRowsZero_returnsFailure")
    void create_affectedRowsZero_returnsFailure() {
        stubEncoder("lisi", "123456");
        when(userMapper.selectByUsername("lisi")).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenReturn(0);

        Result<Void> result = userService.create(
                new UserCreateRequest("lisi", "123456", "李四", null, "lisi@test.com", "13800138001", null));

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("新增失败");
    }

    @Test
    @DisplayName("getById_userNotExists_throwsNotFoundException")
    void getById_userNotExists_throwsNotFoundException() {
        when(userMapper.selectById("u-404")).thenReturn(null);

        assertThatThrownBy(() -> userService.getById("u-404"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("update_partialFields_patchCarriesOnlyRequestedFields")
    void update_partialFields_patchCarriesOnlyRequestedFields() {
        User existing = mockUser("u-1", "zhangsan");
        existing.setEmail("old@test.com");
        when(userMapper.selectById("u-1")).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        Result<Void> result = userService.update("u-1",
                new UserUpdateRequest("新昵称", null, null, null, null, null));

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("更新成功");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User patch = captor.getValue();
        assertThat(patch.getId()).isEqualTo("u-1");
        assertThat(patch.getNickname()).isEqualTo("新昵称");
        // 未传的字段在补丁里为 null：updateById 的 <if> 跳过它们，库里的旧值自然保留，
        // 而不是由应用层把「读取时刻的旧值」再写一遍
        assertThat(patch.getEmail()).isNull();
        assertThat(patch.getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("update_withoutStatus_neverWritesBackStatusOrLoginTime")
    void update_withoutStatus_neverWritesBackStatusOrLoginTime() {
        // 模拟并发：读取时 status=1，若这期间另一位管理员把账号禁用了，
        // 本次只改昵称的更新绝不能把 status=1 写回去
        User existing = mockUser("u-1", "zhangsan");
        existing.setLastLoginTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        existing.setRemark("旧备注");
        when(userMapper.selectById("u-1")).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        userService.update("u-1", new UserUpdateRequest("新昵称", null, null, null, null, null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User patch = captor.getValue();
        assertThat(patch).isNotSameAs(existing);
        assertThat(patch.getStatus()).isNull();
        assertThat(patch.getLastLoginTime()).isNull();
        assertThat(patch.getRemark()).isNull();
        // 不改状态就不该触发等级校验
        verify(menuService, never()).userLevel(any());
    }

    @Test
    @DisplayName("update_statusChange_operatorBelowTarget_throwsForbidden")
    void update_statusChange_operatorBelowTarget_throwsForbidden() {
        when(userMapper.selectById("u-admin")).thenReturn(mockUser("u-admin", "admin"));
        // 数字越小等级越高：操作者 10（普通管理员）不能禁用等级 0 的超管
        when(menuService.userLevel("u-op")).thenReturn(10);
        when(menuService.userLevel("u-admin")).thenReturn(0);

        AuthContext.set(new TokenService.TokenIdentity("u-op", "operator", Long.MAX_VALUE));
        try {
            assertThatThrownBy(() -> userService.update("u-admin",
                    new UserUpdateRequest(null, null, null, null, 0, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).resultCode())
                    .isEqualTo(ResultCode.FORBIDDEN);
        } finally {
            AuthContext.clear();
        }
        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("update_statusChange_allowed_statusWrittenToPatch")
    void update_statusChange_allowed_statusWrittenToPatch() {
        when(userMapper.selectById("u-1")).thenReturn(mockUser("u-1", "zhangsan"));
        when(menuService.userLevel("u-admin")).thenReturn(0);
        when(menuService.userLevel("u-1")).thenReturn(10);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        AuthContext.set(new TokenService.TokenIdentity("u-admin", "admin", Long.MAX_VALUE));
        try {
            userService.update("u-1", new UserUpdateRequest(null, null, null, null, 0, null));
        } finally {
            AuthContext.clear();
        }

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isZero();
        assertThat(captor.getValue().getUpdateBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("update_affectedRowsZero_returnsFailure")
    void update_affectedRowsZero_returnsFailure() {
        when(userMapper.selectById("u-1")).thenReturn(mockUser("u-1", "zhangsan"));
        when(userMapper.updateById(any(User.class))).thenReturn(0);

        Result<Void> result = userService.update("u-1",
                new UserUpdateRequest("新昵称", null, null, null, null, null));

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("更新失败");
    }

    @Test
    @DisplayName("delete_notFound_throwsNotFoundException")
    void delete_notFound_throwsNotFoundException() {
        when(userMapper.selectById("u-9")).thenReturn(null);

        assertThatThrownBy(() -> userService.delete("u-9"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(userMapper, never()).softDeleteById(anyString(), any(), any());
    }

    @Test
    @DisplayName("delete_existing_softDeletesInsteadOfPhysicalDelete")
    void delete_existing_softDeletesInsteadOfPhysicalDelete() {
        when(userMapper.selectById("u-1")).thenReturn(mockUser("u-1", "zhangsan"));
        when(userMapper.softDeleteById(eq("u-1"), any(), any())).thenReturn(1);

        Result<Void> result = userService.delete("u-1");

        assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(result.message()).isEqualTo("删除成功");
        verify(userMapper).softDeleteById(eq("u-1"), isNull(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("delete_affectedRowsZero_returnsFailure")
    void delete_affectedRowsZero_returnsFailure() {
        when(userMapper.selectById("u-1")).thenReturn(mockUser("u-1", "zhangsan"));
        when(userMapper.softDeleteById(eq("u-1"), any(), any())).thenReturn(0);

        Result<Void> result = userService.delete("u-1");

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(result.message()).isEqualTo("删除失败");
    }

    @Test
    @DisplayName("page_emptyResult_returnsEmptyItems")
    void page_emptyResult_returnsEmptyItems() {
        when(userMapper.countByCondition(any(), eq(null))).thenReturn(0L);

        PageResult<UserVO> page = userService.page(new UserQueryRequest("kw", null, 1, 10));

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
        verify(userMapper, never()).selectByCondition(anyString(), any(), anyLong(), eq(10));
    }

    @Test
    @DisplayName("page_hasResult_returnsItems")
    void page_hasResult_returnsItems() {
        when(userMapper.countByCondition("kw", 1)).thenReturn(1L);
        when(userMapper.selectByCondition("kw", 1, 0L, 10)).thenReturn(List.of(mockUser("u-1", "zhangsan")));

        PageResult<UserVO> page = userService.page(new UserQueryRequest("kw", 1, 1, 10));

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
        assertThat(page.pageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("update_invalidStatus_throwsBadRequest")
    void update_invalidStatus_throwsBadRequest() {
        when(userMapper.selectById("u-1")).thenReturn(mockUser("u-1", "zhangsan"));

        assertThatThrownBy(() -> userService.update("u-1",
                new UserUpdateRequest(null, null, null, null, 9, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(userMapper, never()).updateById(any());
        // 取值校验先于等级校验：非法状态不应触发角色等级查询
        verify(menuService, never()).userLevel(any());
    }

    @Test
    @DisplayName("page_invalidStatus_throwsBadRequest")
    void page_invalidStatus_throwsBadRequest() {
        assertThatThrownBy(() -> userService.page(new UserQueryRequest(null, 9, 1, 10)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("page_offsetExceedsLimit_throwsBadRequestBeforeCountQuery")
    void page_offsetExceedsLimit_throwsBadRequestBeforeCountQuery() {
        // 偏移量校验前移到 count 之前：注定被拒的请求不应先白跑一次统计。
        // 因此这里不能再为 countByCondition 打桩——打了桩反而说明它被调用了。
        assertThatThrownBy(() -> userService.page(new UserQueryRequest(null, null, 1002, 100)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(userMapper, never()).countByCondition(any(), any());
        verify(userMapper, never()).selectByCondition(anyString(), any(), anyLong(), eq(100));
    }

    @Test
    @DisplayName("create_fillsIdPasswordAndTimestamps")
    void create_fillsIdPasswordAndTimestamps() {
        stubEncoder("zhangsan", "123456");
        when(userMapper.selectByUsername("zhangsan")).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenReturn(1);

        userService.create(new UserCreateRequest("zhangsan", "123456", "张三", null, "z@test.com", "13800138000", null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).hasSize(32);
        assertThat(captor.getValue().getPassword()).isNotEqualTo("123456");
        assertThat(captor.getValue().getCreateTime()).isNotNull();
        assertThat(captor.getValue().getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("updateSelf_writesPatchOnly_neverCarriesStatusOrRemark")
    void updateSelf_writesPatchOnly_neverCarriesStatusOrRemark() {
        // 库里这一行 status=1、带管理员备注；写库时绝不能把这两个值原样带回去，
        // 否则读写之间管理员的「禁用」会被这次自助更新覆盖掉
        User existing = mockUser("u-1", "zhangsan");
        existing.setRemark("管理员备注");
        existing.setLastLoginTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(userMapper.selectById("u-1")).thenReturn(existing);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        AuthContext.set(new TokenService.TokenIdentity("u-1", "zhangsan", Long.MAX_VALUE));
        try {
            Result<Void> result = userService.updateSelf(
                    new UserSelfUpdateRequest("新昵称", null, "new@test.com", null));
            assertThat(result.code()).isEqualTo(ResultCode.SUCCESS.code());
        } finally {
            AuthContext.clear();
        }

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User patch = captor.getValue();
        assertThat(patch).isNotSameAs(existing);
        assertThat(patch.getId()).isEqualTo("u-1");
        assertThat(patch.getNickname()).isEqualTo("新昵称");
        assertThat(patch.getEmail()).isEqualTo("new@test.com");
        assertThat(patch.getUpdateBy()).isEqualTo("zhangsan");
        assertThat(patch.getUpdateTime()).isNotNull();
        // 关键断言：管理字段与登录时间在补丁里一律为 null，updateById 的 <if> 不会把它们写进 SET
        assertThat(patch.getStatus()).isNull();
        assertThat(patch.getRemark()).isNull();
        assertThat(patch.getLastLoginTime()).isNull();
    }

    @Test
    @DisplayName("updateSelf_notLoggedIn_throwsUnauthorized")
    void updateSelf_notLoggedIn_throwsUnauthorized() {
        AuthContext.clear();

        assertThatThrownBy(() -> userService.updateSelf(new UserSelfUpdateRequest("x", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.UNAUTHORIZED);
        verify(userMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("updateSelf_userDeleted_throwsNotFound")
    void updateSelf_userDeleted_throwsNotFound() {
        when(userMapper.selectById("u-1")).thenReturn(null);

        AuthContext.set(new TokenService.TokenIdentity("u-1", "zhangsan", Long.MAX_VALUE));
        try {
            assertThatThrownBy(() -> userService.updateSelf(new UserSelfUpdateRequest("x", null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).resultCode())
                    .isEqualTo(ResultCode.NOT_FOUND);
        } finally {
            AuthContext.clear();
        }
        verify(userMapper, never()).updateById(any());
    }

    private void stubEncoder(String username, String password) {
        when(passwordEncoder.encode(password)).thenReturn("$2a$10$stub-" + username);
    }

    private User mockUser(String id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(1);
        user.setDelFlag("0");
        return user;
    }
}
