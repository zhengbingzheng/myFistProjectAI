package com.example.erupt.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 任务的新增 / 修改入参。
 *
 * <p>跨字段规则（起止先后、里程碑必须同一天、进度范围）在服务层校验：
 * 这些规则依赖数据库中的既有值（修改时可能只传一端），注解表达不了。</p>
 *
 * @param name        任务名称
 * @param description 任务说明
 * @param parentId    父任务 id，null / 空串表示顶层
 * @param startDate   开始日
 * @param endDate     结束日
 * @param progress    完成度 0-100
 * @param status      todo / doing / done / blocked
 * @param assignee    负责人
 * @param priority    1 高 / 2 中 / 3 低
 * @param milestone   是否里程碑（true 时起止必须同一天）
 * @param color       条形色值，如 #2563eb
 * @param sortNum     同层排序
 */
public record GanttTaskRequest(
        @Size(max = 120, message = "任务名称不能超过 120 个字符")
        String name,

        @Size(max = 500, message = "任务说明不能超过 500 个字符")
        String description,

        String parentId,

        LocalDate startDate,

        LocalDate endDate,

        @Min(value = 0, message = "进度不能小于 0")
        @Max(value = 100, message = "进度不能大于 100")
        Integer progress,

        String status,

        @Size(max = 40, message = "负责人不能超过 40 个字符")
        String assignee,

        @Min(value = 1, message = "优先级取值为 1-3")
        @Max(value = 3, message = "优先级取值为 1-3")
        Integer priority,

        Boolean milestone,

        @Size(max = 20, message = "色值不能超过 20 个字符")
        String color,

        Integer sortNum
) {
}
