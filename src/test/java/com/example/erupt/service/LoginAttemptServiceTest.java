package com.example.erupt.service;

import com.example.erupt.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录失败计数维度的单元测试。
 *
 * <p>重点锁住「账号大小写归一」：数据库 username 是大小写不敏感排序规则，
 * 能登录成功的写法必须与计入同一个失败桶的写法一致，否则换大小写即可绕过锁定。</p>
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AuthProperties properties;

    @InjectMocks
    private LoginAttemptService loginAttemptService;

    @Test
    @DisplayName("失败计数按账号做大小写归一_大小写变体落在同一个桶")
    void recordFailure_normalizesUsernameCase() {
        when(properties.getLoginFailPrefix()).thenReturn("auth:login:fail:");
        when(properties.getLoginFailWindow()).thenReturn(Duration.ofMinutes(15));
        when(properties.getLoginMaxFailures()).thenReturn(5);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        loginAttemptService.recordFailure("Admin", "10.0.0.8");
        loginAttemptService.recordFailure("ADMIN", "10.0.0.8");
        loginAttemptService.recordFailure(" admin ", "10.0.0.8");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(3)).increment(keyCaptor.capture());
        List<String> keys = keyCaptor.getAllValues();
        assertThat(keys).containsOnly("auth:login:fail:admin|10.0.0.8");
    }

    @Test
    @DisplayName("缺少客户端 IP 时退化为 unknown 维度")
    void recordFailure_blankIpUsesUnknownScope() {
        when(properties.getLoginFailPrefix()).thenReturn("auth:login:fail:");
        when(properties.getLoginFailWindow()).thenReturn(Duration.ofMinutes(15));
        when(properties.getLoginMaxFailures()).thenReturn(5);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        loginAttemptService.recordFailure("admin", "  ");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).increment(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo("auth:login:fail:admin|unknown");
    }
}
