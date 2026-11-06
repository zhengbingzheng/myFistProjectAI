package com.example.erupt.model.vo;

import java.util.List;

/**
 * 智能体用量统计。
 *
 * <p>数据源是 t_agent_message 的 assistant 行（每次真实上游调用一行），
 * 已删除会话的消息也计入——token 是真实消耗掉的，「用量」不该随会话删除而消失。</p>
 */
public record AgentUsageVO(
        Summary summary,
        List<ModelRow> byModel,
        List<DayRow> byDay,
        List<UserRow> byUser,
        /** 是否可以查看全员视角（超管）：前端据此显示「我的用量 / 全员」切换 */
        boolean canViewAll) {

    /** 汇总：会话数 / 回复数 / token 明细 / 累计生成耗时 */
    public record Summary(
            long conversations,
            long messages,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long costMs) {
    }

    /** 按模型分布：哪个模型烧掉了多少额度 */
    public record ModelRow(
            String model,
            long messages,
            long promptTokens,
            long completionTokens,
            long costMs) {
    }

    /** 按天趋势（近 30 天）：看消耗节奏、定位异常增长 */
    public record DayRow(
            String day,
            long messages,
            long promptTokens,
            long completionTokens) {
    }

    /** 按用户排行：仅超管视角返回（API Key 是公用的，谁用得多一目了然） */
    public record UserRow(
            String userId,
            String nickname,
            String username,
            long messages,
            long promptTokens,
            long completionTokens,
            long costMs) {
    }
}
