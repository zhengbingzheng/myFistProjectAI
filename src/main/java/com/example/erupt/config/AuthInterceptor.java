package com.example.erupt.config;

import com.example.erupt.common.AuthContext;
import com.example.erupt.repository.entity.User;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 鉴权拦截器。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>不依赖 Spring Security：本项目体量不大，自管一个 HandlerInterceptor 即可；
 *       引入 Spring Security 会让所有 Bean 都得通过它的过滤器链，本项目不想承受这层负担。</li>
 *   <li>白名单（登录/注册/验证码）从 {@link AuthProperties#getPermitPaths()} 读取，
 *       便于日后新增无需鉴权的端点时只改配置不改代码。</li>
 *   <li>Token 从 {@code Authorization: Bearer ...} 头取；缺失、过期、签名错一律 401，
 *       响应体沿用统一的信封结构，前端能无差别解析。</li>
 *   <li><b>必须在 afterCompletion 清理 {@link AuthContext}</b>：线程池会复用线程，
 *       否则 A 用户的请求可能读到 B 用户留下来的身份。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final AuthProperties properties;
    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        if (!properties.isEnabled()) {
            return true;
        }
        if (isPermit(request.getRequestURI())) {
            return true;
        }

        String token = extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            writeUnauthorized(response, "请先登录");
            return false;
        }

        Optional<TokenService.TokenIdentity> identityOpt = tokenService.verify(token);
        if (identityOpt.isEmpty()) {
            writeUnauthorized(response, "登录已过期，请重新登录");
            return false;
        }
        TokenService.TokenIdentity identity = identityOpt.get();

        // 账号被禁用或已删除：即便 Token 仍在有效期内，也立即拒绝。
        // 这里每次请求都会查一次库（主键查询，代价很小），换来的是
        // "管理员禁用某账号后，对方下一次请求即失效"——后台管理场景需要这种即时性。
        // 若将来 QPS 很高，可把用户状态缓存到 Redis（TTL 设短）再降级为不查库。
        User user = userMapper.selectById(identity.userId());
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            writeUnauthorized(response, "账号已禁用或不存在");
            return false;
        }

        AuthContext.set(identity);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // 无论正常返回还是抛异常都要清理，避免线程复用导致身份串号
        AuthContext.clear();
    }

    private boolean isPermit(String uri) {
        if (uri == null) {
            return false;
        }
        for (String prefix : properties.getPermitPaths()) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 提取 Bearer Token 原文；未提供或格式不对返回 null */
    private static String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 手工拼一段与 Result 同构的 JSON 返回 401。
     *
     * <p>刻意不用 ObjectMapper：本项目 classpath 上同时存在 Jackson 2 与 Jackson 3，
     * 注入哪一个取决于自动配置的选择，在拦截器的异常路径上依赖它并不明智。
     * 这段响应体是固定结构，手写更可控，也没有转义出错的风险。</p>
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws java.io.IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null,"
                + "\"timestamp\":" + System.currentTimeMillis() + "}";
        response.getWriter().write(body);
    }
}