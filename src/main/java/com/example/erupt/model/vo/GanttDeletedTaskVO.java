package com.example.erupt.model.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回收站条目：计划下处于软删状态的任务。
 *
 * <p>childCount 只对分组任务有意义：恢复分组会按「家族」把已删子任务一起带回来，
 * 列表里提前报出数量，用户才知道一次恢复会影响几行。</p>
 *
 * @param id          任务 id
 * @param name        任务名称
 * @param parentId    父任务 id，null 表示顶层
 * @param milestone   是否里程碑
 * @param startDate   开始日
 * @param endDate     结束日
 * @param deletedBy   删除人（软删时写入 update_by）
 * @param deletedTime 删除时间（软删时写入 update_time）
 * @param childCount  该分组下已删的子任务数，非分组为 0
 */
public record GanttDeletedTaskVO(
        String id,
        String name,
        String parentId,
        boolean milestone,
        LocalDate startDate,
        LocalDate endDate,
        String deletedBy,
        LocalDateTime deletedTime,
        int childCount
) {
}
