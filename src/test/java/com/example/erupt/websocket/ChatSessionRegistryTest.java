package com.example.erupt.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocket 连接注册表：多端投递、失效连接清理、整体关停。
 *
 * <p>「整体关停」对应会话被吊销（改密/禁用/删号）时关闭该用户全部连接——
 * WS 只在握手时鉴权一次，不显式关闭的话旧连接会继续收到推送。</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatSessionRegistryTest {

    @Mock
    private WebSocketSession first;

    @Mock
    private WebSocketSession second;

    private final ChatSessionRegistry registry = new ChatSessionRegistry();

    @Test
    @DisplayName("同一用户第一条连接返回 true，后续返回 false")
    void register_onlyFirstConnectionIsNew() {
        assertThat(registry.register("u-1", first)).isTrue();
        assertThat(registry.register("u-1", second)).isFalse();
        assertThat(registry.countSessions("u-1")).isEqualTo(2);
    }

    @Test
    @DisplayName("closeUser 关闭该用户全部连接并清空注册表")
    void closeUser_closesAllAndClears() throws Exception {
        registry.register("u-1", first);
        registry.register("u-1", second);

        int closed = registry.closeUser("u-1");

        assertThat(closed).isEqualTo(2);
        assertThat(registry.countSessions("u-1")).isZero();
        verify(first).close(CloseStatus.GOING_AWAY);
        verify(second).close(CloseStatus.GOING_AWAY);
        // 重复调用幂等，不抛异常
        assertThat(registry.closeUser("u-1")).isZero();
    }

    @Test
    @DisplayName("推送跳过已关闭连接并把它剔除")
    void sendToUser_skipsClosedSession() throws Exception {
        when(first.isOpen()).thenReturn(true);
        when(second.isOpen()).thenReturn(false);
        registry.register("u-1", first);
        registry.register("u-1", second);

        int delivered = registry.sendToUser("u-1", "{\"type\":\"unread\"}");

        assertThat(delivered).isEqualTo(1);
        verify(first).sendMessage(any(TextMessage.class));
        verify(second, never()).sendMessage(any(TextMessage.class));
        verify(second).close(CloseStatus.GOING_AWAY);
        assertThat(registry.countSessions("u-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("用户不在线时推送返回 0")
    void sendToUser_offlineReturnsZero() {
        assertThat(registry.sendToUser("nobody", "{}")).isZero();
    }
}
