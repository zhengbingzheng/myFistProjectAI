package com.example.erupt.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接的在线注册表：维护「用户ID → 该用户的全部会话连接」。
 *
 * <p>用一对多而不是一对一，是因为同一个账号完全可能在多个标签页、
 * 或多个设备上同时打开系统——这种情况下每一处都应当收到消息。</p>
 *
 * <p><b>为什么用内存而不是 Redis？</b>
 * WebSocket 连接本身就绑定在某个 JVM 的连接上，跨实例广播必须走消息中间件
 * （Redis Pub/Sub 或 MQ）。当前是单实例部署，内存 Map 足够，
 * 且省掉一层网络往返。若将来要多实例，把 {@link #sendToUser} 的发送动作
 * 换成"发布到 Redis 频道 + 各实例订阅后本地投递"即可，上层业务代码不用改。</p>
 */
@Slf4j
@Component
public class ChatSessionRegistry {

    /** userId → 该用户的所有在线连接 */
    private final Map<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    /** 注册一条连接 */
    public void register(String userId, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WebSocket 建立连接 userId={} 当前连接数={}", userId, countSessions(userId));
    }

    /**
     * 移除一条连接；该用户最后一条断开时清理 key，避免 Map 无限增长。
     *
     * <p><b>返回值的意义</b>：同一次断开往往既触发 {@code handleTransportError}
     * 又触发 {@code afterConnectionClosed}，只有第一次调用能真正移除这条连接。
     * 调用方据此决定是否广播"下线"——否则对端会收到两条一模一样的离线通知。</p>
     *
     * @return 该连接确实被移除时返回 {@code true}
     */
    public boolean unregister(String userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null) {
            return false;
        }
        if (!sessions.remove(session)) {
            // 已经被另一条回调移除过了，这里什么都不做
            return false;
        }
        if (sessions.isEmpty()) {
            sessionsByUser.remove(userId, sessions);
        }
        log.info("WebSocket 断开连接 userId={} 剩余连接数={}", userId, countSessions(userId));
        return true;
    }

    public int countSessions(String userId) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        return sessions == null ? 0 : sessions.size();
    }

    /**
     * 向指定用户推送文本消息。
     *
     * <p>逐个 connection 发送而不是只用第一条，保证多端都能收到。
     * 单条连接发送失败只记录并移除，不影响其它连接——一个坏连接不该拖垮整次推送。</p>
     *
     * @return 成功送达的连接数；0 表示该用户当前不在线
     */
    public int sendToUser(String userId, String payload) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new org.springframework.web.socket.TextMessage(payload));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("WebSocket 推送失败 userId={} 原因={}", userId, e.getMessage());
                sessions.remove(session);
            }
        }
        return delivered;
    }

    /** 该用户是否在线 */
    public boolean isOnline(String userId) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /** 在线用户数（用于管理端观察） */
    public int onlineUserCount() {
        return sessionsByUser.size();
    }
}
