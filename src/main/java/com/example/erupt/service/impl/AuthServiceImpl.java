package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.ResultCode;
import com.example.erupt.config.AuthProperties;
import com.example.erupt.model.converter.UserConverter;
import com.example.erupt.model.dto.LoginRequest;
import com.example.erupt.model.vo.CaptchaVO;
import com.example.erupt.model.vo.LoginFailHintVO;
import com.example.erupt.model.vo.LoginVO;
import com.example.erupt.model.vo.UserVO;
import com.example.erupt.repository.entity.User;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.AuthService;
import com.example.erupt.service.CaptchaService;
import com.example.erupt.service.LoginAttemptService;
import com.example.erupt.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 认证服务实现。
 *
 * <p><b>核心安全约束</b>：</p>
 * <ol>
 *   <li>账号不存在时，必须照样跑一次 BCrypt 校验，否则会通过响应耗时差异
 *       暴露"哪些账号存在"——这就是<a href="https://en.wikipedia.org/wiki/User_enumeration">用户枚举攻击</a>。
 *       用一个不会匹配的 dummy 哈希即可把响应时间对齐。</li>
 *   <li>对用户返回的错误信息要保持模糊——
 *       永远不要告诉前端"账号不存在"或"密码错误"，统一一句"账号或密码不正确"。</li>
 *   <li>校验顺序：先验锁、再验证码、再账号、再密码。
 *       这样"被锁定的账号"不会因为密码错误额外加分，"未到验证码门槛的请求"也不会被无意义打码。</li>
 * </ol>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    /**
     * 一个永远不会匹配任何密码的 BCrypt 哈希，编译期固定。
     * 用途：当账号不存在时，调用一次 matches(...) 来"耗时间"，
     * 让正常登录（账号存在但密码错）和异常登录（账号不存在）的耗时基本一致。
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$CwTycUXWue0Thq9StjUM0uJ8DHyqJ8p9VHjQ7V5Q5Q5Q5Q5Q5Q5O";

    private static final UserConverter CONVERTER = Mappers.getMapper(UserConverter.class);

    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final CaptchaService captchaService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public AuthServiceImpl(UserMapper userMapper,
                           TokenService tokenService,
                           CaptchaService captchaService,
                           LoginAttemptService loginAttemptService,
                           PasswordEncoder passwordEncoder,
                           AuthProperties properties) {
        this.userMapper = userMapper;
        this.tokenService = tokenService;
        this.captchaService = captchaService;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public CaptchaVO generateCaptcha(String username) {
        return captchaService.generate();
    }

    @Override
    public LoginFailHintVO loginHint(String username, String clientIp) {
        boolean need = username != null && loginAttemptService.needCaptcha(username, clientIp);
        long retryAfter = username == null ? 0L
                : loginAttemptService.lockRemaining(username, clientIp)
                        .map(d -> d.toSeconds())
                        .orElse(0L);
        return new LoginFailHintVO(need, retryAfter);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginRequest request, String clientIp) {
        String username = request.username();
        String password = request.password();

        // 1) 检查是否被锁定（命中则直接拒绝，不进入密码比对）
        Optional<java.time.Duration> lock = loginAttemptService.lockRemaining(username, clientIp);
        if (lock.isPresent()) {
            log.warn("账号已锁定，拒绝登录，username={}, 剩余={}秒", username, lock.get().toSeconds());
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号已锁定，请稍后再试");
        }

        // 2) 验证码（仅在需要时强制校验；不强制时允许为空通过）
        if (loginAttemptService.needCaptcha(username, clientIp)) {
            if (request.captchaKey() == null || request.captchaCode() == null
                    || !captchaService.verify(request.captchaKey(), request.captchaCode())) {
                log.warn("验证码校验失败，username={}", username);
                throw new BusinessException(ResultCode.BAD_REQUEST, "验证码不正确");
            }
        }

        // 3) 账号比对
        User user = userMapper.selectByUsername(username);
        boolean passwordMatched;
        if (user == null) {
            // 故意执行一次"必定失败"的 BCrypt 校验，让响应耗时与"账号存在但密码错"对齐
            passwordEncoder.matches(password, DUMMY_PASSWORD_HASH);
            passwordMatched = false;
        } else {
            passwordMatched = passwordEncoder.matches(password, user.getPassword());
        }

        // 4) 失败处理
        if (user == null || !passwordMatched) {
            int failed = loginAttemptService.recordFailure(username, clientIp);
            if (failed == -1) {
                throw new BusinessException(ResultCode.UNAUTHORIZED, "账号已锁定，请稍后再试");
            }
            // 错误信息故意模糊，不透露"账号不存在"或"密码错误"
            log.warn("登录失败，username={}, 连续失败={}次", username, failed);
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码不正确");
        }

        // 5) 账号被禁用
        if (user.getStatus() != null && user.getStatus() == 0) {
            log.warn("账号已禁用，username={}", username);
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        // 6) 成功：清失败计数、刷新登录时间、签发 Token
        loginAttemptService.recordSuccess(username, clientIp);
        try {
            userMapper.updateLastLoginTime(user.getId(), LocalDateTime.now());
        } catch (RuntimeException e) {
            // lastLoginTime 失败不应阻断登录——记 warn 后继续
            log.warn("刷新最近登录时间失败，userId={}, 原因={}", user.getId(), e.getMessage());
        }
        String token = tokenService.issue(user);
        UserVO userVO = CONVERTER.toVO(user);
        log.info("登录成功，userId={}, username={}", user.getId(), username);
        return new LoginVO(token, userVO, properties.getTokenTtl().toSeconds());
    }

    @Override
    public UserVO currentUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return CONVERTER.toVO(user);
    }

    @Override
    public void logout(String token) {
        tokenService.revoke(token);
        log.info("退出登录成功");
    }
}