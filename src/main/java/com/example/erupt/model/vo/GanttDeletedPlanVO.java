package com.example.erupt.model.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 回收站条目：处于软删状态的计划。
 *
 * <p>之前回收站只查任务，删掉计划后刷新页面就没有任何恢复入口——
 * 撤销横条是内存态，任务回收站又挂在「当前计划」下，计划本身删了就进不去。</p>
 *
 * @param id              计划 id
 * @param name            计划名称
 * @param startDate       计划开始日
 * @param endDate         计划结束日
 * @param deletedTaskCount 其下已删的任务数（含各级），恢复计划会全部带回
 * @param deletedBy       删除人
 * @param deletedTime     删除时间
 */
public record GanttDeletedPlanVO(
        String id,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        int deletedTaskCount,
        String deletedBy,
        LocalDateTime deletedTime
) {
}
