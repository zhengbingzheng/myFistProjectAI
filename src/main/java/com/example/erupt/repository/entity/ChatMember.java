package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话成员实体，对应表 {@code t_chat_member}。
 *
 * <p>{@code unreadCount} 冗余在此，避免每次都去消息表 count。
 * 它与消息表的 read_flag 可能短暂不一致（写消息与更新计数不在同一事务时），
 * 以本表为准展示气泡数字，进入会话后校准。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMember {

    private String id;
    /** 会话ID */
    private String conversationId;
    /** 成员用户ID */
    private String userId;
    /** 成员角色：1 普通成员，2 群主 */
    private Integer memberRole;
    /** 最后已读消息ID */
    private String lastReadMsgId;
    /** 未读消息数 */
    private Integer unreadCount;
    private LocalDateTime joinTime;
    /** 删除标记：0 正常，1 已退出 */
    private String delFlag;
    private String createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
