package com.example.erupt.websocket;

import com.example.erupt.common.JsonSupport;
import com.example.erupt.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 聊天 WebSocket 的收尾服务端。
 *
 * <p><b>职责边界</b>：这里只管"连接生命周期"和"把收到的帧转交业务层"，
 * 不做任何业务判断——消息的落库、未读累加、会话摘要更新都在 {@link ChatService}，
 * 这样 HTTP 与 WebSocket 两条入口共享同一套业务逻辑，不会出现
 * "网页发的消息有记录、WebSocket 发的没有"这种割裂。</p>
 *
 * <p><b>鉴权时机</b>：在握手阶段（{@code ChatHandshakeInterceptor}）完成，
 * 握手失败的连接根本到不了这里。因此进入本类的连接一定是已认证用户。</p>
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** 会话属性名：握手阶段写入的用户ID */
    public static final String ATTR_USER_ID = "WS_USER_ID";

    /** 同上：ObjectMapper 线程安全，静态单例即可，且不依赖容器是否暴露该 bean */
    private static final ObjectMapper OBJECT_MAPPER = JsonSupport.MAPPER;

    private final ChatSessionRegistry registry;
    private final ChatService chatService;

    public ChatWebSocketHandler(ChatSessionRegistry registry, ChatService chatService) {
        this.registry = registry;
        this.chatService = chatService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null || userId.isBlank()) {
            log.warn("WebSocket 缺少用户身份，拒绝连接");
            closeQuietly(session);
            return;
        }
        // 单用户连接数上限：ticket 可无限签发，没有上限时一个账号就能用连接对象打满堆内存
        if (registry.countSessions(userId) >= ChatSessionRegistry.MAX_SESSIONS_PER_USER) {
            log.warn("WebSocket 连接数超过上限，拒绝建连 userId={} 现有={} 上限={}",
                    userId, registry.countSessions(userId), ChatSessionRegistry.MAX_SESSIONS_PER_USER);
            closeQuietly(session);
            return;
        }
        // 只在"该用户的第一条连接"时广播上线：多标签页同时打开不该给对端发一串重复通知。
        // 判断由 register 在锁内原子返回——注册后再读 countSessions 会在并发建连时漏报
        if (registry.register(userId, session)) {
            chatService.broadcastPresence(userId, true);
        }
        // 上线即推送未读概览：覆盖"登录后立刻弹提醒"的场景，
        // 不必等下一个新消息到来才有反应
        String payload = chatService.buildUnreadPushPayload(userId);
        if (payload != null) {
            registry.sendToUser(userId, payload);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            return;
        }
        String body = message.getPayload();
        if (body == null || body.isBlank()) {
            return;
        }
        Map<?, ?> frame;
        try {
            frame = OBJECT_MAPPER.readValue(body, Map.class);
        } catch (Exception e) {
            log.warn("WebSocket 收到非法帧 userId={} 内容长度={}", userId, body.length());
            return;
        }
        Object type = frame.get("type");
        if ("ping".equals(type)) {
            // 心跳：回 pong，让前端能判断链路是否存活
            sendQuietly(session, "{\"type\":\"pong\"}");
            return;
        }
        if ("read".equals(type)) {
            // 已读回执：进入会话后告诉服务端清未读，其他人那边不需要感知。
            // 必须兜住异常：markRead 的业务校验（403）或瞬时 DB 异常一旦冒出这里，
            // Spring 会把整条 WebSocket 连接按 SERVER_ERROR 关闭——
            // 一个格式合法的 read 帧不应该有砍断聊天长连接的威力
            Object convId = frame.get("conversationId");
            if (convId != null) {
                try {
                    chatService.markRead(userId, String.valueOf(convId));
                } catch (Exception e) {
                    log.warn("已读回执处理失败 userId={} conversationId={} 原因={}",
                            userId, convId, e.getMessage());
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        // 只有"这次调用真的移除了连接"才广播下线，避免与 handleTransportError 重复通知
        if (userId != null && registry.unregister(userId, session)) {
            notifyOfflineIfLast(userId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常：{}", exception.getMessage());
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        if (userId != null && registry.unregister(userId, session)) {
            notifyOfflineIfLast(userId);
        }
    }

    /**
     * 该用户已没有任何连接时才广播离线。
     * 用"剩余连接数 == 0"判断而不是直接置离线，是因为一个人可能同时开着多个标签页/设备，
     * 关掉其中一个不代表他真的离开了。
     */
    private void notifyOfflineIfLast(String userId) {
        if (registry.countSessions(userId) == 0) {
            chatService.broadcastPresence(userId, false);
        }
    }

    private static void sendQuietly(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        } catch (Exception e) {
            // 心跳回包失败无需处理，下一次心跳会再试
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) {
            // 关闭失败说明连接本来就已经断开了
        }
    }

    /**
     * 保留默认的分帧策略。
     * 聊天消息体都很小，不需要开启分片组装，开了反而增加内存占用与复杂度。
     */
    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

}
