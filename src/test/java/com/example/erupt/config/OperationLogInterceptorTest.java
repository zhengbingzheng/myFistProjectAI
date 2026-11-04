package com.example.erupt.config;

import com.example.erupt.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作日志拦截器的记录范围与字段推导测试。
 *
 * <p>record() 是异步旁路（入队即返回），因此可以直接断言传给服务层的实体内容，
 * 不需要等待落库。</p>
 */
@ExtendWith(MockitoExtension.class)
class OperationLogInterceptorTest {

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private OperationLogInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new OperationLogInterceptor(operationLogService);
        // lenient：各测试会按需覆盖这些公共桩，未被用到的不报 UnnecessaryStubbing
        org.mockito.Mockito.lenient().when(request.getMethod()).thenReturn("POST");
        org.mockito.Mockito.lenient().when(request.getRequestURI()).thenReturn("/api/users");
        org.mockito.Mockito.lenient().when(request.getAttribute(OperationLogInterceptor.ATTR_START_TIME))
                .thenReturn(System.currentTimeMillis());
        org.mockito.Mockito.lenient().when(response.getStatus()).thenReturn(200);
    }

    @Test
    @DisplayName("写请求正常记录：模块/操作/操作人/IP/traceId 齐全")
    void afterCompletion_writeRequest_recorded() {
        when(request.getRequestURI()).thenReturn("/api/knowledge");
        when(request.getQueryString()).thenReturn("page=1&pageSize=10");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1");
        MDC.put(TraceIdFilter.TRACE_ID_KEY, "abc123");

        try {
            interceptor.preHandle(request, response, new Object());
            interceptor.afterCompletion(request, response, new Object(), null);
        } finally {
            MDC.clear();
        }

        ArgumentCaptor<com.example.erupt.repository.entity.OperationLog> captor =
                ArgumentCaptor.forClass(com.example.erupt.repository.entity.OperationLog.class);
        verify(operationLogService).record(captor.capture());
        var log = captor.getValue();
        assertThat(log.getModule()).isEqualTo("knowledge");
        assertThat(log.getOperation()).isEqualTo("create");
        assertThat(log.getRequestUri()).isEqualTo("/api/knowledge");
        assertThat(log.getQueryString()).isEqualTo("page=1&pageSize=10");
        // 反向代理场景取 X-Forwarded-For 首段
        assertThat(log.getOperatorIp()).isEqualTo("203.0.113.7");
        assertThat(log.getTraceId()).isEqualTo("abc123");
        assertThat(log.getResult()).isEqualTo(1);
        assertThat(log.getCostMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("成功的GET不记录：查询没有审计价值")
    void afterCompletion_successfulGet_skipped() {
        when(request.getMethod()).thenReturn("GET");

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        verify(operationLogService, never()).record(any());
    }

    @Test
    @DisplayName("失败的GET记录：登录失败等异常请求要留痕")
    void afterCompletion_failedGet_recorded() {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/users");
        when(response.getStatus()).thenReturn(500);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        verify(operationLogService).record(any());
    }

    @Test
    @DisplayName("验证码与登录提示端点完全不记录")
    void afterCompletion_noiseEndpoints_skipped() {
        when(request.getRequestURI()).thenReturn("/api/auth/captcha");
        interceptor.afterCompletion(request, response, new Object(), null);
        when(request.getRequestURI()).thenReturn("/api/auth/login-hint");
        interceptor.afterCompletion(request, response, new Object(), null);

        verify(operationLogService, never()).record(any());
    }

    @Test
    @DisplayName("操作类型推导：登录/登出/上传/下载优先于HTTP方法")
    void deriveOperation_specialUris() {
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        when(request.getRequestURI()).thenReturn("/api/attachments/download/zip");
        when(request.getMethod()).thenReturn("POST");
        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<com.example.erupt.repository.entity.OperationLog> captor =
                ArgumentCaptor.forClass(com.example.erupt.repository.entity.OperationLog.class);
        verify(operationLogService, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues().get(0).getOperation()).isEqualTo("login");
        assertThat(captor.getAllValues().get(1).getOperation()).isEqualTo("download");
    }

    @Test
    @DisplayName("请求体不落库：queryString 为空也照常记录，绝不去读 body")
    void queryString_optional() {
        when(request.getQueryString()).thenReturn(null);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<com.example.erupt.repository.entity.OperationLog> captor =
                ArgumentCaptor.forClass(com.example.erupt.repository.entity.OperationLog.class);
        verify(operationLogService).record(captor.capture());
        assertThat(captor.getValue().getQueryString()).isNull();
    }
}
