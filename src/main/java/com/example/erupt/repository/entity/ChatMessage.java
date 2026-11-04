package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息实体，对应表 {@code t_chat_message}。
 *
 * <p>所有聊天记录持久化于此，是"历史消息"与"未读提醒"的唯一数据来源。
 * {@code receiverId} 在单聊场景冗余存储，便于按接收人直接查未读，
 * 不必每次都反查会话成员。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private String id;
    /** 会话ID */
    private String conversationId;
    /** 发送人ID */
    private String senderId;
    /** 接收人ID（单聊） */
    private String receiverId;
    /** 消息类型：1 文本，2 图片，3 文件，4 系统通知 */
    private Integer msgType;
    /** 消息内容 */
    private String content;

    /**
     * 附件ID（图片 / 文件消息）。
     *
     * <p>文本消息为 null。之所以单独存 id 而不是把 URL 塞进 content：
     * content 要参与全文式的列表预览（"最近一条说了什么"），
     * 混入存储路径后，文本与文件两种语义就再也分不开了，
     * 后续做撤回、清理孤儿附件也会无从下手。</p>
     */
    private String attachmentId;
    /** 已读标记：0 未读，1 已读 */
    private Integer readFlag;
    private LocalDateTime readTime;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private String createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
