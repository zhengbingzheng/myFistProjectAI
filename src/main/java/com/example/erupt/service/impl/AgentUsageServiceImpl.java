package com.example.erupt.service.impl;

import com.example.erupt.model.vo.AgentUsageVO;
import com.example.erupt.repository.mapper.AgentUsageMapper;
import com.example.erupt.service.AgentUsageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计实现：四条聚合查询拼装。
 *
 * <p>近 30 天趋势里无消耗的天补零——图表需要连续的时间轴，
 * 否则「三天没用」会在折线上看起来像「只有三天」。</p>
 */
@Service
public class AgentUsageServiceImpl implements AgentUsageService {

    /** 趋势窗口：30 天足够看出节奏，窗口再大查询成本与可读性都会变差 */
    private static final int DEFAULT_TREND_DAYS = 30;

    private final AgentUsageMapper agentUsageMapper;

    public AgentUsageServiceImpl(AgentUsageMapper agentUsageMapper) {
        this.agentUsageMapper = agentUsageMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public AgentUsageVO usage(String userId, boolean superAdmin, String scope, int trendDays) {
        // scope=all 仅超管生效：普通用户传 all 也静默降级为自己的数据，而不是报错
        boolean allScope = superAdmin && "all".equalsIgnoreCase(scope);
        String ownerId = allScope ? null : userId;
        int days = trendDays <= 0 ? DEFAULT_TREND_DAYS : Math.min(trendDays, 90);

        List<AgentUsageVO.DayRow> rawDays = agentUsageMapper.selectByDay(ownerId, days);
        return new AgentUsageVO(
                agentUsageMapper.selectSummary(ownerId),
                agentUsageMapper.selectByModel(ownerId),
                fillMissingDays(rawDays, days),
                allScope ? agentUsageMapper.selectByUser(null) : List.of(),
                superAdmin);
    }

    /**
     * 补齐缺失日期（补零）。
     * 用 Map 反查而不是逐日扫列表：30 天窗口 × 每日 O(1)，避免 O(n²)。
     */
    private static List<AgentUsageVO.DayRow> fillMissingDays(List<AgentUsageVO.DayRow> rows, int days) {
        Map<String, AgentUsageVO.DayRow> byDay = new HashMap<>();
        for (AgentUsageVO.DayRow row : rows) {
            byDay.put(row.day(), row);
        }
        List<AgentUsageVO.DayRow> result = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            // MySQL 的 DATE_SUB(CURDATE(), INTERVAL n DAY) 与 java.time 的减法一致，按天回退
            String day = java.time.LocalDate.now().minusDays(i).toString();
            AgentUsageVO.DayRow row = byDay.get(day);
            result.add(row != null ? row : new AgentUsageVO.DayRow(day, 0, 0, 0));
        }
        return result;
    }
}
