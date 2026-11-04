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

    /**
     * 同上，但加 FOR UPDATE 走「当前读」。
     * 并发建会话的兜底场景必须用它：普通 SELECT 在 REPEATABLE READ 下
     * 沿用事务内早已建立的读视图，看不到对手方刚提交的会话行，
     * 兜底会永远落空并把竞态变成一句对不上病因的 409。
     */
    ChatConversation selectConversationByBizKeyForUpdate(@Param("bizKey") String bizKey);

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

    /**
     * 按本次标记已读的行数扣减未读计数。
     * 不能无条件清零：markRead 等行锁期间若恰好收到新消息（+1 已提交），
     * 清零会把那条新消息的未读一并抹掉——红点消失但消息列表里确实有未读。
     * 也不动 last_read_msg_id：当前没有消费方，覆盖成 NULL 只会破坏字段语义。
     */
    int clearMemberUnread(@Param("conversationId") String conversationId,
                          @Param("userId") String userId,
                          @Param("readCount") int readCount);

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
     * 请求者是否为「引用了该附件的某条会话」的成员。
     *
     * <p>用于下载鉴权：直接按请求者的成员关系过滤，
     * 避免"任取一条引用会话再判成员"时，同一附件被发到多个会话后
     * 合法成员被无序 LIMIT 1 误拒。</p>
     */
    String selectReadableConversationId(@Param("attachmentId") String attachmentId,
                                        @Param("userId") String userId);
}
