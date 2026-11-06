package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.vo.AskDataVO;
import com.example.erupt.service.AgentDataService;
import com.example.erupt.service.DataScopeService;
import com.example.erupt.service.MenuService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * AI 读库接口（Text-to-SQL 只读版）。
 *
 * <p>挂在 /api/agent 下（URI 第二段 = agent，按「智能体」菜单鉴权）；
 * 页面菜单 m27 用独立 permission ai-data，超管可单独控制该入口的可见性。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentDataController {

    /** 提问请求体 */
    public record AskDataRequest(
            @NotBlank(message = "问题不能为空")
            @Size(max = 500, message = "问题最多 500 字")
            String question) {
    }

    private final AgentDataService agentDataService;
    private final DataScopeService dataScope;
    private final MenuService menuService;

    public AgentDataController(AgentDataService agentDataService, DataScopeService dataScope,
                               MenuService menuService) {
        this.agentDataService = agentDataService;
        this.dataScope = dataScope;
        this.menuService = menuService;
    }

    /**
     * 自然语言问数据。
     *
     * <p>页面菜单 m27 的权限标识是 {@code ai-data}，但 URI 挂在 {@code /api/agent} 下，
     * 菜单拦截器只认第二段 {@code agent}。这里再查一次 {@code ai-data}，
     * 关掉智能体页面并不等于还能问数。模型调用放到 MVC 异步线程，避免占着 Tomcat 工作线程干等。</p>
     */
    @PostMapping("/ask-data")
    public Callable<ResponseEntity<Result<AskDataVO>>> askData(@RequestBody AskDataRequest request) {
        String userId = AuthContext.currentUserId();
        if (userId == null || !menuService.hasPermission(userId, "ai-data")) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权限使用 AI 读库");
        }
        boolean superAdmin = dataScope.dataScopeOwnerId() == null;
        String question = request.question();
        return () -> ResponseEntity.ok(Result.success(agentDataService.ask(userId, question, superAdmin)));
    }

    /**
     * 审计快照分页（仅超管）：谁问了什么、AI 生成了什么 SQL、结果多少行、成功与否。
     * 普通用户的提问不暴露给他人——别人的问题内容属于他人隐私，只有审计员（超管）能看全貌。
     */
    @GetMapping("/data-audit")
    public ResponseEntity<Result<Map<String, Object>>> auditPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (dataScope.dataScopeOwnerId() != null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅超级管理员可查看 AI 读库审计");
        }
        return ResponseEntity.ok(Result.success(agentDataService.auditPage(page, pageSize)));
    }
}
