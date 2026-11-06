package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.JsonSupport;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.vo.NotificationVO;
import com.example.erupt.repository.entity.Notification;
import com.example.erupt.repository.mapper.NotificationMapper;
import com.example.erupt.service.NotificationService;
import com.example.erupt.websocket.ChatSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 站内通知服务实现。
 *
 * <p>推送链路与 ChatServiceImpl 同一套机制：写入挂在调用方事务上，提交后才走 WebSocket，
 * 避免「通知先到、数据还没提交」。帧格式见 {@link #buildPushPayload}。</p>
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    /** 通知类型白名单：事件源传错类型会让前端图标/页签渲染不出来，写入时就拦下 */
    private static final Set<String> VALID_TYPES = Set.of("agent", "task", "system");

    private static final ObjectMapper MAPPER = JsonSupport.MAPPER;

    private final NotificationMapper notificationMapper;
    private final ChatSessionRegistry registry;

    public NotificationServiceImpl(NotificationMapper notificationMapper, ChatSessionRegistry registry) {
        this.notificationMapper = notificationMapper;
        this.registry = registry;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notify(String userId, String title, String content, String type, String bizPath) {
        // 不需要去重的通知 bizId 为空：委托给完整重载，单一实现路径
        notify(userId, title, content, type, bizPath, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notify(String userId, String title, String content, String type, String bizPath, String bizId) {
        if (userId == null || userId.isBlank()) {
            // 通知没有收件人就没有意义：事件源拿不到 userId 时直接跳过，而不是报错打断主流程
            log.debug("通知跳过：接收人为空 title={}", title);
            return;
        }
        if (type == null || !VALID_TYPES.contains(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知的通知类型：" + type);
        }
        Notification notification = new Notification(
                newId(), userId, title,
                content == null || content.isBlank() ? null : content,
                type, bizId, bizPath, "0", null, "0", null, null);
        notificationMapper.insert(notification);

        // 推送挂在事务提交后：业务数据可见了才提醒用户去看
        afterCommit(() -> push(userId, notification.getId()));
    }

    /** 推送单条通知帧：unread 一起带上，前端收到即可刷新角标，不必再发一次请求 */
    private void push(String userId, String notificationId) {
        try {
            Notification latest = notificationMapper.selectByIdAndUser(notificationId, userId);
            long unread = notificationMapper.countUnread(userId);
            registry.sendToUser(userId, buildPushPayload(latest, unread));
        } catch (Exception e) {
            // 推送失败不影响通知已落库——刷新页面仍能看到，留个痕便于排查 WS 链路问题
            log.warn("通知推送失败 userId={} 原因={}", userId, e.getMessage());
        }
    }

    /**
     * WS 帧格式：{"type":"notification","unread":N,"notification":{...}}。
     * 与即时通信的 unread / presence 帧同一条连接、同一套 type 分发约定。
     */
    private String buildPushPayload(Notification notification, long unread) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "notification");
        payload.put("unread", unread);
        payload.put("notification", toVO(notification));
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"notification\"}";
        }
    }

    @Override
    public Map<String, Object> list(String userId, String type, String readFlag, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long offset = (long) (safePage - 1) * safeSize;
        List<Notification> entities = notificationMapper.selectPageByUser(
                userId, type, readFlag, safeSize, offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset);
        long total = notificationMapper.countByUser(userId, type, readFlag);
        List<NotificationVO> items = new ArrayList<>(entities.size());
        for (Notification entity : entities) {
            items.add(toVO(entity));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    @Override
    public long unreadCount(String userId) {
        return notificationMapper.countUnread(userId);
    }

    @Override
    public void markRead(String userId, String id) {
        // 只更新未读行：重复标记幂等；用受影响行数区分「已读过」与「不存在」
        int updated = notificationMapper.markRead(id, userId);
        if (updated == 0 && notificationMapper.selectByIdAndUser(id, userId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在或已被删除");
        }
    }

    @Override
    public int markAllRead(String userId) {
        return notificationMapper.markAllRead(userId);
    }

    @Override
    public void delete(String userId, String id) {
        int updated = notificationMapper.logicDelete(id, userId);
        if (updated == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "通知不存在或已被删除");
        }
    }

    @Override
    public List<NotificationVO> toVOs(List<?> entities) {
        List<NotificationVO> result = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            result.add(toVO((Notification) entity));
        }
        return result;
    }

    private static NotificationVO toVO(Notification n) {
        return new NotificationVO(n.getId(), n.getTitle(), n.getContent(), n.getType(),
                n.getBizPath(), n.getReadFlag(), n.getReadTime(), n.getCreateTime());
    }

    /** 主键生成：UUID 去横线（与全库「应用层生成字符串主键」的约定一致） */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 把动作挂到当前事务的提交后回调。
     * 没有事务时直接执行——非事务场景（如流式对话线程）也会走到这里。
     */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
