package com.example.erupt.model.vo;

import java.time.LocalDate;

/**
 * 任务出参。
 *
 * @param id          任务 id
 * @param planId      所属计划
 * @param parentId    父任务 id，null 表示顶层
 * @param name        任务名称
 * @param description 任务说明
 * @param startDate   开始日
 * @param endDate     结束日
 * @param progress    完成度 0-100（分组任务为子任务汇总值）
 * @param status      todo / doing / done / blocked
 * @param assignee    负责人
 * @param priority    1 高 / 2 中 / 3 低
 * @param milestone   是否里程碑
 * @param color       条形色值
 * @param sortNum     同层排序
 * @param durationDays 工期天数（含首尾，里程碑为 1）
 */
public record GanttTaskVO(
        String id,
        String planId,
        String parentId,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        Integer progress,
        String status,
        String assignee,
        Integer priority,
        boolean milestone,
        String color,
        Integer sortNum,
        int durationDays
) {
}
