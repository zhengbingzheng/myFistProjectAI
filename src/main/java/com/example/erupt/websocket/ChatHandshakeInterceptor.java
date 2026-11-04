package com.example.erupt.websocket;

import com.example.erupt.service.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WebSocket 握手阶段的身份校验。
 *
 * <p><b>为什么必须在握手阶段鉴权？</b>
 * 浏览器的 WebSocket API 不允许自定义请求头，因此凭证只能走 URL 查询参数。
 * 这意味着 token 会出现在访问日志里——这也是为什么本项目的鉴权采用短 TTL 的原因之一。
 * 更重要的是：如果把校验推迟到连接建立之后，非法连接会先占用一条 TCP 连接、
 * 一个会话对象，再被踢掉，等于给匿名流量留了一段可乘的时间窗。</p>
 *
 * <p>校验通过只做一件事：把 userId 写进会话属性。后续业务帧都从这里取身份，
 * 不再重复解析 token，也避免客户端在消息体里伪造 userId。</p>
 */
@Slf4j
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    private final TokenService tokenService;

    public ChatHandshakeInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        // 优先用一次性 ticket（避免长期 token 进 URL 日志）；无 ticket 再退回 token，兼容旧前端
        String ticket = extractParam(request.getURI(), "ticket");
        if (ticket != null) {
            Optional<String> userId = tokenService.consumeWsTicket(ticket);
            if (userId.isEmpty()) {
                log.warn("WebSocket 握手 ticket 无效或已过期，来源={}", request.getRemoteAddress());
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(ChatWebSocketHandler.ATTR_USER_ID, userId.get());
            return true;
        }

        String token = extractParam(request.getURI(), "token");
        if (token == null) {
            token = extractParam(request.getURI(), "access_token");
        }
        if (token == null) {
            log.warn("WebSocket 握手缺少 token/ticket，来源={}", request.getRemoteAddress());
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        Optional<TokenService.TokenIdentity> identity = tokenService.verify(token);
        if (identity.isEmpty()) {
            log.warn("WebSocket 握手 token 无效或已过期");
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ChatWebSocketHandler.ATTR_USER_ID, identity.get().userId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 握手完成后无额外清理工作：失败连接不会进入注册表
    }

    /**
     * 从查询串里按名称取值（token / access_token / ticket 通用）。
     */
    private static String extractParam(URI uri, String paramName) {
        String query = uri == null ? null : uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            if (paramName.equals(key)) {
                String value = pair.substring(eq + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /** 允许跨域的来源控制由外层配置负责，这里保留默认（不额外放行） */
    public List<String> allowedOrigins() {
        return List.of();
    }
}
