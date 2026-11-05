package com.example.erupt.model.vo;

/**
 * 依赖出参，前端据此在甘特图上画箭头。
 *
 * @param id         依赖 id
 * @param fromTaskId 前置任务
 * @param toTaskId   后置任务
 * @param type       FS / SS / FF / SF
 * @param lagDays    滞后天数
 */
public record GanttDependencyVO(
        String id,
        String fromTaskId,
        String toTaskId,
        String type,
        Integer lagDays
) {
}
