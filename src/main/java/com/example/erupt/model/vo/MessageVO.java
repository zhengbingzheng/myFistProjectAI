package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 聊天消息（列表返回）。
 *
 * <p>{@code mine} 由服务端按当前登录人算出：前端直接据此决定气泡靠左还是靠右，
 * 不必自己在文本比对 senderId（顺带也避免了前端拿不到的场景）。</p>
 */
public record MessageVO(
        String id,
        String conversationId,
        String senderId,
        String senderName,
        String senderAvatar,
        Integer msgType,
        String content,
        Integer readFlag,
        LocalDateTime createTime,
        boolean mine,
        /**
         * 图片 / 文件消息的附件信息；文本消息为 null。
         * 前端据此决定渲染成文字气泡、图片还是文件卡片——
         * 而不是靠解析 content（那样文本和文件语义会纠缠在一起）。
         */
        ChatAttachmentVO attachment
) {
}
