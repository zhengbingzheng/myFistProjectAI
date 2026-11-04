package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天会话实体，对应表 {@code t_chat_conversation}。
 *
 * <p>单聊会话由 {@code bizKey} 唯一确定（双方用户ID升序拼接），
 * 保证同一对用户无论谁先发起都复用同一个会话。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatConversation {

    /** 主键 */
    private String id;
    /** 会话类型：1 单聊，2 群聊 */
    private Integer convType;
    /** 业务唯一键：单聊为双方用户ID升序拼接 */
    private String bizKey;
    /** 会话名称 */
    private String convName;
    /** 会话头像 */
    private String convAvatar;
    /** 创建人（群主） */
    private String ownerId;
    /** 最后一条消息ID */
    private String lastMsgId;
    /** 最后一条消息时间 */
    private LocalDateTime lastMsgTime;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
