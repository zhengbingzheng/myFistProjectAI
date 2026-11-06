package com.example.erupt.websocket;

import com.example.erupt.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHandshakeInterceptorTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler handler;

    @Test
    @DisplayName("查询串里的长期 token 不能再握手")
    void longLivedTokenInQueryIsRejected() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost/ws/chat?token=abc"));
        when(request.getRemoteAddress()).thenReturn(null);
        ChatHandshakeInterceptor interceptor = new ChatHandshakeInterceptor(tokenService);

        boolean allowed = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(allowed).isFalse();
        verify(tokenService, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("一次性 ticket 可以握手")
    void ticketIsAccepted() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost/ws/chat?ticket=once"));
        when(tokenService.consumeWsTicket("once")).thenReturn(Optional.of("u1"));
        ChatHandshakeInterceptor interceptor = new ChatHandshakeInterceptor(tokenService);
        var attributes = new HashMap<String, Object>();

        boolean allowed = interceptor.beforeHandshake(request, response, handler, attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes.get(ChatWebSocketHandler.ATTR_USER_ID)).isEqualTo("u1");
    }
}
