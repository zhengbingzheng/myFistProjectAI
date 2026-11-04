package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.model.dto.SendMessageRequest;
import com.example.erupt.model.vo.ConversationVO;
import com.example.erupt.model.vo.MessageVO;
import com.example.erupt.model.vo.UnreadSummaryVO;

import java.util.List;

/**
 * 即时通信业务。
 *
 * <p>HTTP 接口与 WebSocket 端点共用这里的实现，保证行为一致：
 * 无论从哪条通道进来，都要经历「落库 → 更新会话摘要 → 累加未读 → 推送」同一套动作。</p>
 */
public interface ChatService {

    /** 我的会话列表 */
    List<ConversationVO> listConversations(String userId);

    /** 与某人建立/复用单聊会话 */
    ConversationVO getOrCreateConversation(String userId, String targetUserId);

    /** 历史消息分页（正序返回，最早的在前） */
    PageResult<MessageVO> listMessages(String userId, String conversationId, int page, int pageSize);

    /** 发消息：落库并推送给接收方 */
    MessageVO send(String senderId, SendMessageRequest request);

    /** 标记会话内别人发给我的消息为已读 */
    int markRead(String userId, String conversationId);

    /** 未读概览（按发送人聚合） */
    List<UnreadSummaryVO> unreadSummary(String userId);

    /** 全部会话未读总数（全局角标） */
    int totalUnread(String userId);

    /**
     * 构造给指定用户的推送报文。
     * WebSocket 连接建立时调用，实现"登录后立刻弹提醒"。
     *
     * @return 无未读时返回 null（调用方据此决定是否推送）
     */
    String buildUnreadPushPayload(String userId);

    /** 可发起聊天的用户（排除自己，按关键字过滤） */
    List<java.util.Map<String, Object>> listPartners(String userId, String keyword);

    /**
     * 校验用户能否读取某聊天附件。
     *
     * <p>只允许"引用了该附件的会话的成员"读取。不做成"登录即可读"，
     * 是因为附件 id 一旦泄露（截图、日志、分享链接），
     * 就不能再靠它不可猜来兜底。</p>
     */
    void requireAttachmentReadable(String userId, String attachmentId);

    /**
     * 某用户当前是否在线（至少有一条 WebSocket 连接）。
     */
    boolean isOnline(String userId);

    /**
     * 把某用户的在线状态变化广播给所有与他有单聊关系的人。
     *
     * <p>只在"第一条连接建立"与"最后一条连接断开"时调用——
     * 多标签页同时打开不应让对端收到一串重复的状态通知。</p>
     */
    void broadcastPresence(String userId, boolean online);
}
