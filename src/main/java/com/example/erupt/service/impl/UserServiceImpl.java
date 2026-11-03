package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.ChangePasswordRequest;
import com.example.erupt.model.dto.ResetPasswordRequest;
import com.example.erupt.model.converter.UserConverter;
import com.example.erupt.model.dto.UserCreateRequest;
import com.example.erupt.model.dto.UserQueryRequest;
import com.example.erupt.model.dto.UserUpdateRequest;
import com.example.erupt.model.vo.UserVO;
import com.example.erupt.repository.entity.User;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户服务实现：业务规则与事务边界都在这一层，Controller 与 数据层都不承担业务逻辑。
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private static final UserConverter CONVERTER = Mappers.getMapper(UserConverter.class);

    /** 分页偏移量上限，超过后直接拒绝，避免 LIMIT 的大 OFFSET 扫描丢弃大量行。 */
    private static final long MAX_OFFSET = 100_000L;

    /** 未删除标记，与库表默认值保持一致。 */
    private static final String DEL_FLAG_NORMAL = "0";

    private final UserMapper userMapper;

    /** 密码只存 BCrypt 哈希，不存明文。 */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> create(UserCreateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (userMapper.selectByUsername(request.username()) != null) {
            throw new BusinessException(ResultCode.CONFLICT, "账号已存在：" + request.username());
        }
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setAvatar(request.avatar());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        user.setStatus(UserCreateRequest.DEFAULT_STATUS);
        user.setRemark(request.remark());
        user.setDelFlag(DEL_FLAG_NORMAL);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        int affected = userMapper.insert(user);
        if (affected >= 1) {
            log.info("新增用户成功，id={}, username={}, affected={}", user.getId(), user.getUsername(), affected);
            return Result.success(null, "新增成功");
        }
        log.warn("新增用户失败，username={}, affected={}", user.getUsername(), affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "新增失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> update(String id, UserUpdateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        User existing = requireUser(id);
        if (request.nickname() != null) {
            existing.setNickname(request.nickname());
        }
        if (request.avatar() != null) {
            existing.setAvatar(request.avatar());
        }
        if (request.email() != null) {
            existing.setEmail(request.email());
        }
        if (request.phone() != null) {
            existing.setPhone(request.phone());
        }
        if (request.status() != null) {
            existing.setStatus(requireValidStatus(request.status()));
        }
        if (request.remark() != null) {
            existing.setRemark(request.remark());
        }
        existing.setUpdateTime(LocalDateTime.now());
        int affected = userMapper.updateById(existing);
        if (affected >= 1) {
            log.info("更新用户成功，id={}, affected={}", id, affected);
            return Result.success(null, "更新成功");
        }
        log.warn("更新用户失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "更新失败");
    }

    @Override
    @Transactional(readOnly = true)
    public UserVO getById(String id) {
        return toVO(requireUser(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<UserVO> page(UserQueryRequest query) {
        Objects.requireNonNull(query, "query 不能为空");
        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        String keyword = LikeEscape.escape(normalizeKeyword(query.keyword()));
        Integer status = requireValidStatusOrNull(query.status());
        long total = userMapper.countByCondition(keyword, status);
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0L, page, pageSize);
        }
        long offset = (long) (page - 1) * pageSize;
        requireOffsetWithinLimit(offset);
        List<UserVO> items = userMapper.selectByCondition(keyword, status, offset, pageSize)
                .stream()
                .map(this::toVO)
                .toList();
        return PageResult.of(items, total, page, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(String id) {
        requireUser(id);
        int affected = userMapper.softDeleteById(id, null, LocalDateTime.now());
        if (affected >= 1) {
            log.info("软删除用户成功，id={}, affected={}", id, affected);
            return Result.success(null, "删除成功");
        }
        log.warn("软删除用户失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "删除失败");
    }

    @Override
    @Transactional(readOnly = true)
    public UserVO current() {
        return getById(requireCurrentId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateSelf(UserUpdateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String id = requireCurrentId();
        User existing = requireUser(id);
        // 管理字段一律不写入：status 能让人解除自己的禁用状态，remark 是管理员备注。
        // 前端不传那只是一种约定，服务端显式忽略才算真的堵住。
        if (request.nickname() != null) {
            existing.setNickname(request.nickname());
        }
        if (request.avatar() != null) {
            existing.setAvatar(request.avatar());
        }
        if (request.email() != null) {
            existing.setEmail(request.email());
        }
        if (request.phone() != null) {
            existing.setPhone(request.phone());
        }
        existing.setUpdateTime(LocalDateTime.now());
        int affected = userMapper.updateById(existing);
        if (affected >= 1) {
            log.info("用户自助更新资料成功，id={}, affected={}", id, affected);
            return Result.success(null, "更新成功");
        }
        log.warn("用户自助更新资料失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "更新失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> changePassword(ChangePasswordRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String id = requireCurrentId();
        User user = requireUser(id);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "原密码不正确");
        }
        if (Objects.equals(request.oldPassword(), request.newPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码不能与原密码相同");
        }
        int affected = userMapper.updatePasswordById(
                id, passwordEncoder.encode(request.newPassword()), LocalDateTime.now());
        if (affected >= 1) {
            log.info("用户自助修改密码成功，id={}", id);
            return Result.success(null, "密码修改成功");
        }
        log.warn("用户自助修改密码失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "密码修改失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> resetPassword(String id, ResetPasswordRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        requireUser(id);
        int affected = userMapper.updatePasswordById(
                id, passwordEncoder.encode(request.newPassword()), LocalDateTime.now());
        if (affected >= 1) {
            // 记录操作人：重置他人密码是高敏操作，事后必须能追溯到具体账号
            log.info("管理员重置用户密码成功，userId={}, operator={}", id, AuthContext.currentUsername());
            return Result.success(null, "密码重置成功");
        }
        log.warn("管理员重置用户密码失败，userId={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "密码重置失败");
    }

    /**
     * 当前登录用户的 id，取自 Token 解析结果而非请求参数。
     * 这是自助接口越权防护的根：任何 "传个 id 就能操作别人" 的可能都被排除在外。
     */
    private String requireCurrentId() {
        String id = AuthContext.currentUserId();
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "无法识别当前登录用户");
        }
        return id;
    }

    private User requireUser(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户 id 不能为空");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在：" + id);
        }
        return user;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }

    /** 状态只允许 0/1，非法取值直接拒绝，避免脏数据入库。 */
    private Integer requireValidStatusOrNull(Integer status) {
        if (status == null) {
            return null;
        }
        return requireValidStatus(status);
    }

    private Integer requireValidStatus(Integer status) {
        if (status < 0 || status > 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态取值只能为 0（禁用）或 1（启用）");
        }
        return status;
    }

    /** 深分页保护：LIMIT 的大 OFFSET 会扫描并丢弃大量行，直接拒绝超限请求。 */
    private void requireOffsetWithinLimit(long offset) {
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量过大，请缩小页码或使用更精确的查询条件");
        }
    }

    /**
     * 实体 → VO：具体映射由 MapStruct 在编译期生成（见 {@code UserConverter}），
     * 实体新增字段时会自动纳入映射，不会出现漏字段。
     */
    private UserVO toVO(User user) {
        return CONVERTER.toVO(user);
    }
}
