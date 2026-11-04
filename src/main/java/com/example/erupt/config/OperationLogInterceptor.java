package com.example.erupt.config;

import com.example.erupt.common.AuthContext;
import com.example.erupt.repository.entity.OperationLog;
import com.example.erupt.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;

/**
 * 系统操作日志拦截器：把 /api/** 请求的审计信息异步写入 {@code t_operation_log}。
 *
 * <p><b>记录范围（刻意的取舍）</b>：</p>
 * <ul>
 *   <li>所有<b>写请求</b>（POST/PUT/DELETE）一律记录——审计价值最高；</li>
 *   <li>GET 只在<b>失败</b>（状态码 ≥ 400）时记录——成功的查询量大且无审计价值，
 *       全记会让表以每页浏览 +N 行的速度膨胀；</li>
 *   <li>验证码 / 登录前置提示这两个高频匿名端点完全不记——纯噪音。</li>
 * </ul>
 *
 * <p><b>不记录请求体</b>：body 里可能有密码（登录、改密、建账号），
 * 审计库不应成为敏感信息的第二份存储。查询参数本身已足够定位请求。</p>
 *
 * <p><b>已知的覆盖盲区</b>：拦截器链短路时（AuthInterceptor 401、MenuAccessInterceptor 403），
 * 排在后面的本拦截器的 afterCompletion 不会被调用，这类请求不产生日志。
 * 换 Filter 可以全覆盖，但拿不到 AuthContext（它在线程里的存留期只覆盖拦截器链），
 * 操作人字段会全部为空——取舍后保留拦截器方案。</p>
 */
@Slf4j
@Component
public class OperationLogInterceptor implements HandlerInterceptor {

    /** 完全不记录的高频匿名端点 */
    private static final Set<String> SKIP_URIS = Set.of(
            "/api/auth/captcha",
            "/api/auth/login-hint"
    );

    /** 请求开始时间在 request attribute 里的 key（包可见，供单元测试断言使用） */
    static final String ATTR_START_TIME = "OPERATION_LOG_START";

    private final OperationLogService operationLogService;

    public OperationLogInterceptor(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START_TIME, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        try {
            String uri = request.getRequestURI();
            String method = request.getMethod();
            int status = response.getStatus();
            boolean failed = status >= 400;
            // 成功 GET 不记：查询没有审计价值，全记会让表膨胀得比业务还快
            if ("GET".equals(method) && !failed) {
                return;
            }
            if (SKIP_URIS.contains(uri)) {
                return;
            }
            operationLogService.record(buildLog(request, uri, method, status, failed));
        } catch (Exception e) {
            // 审计是旁路：任何构造/投递异常都不能影响请求收尾
            log.warn("操作日志记录失败：{}", e.getMessage());
        }
    }

    private OperationLog buildLog(HttpServletRequest request, String uri, String method,
                                  int status, boolean failed) {
        Object start = request.getAttribute(ATTR_START_TIME);
        long costMs = start instanceof Long begin ? System.currentTimeMillis() - begin : 0;

        OperationLog item = new OperationLog();
        item.setId(UUID.randomUUID().toString().replace("-", ""));
        item.setModule(extractModule(uri));
        item.setOperation(deriveOperation(method, uri));
        item.setRequestMethod(method);
        item.setRequestUri(uri);
        item.setQueryString(trimTo(request.getQueryString(), 512));
        item.setOperatorId(AuthContext.currentUserId());
        item.setOperatorName(AuthContext.currentUsername());
        item.setOperatorIp(resolveClientIp(request));
        item.setResult(failed ? 0 : 1);
        item.setStatusCode(status);
        item.setCostMs(costMs);
        item.setTraceId(MDC.get(TraceIdFilter.TRACE_ID_KEY));
        return item;
    }

    /** 模块名：URI 第二段（/api/knowledge/list → knowledge），与菜单 permission 对应 */
    private static String extractModule(String uri) {
        String[] segments = uri.split("/");
        return segments.length >= 3 ? segments[2] : "unknown";
    }

    /**
     * 操作类型推导：特殊 URI 优先（登录/登出/上传/下载），
     * 其余按 HTTP 方法映射。推导结果只服务于日志检索的归类，不必追求精确。
     */
    private static String deriveOperation(String method, String uri) {
        if (uri.startsWith("/api/auth/login")) {
            return "login";
        }
        if (uri.startsWith("/api/auth/logout")) {
            return "logout";
        }
        if (uri.contains("upload")) {
            return "upload";
        }
        if (uri.contains("download") || uri.contains("/zip")) {
            return "download";
        }
        return switch (method) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            case "GET" -> "query";
            default -> "other";
        };
    }

    /** 反向代理在后时取 X-Forwarded-For 的首段（最早的客户端 IP），否则取直连地址 */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return trimTo(first.trim(), 64);
        }
        return trimTo(request.getRemoteAddr(), 64);
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
