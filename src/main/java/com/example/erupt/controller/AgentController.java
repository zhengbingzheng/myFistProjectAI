package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.AgentChatRequest;
import com.example.erupt.model.vo.AgentChatVO;
import com.example.erupt.model.vo.AgentConversationVO;
import com.example.erupt.model.vo.AgentMessageVO;
import com.example.erupt.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 智能体接口：GLM-5.3 / GLM-5.3-Flash 多轮对话与会话管理。
 *
 * <p>整组挂在 /api/agent 下，走 MenuAccessInterceptor 的通用规则：
 * URI 第二段 agent → 校验菜单权限 {@code agent}（菜单 m23）。
 * 上游配置在数据字典 agent_config 下，见 {@code db/schema-agent.sql}。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 发送一条消息（不传 conversationId = 新建会话），返回助手回复 */
    @PostMapping("/chat")
    public ResponseEntity<Result<AgentChatVO>> chat(@Valid @RequestBody AgentChatRequest request) {
        AgentChatVO reply = agentService.chat(AuthContext.currentUserId(), request);
        return ResponseEntity.ok(Result.success(reply));
    }

    /**
     * 流式对话：SSE 逐块下发正文与思考过程，结束时补一条 {@code done} 事件（含落库后的完整消息）。
     *
     * <p>参数 / 配置错误会在建立流之前以普通 JSON 错误返回（HTTP 4xx），
     * 前端按 !response.ok 分支读 message 即可；流建立后的失败走 {@code error} 事件。</p>
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AgentChatRequest request) {
        return agentService.chatStream(AuthContext.currentUserId(), request);
    }

    /** 当前用户的会话列表（最近对话在前） */
    @GetMapping("/conversations")
    public ResponseEntity<Result<List<AgentConversationVO>>> conversations() {
        return ResponseEntity.ok(Result.success(agentService.myConversations(AuthContext.currentUserId())));
    }

    /** 某会话的全部消息，按时间正序 */
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<Result<List<AgentMessageVO>>> messages(@PathVariable String id) {
        return ResponseEntity.ok(Result.success(agentService.messages(AuthContext.currentUserId(), id)));
    }

    /** 删除会话（软删除，消息随之不可达） */
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Result<Void>> deleteConversation(@PathVariable String id) {
        agentService.deleteConversation(AuthContext.currentUserId(), id);
        return ResponseEntity.ok(Result.success(null, "删除成功"));
    }
}
