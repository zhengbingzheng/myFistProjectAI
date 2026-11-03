package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 会话列表项（前端菜单/列表直接消费）。
 *
 * <p>{@code peer*} 一组字段是会话对方的快照信息：单聊时前端直接展示对方昵称头像，
 * 不必再为每个会话调一次用户接口。群聊时这几个字段为空，改用 {@code convName}。</p>
 *
 * <p>{@code unreadCount} 取自会话成员的冗余计数（写消息时累加、读消息时清零），
 * 而不是每次 COUNT 消息表——列表接口被轮询的频率远高于读消息。</p>
 */
public record ConversationVO(
        String id,
        Integer convType,
        String convName,
        String convAvatar,
        String lastContent,
        Integer lastMsgType,
        LocalDateTime lastMsgTime,
        Integer unreadCount,
        String peerId,
        String peerName,
        String peerAvatar,
        String peerUsername,
        /**
         * 单聊对方当前是否在线。群聊没有单一对端，或对方已不存在时为 {@code null}，
         * 前端据此决定是否渲染状态标签——用 null 而不是 false，
         * 是为了区分"确认离线"与"不适用"。
         */
        Boolean peerOnline
) {
}
