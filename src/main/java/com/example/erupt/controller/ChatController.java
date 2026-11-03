package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.SendMessageRequest;
import com.example.erupt.model.vo.ConversationVO;
import com.example.erupt.model.vo.MessageVO;
import com.example.erupt.model.vo.UnreadSummaryVO;
import com.example.erupt.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 即时通信接口（视图层）。
 *
 * <p><b>HTTP 与 WebSocket 的分工</b>：
 * <ul>
 *   <li>HTTP 负责「查」与「写」：会话列表、历史消息、发送消息、已读回执；</li>
 *   <li>WebSocket 只负责「推」：把别人发来的新消息即时送到在线的另一端。</li>
 * </ul>
 * 发送走 HTTP 而不是 WebSocket，是因为 HTTP 自带鉴权拦截器、异常转译与事务边界，
 * 写操作放这里能复用整套既有设施；WebSocket 一旦也要做这些，等于重写一遍过滤器链。</p>
 */
@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 我的会话列表 */
    @GetMapping("/conversations")
    public ResponseEntity<Result<List<ConversationVO>>> conversations() {
        return ok(chatService.listConversations(AuthContext.currentUserId()));
    }

    /** 与指定用户建立/复用单聊会话 */
    @PostMapping("/conversations/with/{userId}")
    public ResponseEntity<Result<ConversationVO>> openWith(@PathVariable String userId) {
        ConversationVO conversation = chatService.getOrCreateConversation(AuthContext.currentUserId(), userId);
        return ResponseEntity.ok(Result.success(conversation));
    }

    /** 历史消息分页 */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<Result<PageResult<MessageVO>>> messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        PageResult<MessageVO> result = chatService.listMessages(
                AuthContext.currentUserId(), conversationId,
                page == null ? 1 : page, pageSize == null ? 20 : pageSize);
        return ResponseEntity.ok(Result.success(result));
    }

    /** 发消息。落库后由 WebSocket 推送给接收方 */
    @PostMapping("/messages")
    public ResponseEntity<Result<MessageVO>> send(@Valid @RequestBody SendMessageRequest request) {
        MessageVO message = chatService.send(AuthContext.currentUserId(), request);
        return ResponseEntity.ok(Result.success(message));
    }

    /** 标记会话已读 */
    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Result<Integer>> markRead(@PathVariable String conversationId) {
        int updated = chatService.markRead(AuthContext.currentUserId(), conversationId);
        return ResponseEntity.ok(Result.success(updated));
    }

    /** 未读概览（按发送人聚合），供页面刷新/轮询兜底 */
    @GetMapping("/unread")
    public ResponseEntity<Result<List<UnreadSummaryVO>>> unread() {
        return ok(chatService.unreadSummary(AuthContext.currentUserId()));
    }

    /** 未读总数（全局红点） */
    @GetMapping("/unread/total")
    public ResponseEntity<Result<Integer>> unreadTotal() {
        return ResponseEntity.ok(Result.success(chatService.totalUnread(AuthContext.currentUserId())));
    }

    /** 可发起聊天的用户列表 */
    @GetMapping("/partners")
    public ResponseEntity<Result<List<Map<String, Object>>>> partners(
            @RequestParam(required = false) String keyword) {
        List<Map<String, Object>> partners = chatService.listPartners(AuthContext.currentUserId(), keyword);
        return ResponseEntity.ok(Result.success(partners));
    }

    private <T> ResponseEntity<Result<List<T>>> ok(List<T> data) {
        return ResponseEntity.ok(Result.success(data));
    }
}
