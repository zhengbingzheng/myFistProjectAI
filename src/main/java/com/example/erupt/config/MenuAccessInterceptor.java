package com.example.erupt.config;

import com.example.erupt.common.JsonSupport;
import com.example.erupt.common.AuthContext;
import com.example.erupt.common.ResultCode;
import com.example.erupt.service.MenuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单权限拦截器：确保「未授权的模块，接口也进不来」。
 *
 * <p><b>为什么后端必须也拦一道？</b>
 * 前端隐藏菜单只是"看不见"，用户照样可以直接构造请求调用接口。
 * 真正的访问控制边界只能在服务端——前端的隐藏应当被视为体验优化，而不是安全措施。</p>
 *
 * <p><b>权限标识怎么映射</b>：菜单表里 {@code permission} 存模块名（如 {@code knowledge}），
 * 拦截器从 URI {@code /api/knowledge/xxx} 取出第二段作为待校验模块。
 * 也就是说，一个模块的菜单是否授权，决定了它的全部 API 是否可达。</p>
 *
 * <p><b>放行的三类路径</b>：
 * <ol>
 *   <li>认证相关——未登录用户必须能登录；</li>
 *   <li>聊天——所有登录用户都可用的基础能力，不参与菜单授权；</li>
 *   <li>{@code /api/menu/mine}——获取自己的菜单是渲染的前提，对它鉴权会形成死锁。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class MenuAccessInterceptor implements HandlerInterceptor {

    /** 不参与菜单鉴权的路径前缀 */
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/api/auth/",
            "/api/chat/",
            "/error"
    );

    /**
     * 不参与菜单鉴权的精确路径。
     *
     * <p>{@code /api/menu/mine}：获取自身菜单是渲染菜单的前提，对它鉴权会形成死锁。</p>
     *
     * <p>{@code /api/users/me}：查看与修改<b>自己的</b>资料属于基础能力，不应依赖菜单授权。
     * 若纳入 users 模块鉴权，未获授权的用户连自己的资料页都打不开——这正是此前
     * 「新增账号后无法查看个人信息」的根因之一。</p>
     */
    private static final Set<String> SKIP_EXACT = Set.of(
            "/api/menu/mine",
            "/api/users/me",
            "/api/users/me/password"
    );

    /**
     * 序列化工具不通过容器注入：本项目的 Spring 上下文并未暴露 ObjectMapper bean
     * （启动时会因找不到依赖而失败）。ObjectMapper 本身就是线程安全的，
     * 作为静态单例使用完全合适，还能顺带避免序列化配置被外部改写。
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonSupport.MAPPER;

    private final MenuService menuService;

    public MenuAccessInterceptor(MenuService menuService) {
        this.menuService = menuService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String uri = request.getRequestURI();
        if (shouldSkip(uri)) {
            return true;
        }
        String userId = AuthContext.currentUserId();
        if (userId == null) {
            // 未登录交给 AuthInterceptor 处理，这里不再重复写响应
            return true;
        }
        String module = extractModule(uri);
        if (module == null) {
            return true;
        }
        if (menuService.hasPermission(userId, module)) {
            return true;
        }
        log.warn("菜单权限拒绝 userId={} uri={} module={}", userId, uri, module);
        writeForbidden(response);
        return false;
    }

    private static boolean shouldSkip(String uri) {
        if (uri == null) {
            return true;
        }
        if (SKIP_EXACT.contains(uri)) {
            return true;
        }
        return SKIP_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    /**
     * 从 URI 提取模块名：{@code /api/knowledge/abc} → {@code knowledge}。
     * 非 {@code /api/} 开头或只有一段的路径返回 null（表示不参与模块鉴权）。
     */
    static String extractModule(String uri) {
        if (uri == null || !uri.startsWith("/api/")) {
            return null;
        }
        String[] segments = uri.split("/");
        // segments 形如 ["", "api", "knowledge", ...]
        if (segments.length < 3 || segments[2].isEmpty()) {
            return null;
        }
        return segments[2];
    }

    private void writeForbidden(HttpServletResponse response) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            Map<String, Object> body = Map.of(
                    "code", ResultCode.FORBIDDEN.code(),
                    "message", "无权限访问该菜单",
                    "data", null,
                    "timestamp", System.currentTimeMillis());
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            // 响应写出失败时状态码已设置，前端仍会收到 403
        }
    }

    /** 预留：将来若需按角色等级限制数据范围，从这里扩展 */
    public List<String> skippedPrefixes() {
        return List.copyOf(SKIP_PREFIXES);
    }
}
