package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.UserCreateRequest;
import com.example.erupt.model.dto.UserQueryRequest;
import com.example.erupt.model.dto.UserUpdateRequest;
import com.example.erupt.model.vo.UserVO;
import com.example.erupt.repository.entity.User;
import com.example.erupt.repository.mapper.UserMapper;
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
    @DisplayName("update_partialFields_keepsUntouchedValues")
    void update_partialFields_keepsUntouchedValues() {
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
        assertThat(captor.getValue().getNickname()).isEqualTo("新昵称");
        // 未传的字段保持原值，更新时间由应用层填充
        assertThat(captor.getValue().getEmail()).isEqualTo("old@test.com");
        assertThat(captor.getValue().getUpdateTime()).isNotNull();
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
    @DisplayName("page_offsetExceedsLimit_throwsBadRequest")
    void page_offsetExceedsLimit_throwsBadRequest() {
        when(userMapper.countByCondition(any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> userService.page(new UserQueryRequest(null, null, 1002, 100)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).resultCode())
                .isEqualTo(ResultCode.BAD_REQUEST);
        verify(userMapper, never()).selectByCondition(anyString(), any(), anyLong(), eq(100));
    }

    @Test
    @DisplayName("create_fillsIdPasswordAndTimestamps")
    void create_fillsIdPasswordAndTimestamps() {
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

    private User mockUser(String id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(1);
        user.setDelFlag("0");
        return user;
    }
}
