package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.ChangePasswordRequest;
import com.example.erupt.model.dto.ResetPasswordRequest;
import com.example.erupt.model.dto.UserCreateRequest;
import com.example.erupt.model.dto.UserQueryRequest;
import com.example.erupt.model.dto.UserUpdateRequest;
import com.example.erupt.model.vo.UserVO;

/**
 * 用户服务（服务层）：用例编排与事务边界，依赖数据层的 {@code UserMapper}。
 */
public interface UserService {

    /**
     * 新增用户，账号重复时抛 {@link com.example.erupt.common.BusinessException}；
     * 密码使用 BCrypt 哈希后入库；根据插入 SQL 影响行数返回成功或失败标识，不回传实体。
     */
    Result<Void> create(UserCreateRequest request);

    /**
     * 按主键选择性更新用户。记录不存在时抛 {@link com.example.erupt.common.BusinessException}；
     * 根据更新 SQL 影响行数返回成功或失败标识，不回传实体。
     */
    Result<Void> update(String id, UserUpdateRequest request);

    /**
     * 查询用户详情，不存在时抛 {@link com.example.erupt.common.BusinessException}。
     */
    UserVO getById(String id);

    /**
     * 分页查询用户列表。
     */
    PageResult<UserVO> page(UserQueryRequest query);

    /**
     * 软删除用户（置 {@code del_flag='1'}，不物理删除）。记录不存在时抛
     * {@link com.example.erupt.common.BusinessException}；根据删除 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> delete(String id);

    /**
     * 当前登录用户的资料。
     *
     * <p>用户身份取自 Token 解析结果，<b>不接受任何入参</b>——
     * 这样就不存在"把 id 改成别人的"这种越权查看的可能。</p>
     */
    UserVO current();

    /**
     * 更新当前登录用户的资料。
     *
     * <p>只允许改动昵称 / 头像 / 邮箱 / 手机号。{@code status} 与 {@code remark} 属管理字段，
     * 即使请求里携带也会被忽略——否则被管理员禁用的用户能把自己改回启用状态。</p>
     */
    Result<Void> updateSelf(UserUpdateRequest request);

    /** 自助修改密码，必须校验原密码 */
    Result<Void> changePassword(ChangePasswordRequest request);

    /** 管理员重置指定用户的密码，不校验原密码 */
    Result<Void> resetPassword(String id, ResetPasswordRequest request);
}
