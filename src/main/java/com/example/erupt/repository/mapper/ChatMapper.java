package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.ChatConversation;
import com.example.erupt.repository.entity.ChatMember;
import com.example.erupt.repository.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 即时通信模块的数据访问。
 *
 * <p>会话 / 成员 / 消息三张表集中在一个 Mapper：它们总是被同一次业务操作联合访问
 * （发一条消息要写消息表、改会话摘要、累加成员未读），拆成三个接口反而让 Service
 * 里到处是跨 Mapper 调用，事务边界也更容易被误写在错误的位置。</p>
 */
@Mapper
public interface ChatMapper {

    /* ---------- 会话 ---------- */

    int insertConversation(ChatConversation conversation);

    ChatConversation selectConversationByBizKey(@Param("bizKey") String bizKey);

    ChatConversation selectConversationById(@Param("id") String id);

    int updateConversationLastMsg(@Param("id") String id,
                                  @Param("lastMsgId") String lastMsgId,
                                  @Param("lastMsgTime") java.time.LocalDateTime lastMsgTime);

    /**
     * 查询某用户的会话列表（按最后消息时间倒序）。
     * 同时带出对方的昵称与头像，避免前端再逐个调用用户信息接口。
     */
    List<java.util.Map<String, Object>> selectConversationsByUser(@Param("userId") String userId);

    /* ---------- 成员 ---------- */

    int insertMember(ChatMember member);

    List<ChatMember> selectMembersByConversation(@Param("conversationId") String conversationId);

    ChatMember selectMember(@Param("conversationId") String conversationId,
                            @Param("userId") String userId);

    /** 累加未读计数。用 SQL 自增而非先查后改，避免并发下计数被覆盖 */
    int incrMemberUnread(@Param("conversationId") String conversationId,
                         @Param("userId") String userId);

    /** 清空未读并推进已读位点 */
    int clearMemberUnread(@Param("conversationId") String conversationId,
                          @Param("userId") String userId,
                          @Param("lastReadMsgId") String lastReadMsgId);

    /** 该用户所有会话的未读总数，用于全局角标 */
    int sumUnreadByUser(@Param("userId") String userId);

    /* ---------- 消息 ---------- */

    int insertMessage(ChatMessage message);

    /**
     * 分页查历史消息。
     * 用 createTime 倒序取当前页后由应用层反转，配合 (conversation_id, create_time) 索引。
     */
    List<java.util.Map<String, Object>> selectMessages(@Param("conversationId") String conversationId,
                                                       @Param("offset") long offset,
                                                       @Param("limit") int limit);

    long countMessages(@Param("conversationId") String conversationId);

    /** 把会话中"别人发给我的"未读消息标记为已读 */
    int markMessagesRead(@Param("conversationId") String conversationId,
                         @Param("receiverId") String receiverId);

    /**
     * 未读消息按发送人聚合：给"登录后的消息提醒"用。
     * 返回发送人ID、昵称、头像、未读条数、最新一条内容与时间。
     */
    List<java.util.Map<String, Object>> selectUnreadGroupBySender(@Param("userId") String userId);
    /**
     * 某附件被哪条消息引用。
     *
     * <p>用于下载鉴权：只有引用了该附件的会话成员才有权读取，
     * 避免"登录了就能凭 id 遍历所有附件"。</p>
     */
    String selectConversationIdByAttachment(@Param("attachmentId") String attachmentId);
}
