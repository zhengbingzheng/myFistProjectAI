package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 未读消息按发送人聚合的摘要，用于右下角消息提醒。
 *
 * <p>设计成"按人聚合"而不是"按条推送"，是因为连续收到同一个人的多条消息时，
 * 弹 N 个提示框会盖住整个屏幕；聚合后只显示"XXX 发来 N 条消息"更符合直觉。</p>
 */
public record UnreadSummaryVO(
        String senderId,
        String senderName,
        String senderAvatar,
        Integer unreadCount,
        String lastContent,
        LocalDateTime lastTime,
        String conversationId
) {
}
