package com.example.erupt.controller;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.Result;
import com.example.erupt.model.vo.AgentUsageVO;
import com.example.erupt.service.AgentUsageService;
import com.example.erupt.service.DataScopeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体用量统计接口。
 *
 * <p>挂在 /api/agent 下：URI 第二段是 agent，拦截器按「智能体」菜单鉴权——
 * 能用智能体的人自然有权看自己的用量；页面菜单（m26）用独立 permission，
 * 让超管可以单独控制统计页的可见性。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentUsageController {

    private final AgentUsageService agentUsageService;
    private final DataScopeService dataScope;

    public AgentUsageController(AgentUsageService agentUsageService, DataScopeService dataScope) {
        this.agentUsageService = agentUsageService;
        this.dataScope = dataScope;
    }

    /**
     * 用量统计：scope=me（默认）看自己；scope=all 全员排行（仅超管，其他人静默降级为 me）。
     */
    @GetMapping("/usage")
    public ResponseEntity<Result<AgentUsageVO>> usage(
            @RequestParam(defaultValue = "me") String scope,
            @RequestParam(defaultValue = "30") int trendDays) {
        String userId = AuthContext.currentUserId();
        boolean superAdmin = dataScope.dataScopeOwnerId() == null;
        return ResponseEntity.ok(Result.success(agentUsageService.usage(userId, superAdmin, scope, trendDays)));
    }
}
