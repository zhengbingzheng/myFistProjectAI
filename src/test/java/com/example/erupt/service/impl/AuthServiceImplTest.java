package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.config.AuthProperties;
import com.example.erupt.model.dto.LoginRequest;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.CaptchaService;
import com.example.erupt.service.LoginAttemptService;
import com.example.erupt.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务的时序与错误映射测试。
 *
 * <p>账号不存在时必须仍然跑一次真实 BCrypt：否则"账号存在但密码错"（约 80ms）与
 * "账号不存在"（不到 1ms）的耗时差会把登录接口变成账号枚举探针。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private TokenService tokenService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthProperties properties;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    @DisplayName("账号不存在_仍以合法 BCrypt 串执行一次比对")
    void unknownUser_stillRunsBcryptWithValidHash() {
        when(loginAttemptService.lockRemaining("ghost", "10.0.0.8"))
                .thenReturn(Optional.empty());
        when(loginAttemptService.needCaptcha("ghost", "10.0.0.8")).thenReturn(false);
        when(userMapper.selectByUsername("ghost")).thenReturn(null);
        when(loginAttemptService.recordFailure("ghost", "10.0.0.8")).thenReturn(1);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("ghost", "whatever", null, null), "10.0.0.8"))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(eq("whatever"), hashCaptor.capture());
        // 59 字符的串会被 BCryptPasswordEncoder 的格式预校验直接拒绝，根本不跑哈希——
        // 那样等于没有对齐耗时
        assertThat(hashCaptor.getValue()).hasSize(60);
        assertThat(hashCaptor.getValue()).matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    }

    @Test
    @DisplayName("账号被锁定_不进入密码比对")
    void lockedAccount_skipsPasswordCheck() {
        when(loginAttemptService.lockRemaining("admin", "10.0.0.8"))
                .thenReturn(Optional.of(Duration.ofMinutes(3)));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin", "whatever", null, null), "10.0.0.8"))
                .isInstanceOf(BusinessException.class);

        verify(userMapper, org.mockito.Mockito.never()).selectByUsername(org.mockito.ArgumentMatchers.anyString());
    }
}
