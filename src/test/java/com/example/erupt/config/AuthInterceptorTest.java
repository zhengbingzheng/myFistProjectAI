package com.example.erupt.config;

import com.example.erupt.common.AuthContext;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 鉴权拦截器的 ThreadLocal 生命周期。
 *
 * <p>异步请求（SSE / Callable / StreamingResponseBody）的 {@code afterCompletion}
 * 不会在本次 dispatch 执行，身份必须在 {@code afterConcurrentHandlingStarted}
 * 与下一次 {@code preHandle} 开头被清掉，否则线程池复用会把 A 用户身份带给 B。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private AuthProperties properties;

    @Mock
    private UserMapper userMapper;

    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(tokenService, properties, userMapper);
    }

    @Test
    @DisplayName("匿名白名单请求进入时清理上一个请求残留的身份")
    void preHandle_permitPath_clearsStaleIdentity() throws Exception {
        AuthContext.set(new TokenService.TokenIdentity("stale-user", "stale", Long.MAX_VALUE));
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getPermitPaths()).thenReturn(new String[]{"/api/auth/login"});

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/auth/login"),
                new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        assertThat(AuthContext.get()).isNull();
    }

    @Test
    @DisplayName("异步开始回调清理身份，防止容器线程复用串号")
    void afterConcurrentHandlingStarted_clearsIdentity() {
        AuthContext.set(new TokenService.TokenIdentity("u-1", "alice", Long.MAX_VALUE));

        interceptor.afterConcurrentHandlingStarted(
                new MockHttpServletRequest("GET", "/api/agent/chat/stream"),
                new MockHttpServletResponse(), new Object());

        assertThat(AuthContext.get()).isNull();
    }
}
