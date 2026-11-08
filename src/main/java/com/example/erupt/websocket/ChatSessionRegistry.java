package com.example.erupt.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
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
 *
 * <p><b>并发约定</b>：「移除连接 + 空则删 key」这类多步操作必须整体放进
 * {@link ConcurrentHashMap#compute}，不能先 remove 再判断再 remove——
 * 否则旧连接断开与新连接注册交错时，会把含新连接的 Set 整个摘出 Map，
 * 该用户的后续推送全部静默丢失（连接还在，注册表里却没有它了）。</p>
 */
@Slf4j
@Component
public class ChatSessionRegistry {

    /**
     * 单用户连接上限。多标签页/多设备是正常需求，但没有上限时一个账号就能把
     * JVM 堆和推送遍历成本无限拉高：ticket 只要求登录、可无限签发，
     * 连接建立时还会各跑一次未读聚合查询。
     */
    public static final int MAX_SESSIONS_PER_USER = 8;

    /** userId → 该用户的所有在线连接 */
    private final Map<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    /**
     * 注册一条连接。
     *
     * @return 是否该用户的<b>第一条</b>连接。调用方据此广播上线——
     * 若注册后再读 {@code countSessions() == 1} 判断，两个标签页并发建连时
     * 双方都可能读到 2，导致一次上线通知都不发。
     */
    public boolean register(String userId, WebSocketSession session) {
        boolean[] first = {false};
        sessionsByUser.compute(userId, (k, sessions) -> {
            if (sessions == null) {
                sessions = ConcurrentHashMap.newKeySet();
            }
            first[0] = sessions.isEmpty();
            sessions.add(session);
            return sessions;
        });
        log.info("WebSocket 建立连接 userId={} 当前连接数={}", userId, countSessions(userId));
        return first[0];
    }

    /**
     * 移除一条连接；该用户最后一条断开时清理 key，避免 Map 无限增长。
     * 两步在 compute 内原子完成，见类注释的并发约定。
     *
     * <p><b>返回值的意义</b>：同一次断开往往既触发 {@code handleTransportError}
     * 又触发 {@code afterConnectionClosed}，只有第一次调用能真正移除这条连接。
     * 调用方据此决定是否广播"下线"——否则对端会收到两条一模一样的离线通知。</p>
     *
     * @return 该连接确实被移除时返回 {@code true}
     */
    public boolean unregister(String userId, WebSocketSession session) {
        boolean[] removed = {false};
        sessionsByUser.compute(userId, (k, sessions) -> {
            if (sessions == null || !sessions.remove(session)) {
                // 已经被另一条回调移除过了，这里什么都不做
                return sessions;
            }
            removed[0] = true;
            return sessions.isEmpty() ? null : sessions;
        });
        if (removed[0]) {
            log.info("WebSocket 断开连接 userId={} 剩余连接数={}", userId, countSessions(userId));
        }
        return removed[0];
    }

    public int countSessions(String userId) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        return sessions == null ? 0 : sessions.size();
    }

    /**
     * 关闭并移除某个用户的<b>全部</b>连接。
     *
     * <p>用于「会话被整体吊销」的场景（改密、管理员重置密码、禁用、删号）：
     * WS 只在握手时鉴权一次，之后不复查 Token/账号状态；不显式关闭的话，
     * 已登出的连接仍会持续收到含最新消息正文的未读推送，等于吊销只对 HTTP 生效。</p>
     *
     * @return 实际关闭的连接数
     */
    public int closeUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        Set<WebSocketSession> sessions = sessionsByUser.remove(userId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        for (WebSocketSession session : sessions) {
            closeQuietly(session);
        }
        log.info("已关闭用户全部 WebSocket 连接 userId={} 连接数={}", userId, sessions.size());
        return sessions.size();
    }

    /**
     * 向指定用户推送文本消息。
     *
     * <p>逐个 connection 发送而不是只用第一条，保证多端都能收到。
     * 单条连接发送失败只记录、移除并关闭，不影响其它连接——一个坏连接不该拖垮整次推送。</p>
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
                discard(userId, session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new org.springframework.web.socket.TextMessage(payload));
                }
                delivered++;
            } catch (IOException e) {
                log.warn("WebSocket 推送失败 userId={} 原因={}", userId, e.getMessage());
                discard(userId, session);
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

    /**
     * 从注册表剔除一条失效连接并关闭它。
     * 只移除不关闭的话，坏 socket 只能靠容器超时兜底；最后一条移除后还要清 key，
     * 否则 {@link #onlineUserCount()} 虚高、Map 缓慢泄漏。
     *
     * <p>「移除 + 空则删 key」整体放进 compute（见类注释的并发约定）：
     * 若拆成三步，"移除后集合暂空"的瞬间与新连接 register 的 compute 交错，
     * register 会往这个即将被摘除的 Set 里塞进新连接——随后 remove(key, value)
     * 按引用匹配照样把整个 Set 摘掉，新连接就此失联，所有推送静默丢失。</p>
     */
    private void discard(String userId, WebSocketSession session) {
        sessionsByUser.compute(userId, (k, sessions) -> {
            if (sessions == null) {
                return null;
            }
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
        closeQuietly(session);
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.GOING_AWAY);
        } catch (Exception ignored) {
            // 关闭失败说明连接本来就已经断开了
        }
    }
}
