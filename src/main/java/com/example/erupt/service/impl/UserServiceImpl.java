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
import com.example.erupt.model.dto.UserSelfUpdateRequest;
import com.example.erupt.model.dto.UserUpdateRequest;
import com.example.erupt.model.vo.UserVO;
import com.example.erupt.repository.entity.User;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.MenuService;
import com.example.erupt.service.TokenService;
import com.example.erupt.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
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
    private final MenuService menuService;

    /** 改密后需要吊销该用户的全部登录会话，这里直接依赖 TokenService。 */
    private final TokenService tokenService;

    /** 密码只存 BCrypt 哈希，不存明文；复用 SecurityConfig 暴露的 PasswordEncoder bean，避免多实例。 */
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, MenuService menuService,
                           TokenService tokenService, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.menuService = menuService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
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
        // 与其他模块（附件、字典、菜单）保持一致：新增时记录操作人账号。
        // 之前这里没写，导致新建的账号在库里 create_by 恒为 NULL，事后查不到是谁开的号。
        user.setCreateBy(AuthContext.currentUsername());
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
        // 只用来确认目标账号存在（已删除则 404），不拿它当写库的载体
        requireUser(id);

        // 写库只用「补丁对象」：id + 本次请求里真正传了的字段。
        // 以前是把查出来的整行改几个字段再整行交给 updateById，而它会把所有非空字段写回——
        // 两个管理员前后脚操作时，后写的一方会把对方刚改的值覆盖回自己读到的旧值：
        //   管理员 A 读取（status=1）→ 管理员 B 禁用（status=0）→ A 只改了昵称，却把 status 写回 1
        // 连 last_login_time 也会被写回读取时刻的旧值。补丁里只放请求字段，未传的保持 null，
        // updateById 的 <if test="x != null"> 自然不会碰它们。
        User patch = new User();
        patch.setId(id);
        patch.setNickname(request.nickname());
        patch.setAvatar(request.avatar());
        patch.setEmail(request.email());
        patch.setPhone(request.phone());
        patch.setRemark(request.remark());
        if (request.status() != null) {
            // 先校验取值再做等级校验：非法取值没必要先查两次角色等级
            Integer status = requireValidStatus(request.status());
            // 只对"改状态"做等级校验：禁用/启用是管理动作，
            // 低级管理员禁用超管与"重置超管密码"是同一类越权
            requireNotAboveTarget(id, "变更该用户的状态");
            patch.setStatus(status);
        }
        patch.setUpdateTime(LocalDateTime.now());
        // 更新人必须落库：只有 update_time 的话，「谁改的」无从追溯。
        // 注意 update_by 存的是账号而非 id（库表注释如此约定），与 create_by 保持一致。
        patch.setUpdateBy(AuthContext.currentUsername());
        int affected = userMapper.updateById(patch);
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
        // 偏移量必须在 count 之前拦下：count 本身要扫一遍命中条件的行，
        // 先统计再拒绝等于让一个注定失败的请求白付一次全表代价。
        long offset = (long) (page - 1) * pageSize;
        requireOffsetWithinLimit(offset);

        long total = userMapper.countByCondition(keyword, status);
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0L, page, pageSize);
        }
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
        // 二次鉴权：不允许低级管理员删除高等级用户（如超管），
        // 与 resetPassword 同一防线——能改密码却删不掉账号，防线就算白设
        requireNotAboveTarget(id, "删除该用户");
        // 软删除必须留下操作人：删除他人是高敏操作，事后要能追溯到具体账号
        int affected = userMapper.softDeleteById(id, AuthContext.currentUsername(), LocalDateTime.now());
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
    public Result<Void> updateSelf(UserSelfUpdateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String id = requireCurrentId();
        // 只用来确认账号仍然存在（已删除则 404），不拿它当写库的载体
        requireUser(id);

        // 写库只用「补丁对象」：仅含 id + 本次允许改的字段。
        // 不能把查出来的整行实体交给 updateById——它会把所有非空字段原样写回，
        // 其中就有读取时刻的 status。若在读与写之间管理员禁用了该账号，
        // 这次自助更新会把 status 写回 1，等于用户自己撤销了禁用（丢失更新）。
        // 入参类型已不含 status / remark，补丁对象再保证写库时也碰不到它们，两道防线缺一不可。
        User patch = new User();
        patch.setId(id);
        patch.setNickname(request.nickname());
        patch.setAvatar(request.avatar());
        patch.setEmail(request.email());
        patch.setPhone(request.phone());
        patch.setUpdateTime(LocalDateTime.now());
        patch.setUpdateBy(AuthContext.currentUsername());
        int affected = userMapper.updateById(patch);
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
            // 改密必须吊销该用户的全部旧会话：否则「改了密码」只对下次登录取效，
            // 已经登录的其它设备（包括被盗 Token 的攻击者）还能继续用到 Token 自然过期。
            // 当前这次请求的 Token 也在其中——前端收到成功后应重新登录，这是预期行为。
            tokenService.revokeAllForUser(id);
            log.info("用户自助修改密码成功，id={}", id);
            return Result.success(null, "密码修改成功，请重新登录");
        }
        log.warn("用户自助修改密码失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "密码修改失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> resetPassword(String id, ResetPasswordRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        requireUser(id);
        // 二次鉴权：操作者等级必须不低于目标用户（数字越小越高），防止低级管理员重置高等级用户密码
        requireNotAboveTarget(id, "重置该用户的密码");
        int affected = userMapper.updatePasswordById(
                id, passwordEncoder.encode(request.newPassword()), LocalDateTime.now());
        if (affected >= 1) {
            // 与自助改密同理：管理员重置密码之后，目标用户手里所有旧 Token 必须立即失效。
            // 少了这一步，"密码已重置"只是让下次登录取新密码，被盗会话仍可用到自然过期。
            tokenService.revokeAllForUser(id);
            // 记录操作人：重置他人密码是高敏操作，事后必须能追溯到具体账号
            log.info("管理员重置用户密码成功，userId={}, operator={}", id, AuthContext.currentUsername());
            return Result.success(null, "密码重置成功");
        }
        log.warn("管理员重置用户密码失败，userId={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "密码重置失败");
    }

    /**
     * 二次鉴权：操作者角色等级必须不低于目标用户（数字越小越高，0=超管）。
     *
     * <p>等级比对原本只挂在 resetPassword 上，删除 / 禁用用户完全裸奔——
     * 低级管理员可以禁用甚至软删超管，超管被删后没人能恢复系统权限集合。
     * 现在删除、状态变更、重置密码三类高敏操作统一走这里。</p>
     */
    private void requireNotAboveTarget(String targetUserId, String action) {
        int operatorLevel = orMaxLevel(menuService.userLevel(AuthContext.currentUserId()));
        int targetLevel = orMaxLevel(menuService.userLevel(targetUserId));
        if (operatorLevel > targetLevel) {
            log.warn("无权{}，operator={}, targetId={}",
                    action, AuthContext.currentUsername(), targetUserId);
            throw new BusinessException(ResultCode.FORBIDDEN, "无权" + action);
        }
    }

    /** userLevel 返回 null（无任何角色或 Mapper 未命中）按最低权限处理：只能动"无角色"的用户 */
    private static int orMaxLevel(Integer level) {
        return level == null ? Integer.MAX_VALUE : level;
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
