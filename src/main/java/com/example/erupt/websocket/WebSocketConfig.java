package com.example.erupt.websocket;

import com.example.erupt.service.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配。
 *
 * <p><b>为什么不用 STOMP？</b>
 * STOMP 提供订阅/广播语义与消息代理，听起来更"标准"，但它要求前端引入额外的
 * stomp.js 客户端，并且会把消息代理（broker）引入进来。本项目只需要
 * 「服务端按 userId 定向推送」这一种模式，用原生 {@code TextWebSocketHandler}
 * 配合 {@link ChatSessionRegistry} 就能完成，少一层协议也就少一层出错的地方。</p>
 *
 * <p><b>握手处理器用默认实现</b>：默认处理器已适配当前 Servlet 容器，
 * 且聊天消息体都很小，无需调整缓冲区。自定义 {@code DefaultHandshakeHandler}
 * 反而需要显式指定具体容器的升级策略类，把代码和 Tomcat 绑死。</p>
 *
 * <p><b>跨域来源</b>：默认同源。前后端分离部署时通过
 * {@code app.ws.allowed-origins} 配置白名单，
 * 切勿无条件设成 {@code *}——那等于允许任意站点用你用户的凭证建立连接。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatHandler;
    private final TokenService tokenService;

    @Value("${app.ws.allowed-origins:}")
    private String allowedOrigins;

    public WebSocketConfig(ChatWebSocketHandler chatHandler, TokenService tokenService) {
        this.chatHandler = chatHandler;
        this.tokenService = tokenService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(chatHandler, "/ws/chat")
                .addInterceptors(new ChatHandshakeInterceptor(tokenService));
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            registration.setAllowedOrigins(allowedOrigins.split(","));
        }
    }
}
