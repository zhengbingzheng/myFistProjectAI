package com.example.erupt.service.impl;

import com.example.erupt.common.JsonSupport;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.SendMessageRequest;
import com.example.erupt.model.vo.ChatAttachmentVO;
import com.example.erupt.model.vo.ConversationVO;
import com.example.erupt.model.vo.MessageVO;
import com.example.erupt.model.vo.UnreadSummaryVO;
import com.example.erupt.repository.entity.ChatConversation;
import com.example.erupt.repository.entity.ChatMember;
import com.example.erupt.repository.entity.ChatAttachment;
import com.example.erupt.repository.entity.ChatMessage;
import com.example.erupt.repository.mapper.ChatMapper;
import com.example.erupt.repository.mapper.UserMapper;
import com.example.erupt.service.ChatAttachmentService;
import com.example.erupt.service.ChatService;
import com.example.erupt.websocket.ChatSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 即时通信业务实现。
 *
 * <p><b>推送必须在事务提交之后</b>：见 {@link #afterCommit(Runnable)}。
 * 这一点很关键——如果在本事务内推送，接收方客户端收到通知后会立刻拉取未读列表，
 * 而此时消息尚未提交，对方会看到"收到提醒但没有新消息"的诡异状态。</p>
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final int TYPE_TEXT = 1;
    /** 图片消息：content 存占位文案，实际内容在附件里 */
    private static final int TYPE_IMAGE = 2;
    /** 文件消息：非图片附件一律按此类型发送 */
    private static final int TYPE_FILE = 3;
    private static final int CONV_SINGLE = 1;

    /** 同上：不依赖容器注入 ObjectMapper */
    private static final ObjectMapper OBJECT_MAPPER = JsonSupport.MAPPER;

    private final ChatMapper chatMapper;
    private final UserMapper userMapper;
    private final ChatSessionRegistry registry;
    private final ChatAttachmentService chatAttachmentService;

    public ChatServiceImpl(ChatMapper chatMapper,
                           UserMapper userMapper,
                           ChatSessionRegistry registry,
                           ChatAttachmentService chatAttachmentService) {
        this.chatMapper = chatMapper;
        this.userMapper = userMapper;
        this.registry = registry;
        this.chatAttachmentService = chatAttachmentService;
    }

    @Override
    public List<ConversationVO> listConversations(String userId) {
        List<Map<String, Object>> rows = chatMapper.selectConversationsByUser(userId);
        List<ConversationVO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new ConversationVO(
                    str(row.get("id")),
                    intValue(row.get("convType")),
                    str(row.get("convName")),
                    str(row.get("convAvatar")),
                    str(row.get("lastContent")),
                    intValue(row.get("lastMsgType")),
                    time(row.get("lastMsgTime")),
                    intValue(row.get("unreadCount")),
                    str(row.get("peerId")),
                    str(row.get("peerName")),
                    str(row.get("peerAvatar")),
                    str(row.get("peerUsername")),
                    isPeerOnline(str(row.get("peerId")))));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO getOrCreateConversation(String userId, String targetUserId) {
        var target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        String bizKey = bizKey(userId, targetUserId);
        ChatConversation existing = chatMapper.selectConversationByBizKey(bizKey);
        if (existing != null) {
            return toConversationVO(existing, userId);
        }
        String convId = newId();
        ChatConversation conv = new ChatConversation();
        conv.setId(convId);
        conv.setConvType(CONV_SINGLE);
        conv.setBizKey(bizKey);
        conv.setOwnerId(userId);
        conv.setDelFlag("0");
        chatMapper.insertConversation(conv);

        LocalDateTime now = LocalDateTime.now();
        chatMapper.insertMember(member(convId, userId, 1));
        chatMapper.insertMember(member(convId, targetUserId, 1));

        var created = chatMapper.selectConversationById(convId);
        return toConversationVO(created, userId);
    }

    @Override
    public PageResult<MessageVO> listMessages(String userId, String conversationId, int page, int pageSize) {
        requireMember(userId, conversationId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long total = chatMapper.countMessages(conversationId);
        if (total == 0) {
            return PageResult.of(List.of(), 0L, safePage, safeSize);
        }
        long offset = (long) (safePage - 1) * safeSize;
        // SQL 按时间倒序取当前页，这里反转成正序（最早的在前），符合聊天窗口的阅读习惯
        List<Map<String, Object>> rows = chatMapper.selectMessages(conversationId, offset, safeSize);
        List<MessageVO> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(new MessageVO(
                    str(row.get("id")),
                    str(row.get("conversationId")),
                    str(row.get("senderId")),
                    str(row.get("senderName")),
                    str(row.get("senderAvatar")),
                    intValue(row.get("msgType")),
                    str(row.get("content")),
                    intValue(row.get("readFlag")),
                    time(row.get("createTime")),
                    userId.equals(str(row.get("senderId"))),
                    attachmentOf(row)));
        }
        items.sort(Comparator.comparing(MessageVO::createTime,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return PageResult.of(items, total, safePage, safeSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageVO send(String senderId, SendMessageRequest request) {
        String conversationId = resolveConversation(senderId, request);

        String attachmentId = blankToNull(request.attachmentId());
        ChatAttachmentVO attachment = attachmentId == null ? null : requireAttachment(attachmentId, senderId);
        String text = blankToNull(request.content());

        int msgType = request.msgType() == null ? inferMsgType(attachment) : request.msgType();

        // 先判"声明了类型却没给对应内容"，提示才说得具体。
        // 若反过来先判"空消息"，前端声明 msgType=2 却忘了附件时只会得到
        // "消息内容不能为空"，排查方向会被带偏。
        if (msgType == TYPE_IMAGE && attachment == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片消息缺少附件");
        }

        // 文本消息必须"有话可说"；图片/文件消息允许只带附件
        if (text == null && attachment == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息内容不能为空");
        }
        // 非文本消息的 content 不作展示主体，但要留给会话列表做"最近一条"预览：
        // 留空的话列表上是一行空白，看起来像消息丢了。
        String content = text != null ? text : previewTextOf(msgType);

        ChatMessage message = new ChatMessage();
        message.setId(newId());
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setReceiverId(receiverOf(conversationId, senderId));
        message.setMsgType(msgType);
        message.setContent(content);
        message.setAttachmentId(attachmentId);
        message.setReadFlag(0);
        message.setDelFlag("0");
        chatMapper.insertMessage(message);

        LocalDateTime now = LocalDateTime.now();
        chatMapper.updateConversationLastMsg(conversationId, message.getId(), now);
        String receiverId = message.getReceiverId();
        if (receiverId != null) {
            chatMapper.incrMemberUnread(conversationId, receiverId);
        }

        // 事务提交后再推：把推送动作挂到当前事务的 afterCommit 回调上
        afterCommit(() -> pushUnread(receiverId));

        return new MessageVO(message.getId(), conversationId, senderId,
                null, null, msgType, content, 0, now, true, attachment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markRead(String userId, String conversationId) {
        requireMember(userId, conversationId);
        int updated = chatMapper.markMessagesRead(conversationId, userId);
        chatMapper.clearMemberUnread(conversationId, userId, null);
        if (updated > 0) {
            afterCommit(() -> pushUnread(userId));
        }
        return updated;
    }

    @Override
    public List<UnreadSummaryVO> unreadSummary(String userId) {
        List<Map<String, Object>> rows = chatMapper.selectUnreadGroupBySender(userId);
        List<UnreadSummaryVO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new UnreadSummaryVO(
                    str(row.get("senderId")),
                    str(row.get("senderName")),
                    str(row.get("senderAvatar")),
                    intValue(row.get("unreadCount")),
                    str(row.get("lastContent")),
                    time(row.get("lastTime")),
                    str(row.get("conversationId"))));
        }
        return result;
    }

    @Override
    public int totalUnread(String userId) {
        return chatMapper.sumUnreadByUser(userId);
    }

    @Override
    public String buildUnreadPushPayload(String userId) {
        List<UnreadSummaryVO> items = unreadSummary(userId);
        int total = totalUnread(userId);
        if (total <= 0 && items.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "type", "unread",
                    "total", total,
                    "items", items);
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("构造未读推送失败 userId={} 原因={}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> listPartners(String userId, String keyword) {
        // 复用既有用户查询能力；这里排除自己，避免"给自己发消息"这种无意义会话。
        // keyword 走 LIKE，% 和 _ 必须先转义：否则搜 "%" 会退化成全表匹配，
        // 看起来"搜到了所有人"，实际是通配符在起作用。空白关键字按"不带条件"处理。
        String escaped = (keyword == null || keyword.isBlank())
                ? null
                : LikeEscape.escape(keyword.trim());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var user : userMapper.selectByCondition(escaped, 1, 0, 50)) {
            if (userId.equals(user.getId())) {
                continue;
            }
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("nickname", user.getNickname());
            item.put("avatar", user.getAvatar());
            result.add(item);
        }
        return result;
    }

    /* ---------- 内部方法 ---------- */

    @Override
    public boolean isOnline(String userId) {
        return userId != null && !userId.isBlank() && registry.isOnline(userId);
    }

    @Override
    public void broadcastPresence(String userId, boolean online) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String payload;
        try {
            payload = OBJECT_MAPPER.writeValueAsString(
                    Map.of("type", "presence", "userId", userId, "online", online));
        } catch (Exception e) {
            log.warn("构造在线状态推送失败 userId={} 原因={}", userId, e.getMessage());
            return;
        }
        int delivered = 0;
        // 复用"我的会话列表"来找出全部单聊对端，省一条专用 SQL；
        // 群聊的 peerId 为空，不参与一对一在线状态
        for (Map<String, Object> row : chatMapper.selectConversationsByUser(userId)) {
            String peerId = str(row.get("peerId"));
            if (peerId != null && !peerId.isBlank()) {
                delivered += registry.sendToUser(peerId, payload);
            }
        }
        log.info("广播在线状态 userId={} online={} 触达连接数={}", userId, online, delivered);
    }

    /** 单聊对方是否在线；群聊或对方为空时返回 null（表示"不适用"而非"离线"） */
    private Boolean isPeerOnline(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return null;
        }
        return registry.isOnline(peerId);
    }

    /** 向指定用户推送未读概览（JSON 报文） */
    private void pushUnread(String userId) {
        if (userId == null) {
            return;
        }
        String payload = buildUnreadPushPayload(userId);
        if (payload == null) {
            return;
        }
        int delivered = registry.sendToUser(userId, payload);
        log.debug("推送未读概览 userId={} 送达连接数={}", userId, delivered);
    }

    /**
     * 把动作挂到当前事务的提交后回调。
     * 没有事务时直接执行——WebSocket 上线推送等非事务场景也会走到这里。
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

    /** 定位会话：优先用请求里的 conversationId，否则按单聊双方重建 */
    @Override
    public void requireAttachmentReadable(String userId, String attachmentId) {
        ChatAttachment attachment = chatAttachmentService.getById(attachmentId);
        if (attachment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在或已被删除");
        }
        // 上传者本人可读：刚上传、消息还没发出去时，前端要能预览自己选的图
        if (userId.equals(attachment.getUploaderId())) {
            return;
        }
        String conversationId = chatMapper.selectConversationIdByAttachment(attachmentId);
        if (conversationId == null) {
            // 尚未发送的附件，除上传者外不对外开放
            throw new BusinessException(ResultCode.NOT_FOUND, "附件尚未发送");
        }
        requireMember(userId, conversationId);
    }

    /**
     * 校验附件存在、且属于发送者本人，并取出展示所需信息。
     *
     * <p>归属校验不能省：读取权限是按"引用了该附件的会话成员"判定的，
     * 若允许随意引用别人的附件 id，拿到一个 id 就能把自己的会话当跳板读走它，
     * 那条按会话判定读权限的规则就形同虚设。</p>
     */
    private ChatAttachmentVO requireAttachment(String attachmentId, String senderId) {
        ChatAttachment attachment = chatAttachmentService.getById(attachmentId);
        if (attachment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "附件不存在或已被删除");
        }
        // 归属校验不能省：读权限是按"引用了该附件的会话成员"判定的，
        // 若允许随意引用别人的附件 id，拿到一个 id 就能把自己的会话当跳板读走它。
        // 这里直接比用户ID，不必再按用户名反查——上传时就把 uploader_id 落了库。
        if (!senderId.equals(attachment.getUploaderId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能发送自己上传的附件");
        }
        return new ChatAttachmentVO(attachment.getId(), attachment.getOriginalName(),
                attachment.getFileSize(), attachment.getContentType());
    }

    /** 未显式指定消息类型时按附件 MIME 推断；没有附件就是文本 */
    private static int inferMsgType(ChatAttachmentVO attachment) {
        if (attachment == null) {
            return TYPE_TEXT;
        }
        String contentType = attachment.contentType();
        return contentType != null && contentType.startsWith("image/") ? TYPE_IMAGE : TYPE_FILE;
    }

    /** 非文本消息在会话列表里的占位文案 */
    private static String previewTextOf(int msgType) {
        return switch (msgType) {
            case TYPE_IMAGE -> "[图片]";
            case TYPE_FILE -> "[文件]";
            default -> "";
        };
    }

    /** 从查询行还原附件信息；文本消息 attachmentId 为空，返回 null 供前端判断 */
    private static ChatAttachmentVO attachmentOf(Map<String, Object> row) {
        String attachmentId = str(row.get("attachmentId"));
        if (attachmentId == null || attachmentId.isBlank()) {
            return null;
        }
        return new ChatAttachmentVO(attachmentId, str(row.get("attachmentName")),
                longValue(row.get("attachmentSize")), str(row.get("attachmentContentType")));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveConversation(String senderId, SendMessageRequest request) {
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            String conversationId = request.conversationId();
            requireMember(senderId, conversationId);
            return conversationId;
        }
        if (request.receiverId() == null || request.receiverId().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少接收人");
        }
        return getOrCreateConversation(senderId, request.receiverId()).id();
    }

    /** 取会话中"不是我"的那个成员作为接收人 */
    private String receiverOf(String conversationId, String senderId) {
        List<ChatMember> members = chatMapper.selectMembersByConversation(conversationId);
        return members.stream()
                .filter(m -> !senderId.equals(m.getUserId()))
                .map(ChatMember::getUserId)
                .findFirst()
                .orElse(null);
    }

    /** 校验我确实是该会话成员——防止通过伪造 conversationId 读取他人聊天记录 */
    private void requireMember(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话不存在");
        }
        ChatMember membership = chatMapper.selectMember(conversationId, userId);
        if (membership == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
        }
    }

    private ConversationVO toConversationVO(ChatConversation conv, String userId) {
        List<ChatMember> members = chatMapper.selectMembersByConversation(conv.getId());
        String peerId = members.stream()
                .map(ChatMember::getUserId)
                .filter(id -> !userId.equals(id))
                .findFirst()
                .orElse(null);
        String peerName = null;
        String peerAvatar = null;
        String peerUsername = null;
        if (peerId != null) {
            var peer = userMapper.selectById(peerId);
            if (peer != null) {
                peerName = peer.getNickname();
                peerAvatar = peer.getAvatar();
                peerUsername = peer.getUsername();
            }
        }
        ChatMember mine = chatMapper.selectMember(conv.getId(), userId);
        return new ConversationVO(conv.getId(), conv.getConvType(), conv.getConvName(),
                conv.getConvAvatar(), null, null, conv.getLastMsgTime(),
                mine == null ? 0 : mine.getUnreadCount(),
                peerId, peerName, peerAvatar, peerUsername, isPeerOnline(peerId));
    }

    /** 单聊 bizKey：双方ID升序拼接，保证 A→B 与 B→A 得到同一个 key */
    private static String bizKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "_" + b : b + "_" + a;
    }

    private static ChatMember member(String convId, String userId, int role) {
        ChatMember member = new ChatMember();
        member.setId(newId());
        member.setConversationId(convId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setUnreadCount(0);
        member.setJoinTime(LocalDateTime.now());
        member.setDelFlag("0");
        return member;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime t) {
            return t;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
