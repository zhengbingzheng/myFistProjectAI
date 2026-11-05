package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建任务依赖的入参。
 *
 * @param fromTaskId 前置任务 id（必须早于 / 同步于 to 任务，由 depType 决定）
 * @param toTaskId   后置任务 id
 * @param type       依赖类型 FS / SS / FF / SF，空则默认 FS
 * @param lagDays    滞后天数，可为负
 */
public record GanttDependencyRequest(
        @NotBlank(message = "前置任务不能为空")
        String fromTaskId,

        @NotBlank(message = "后置任务不能为空")
        String toTaskId,

        @Size(max = 4, message = "依赖类型非法")
        String type,

        Integer lagDays
) {
}
