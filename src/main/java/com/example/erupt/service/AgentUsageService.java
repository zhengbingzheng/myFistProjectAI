package com.example.erupt.service;

import com.example.erupt.model.vo.AgentUsageVO;

/**
 * 智能体用量统计：聚合 t_agent_message 的 token / 耗时消耗。
 *
 * <p>数据范围与智能体一致：普通用户只能看到自己的消耗（API Key 虽是公用的，
 * 但别人的用量属于他人隐私）；超管可看全员排行——公用额度谁来买单，得有人看得见。</p>
 */
public interface AgentUsageService {

    /**
     * 用量统计。
     *
     * @param scope me = 当前用户；all = 全员（仅超管，非超管传 all 按 me 处理）
     */
    AgentUsageVO usage(String userId, boolean superAdmin, String scope, int trendDays);
}
