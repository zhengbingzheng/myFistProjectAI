package com.example.erupt.model.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 计划的新增 / 修改入参。
 *
 * <p>除长度外不做 {@code @NotNull} 校验：新增与修改共用同一个 record，
 * 修改时字段为 null 表示「不更新」。哪些字段在新增时必填，由服务层判定，
 * 否则「只想改个名字」也会因为没传日期被 400 拦下。</p>
 *
 * @param name        计划名称
 * @param description 计划说明
 * @param startDate   起始日
 * @param endDate     结束日
 * @param status      active / archived
 */
public record GanttPlanRequest(
        @Size(max = 100, message = "计划名称不能超过 100 个字符")
        String name,

        @Size(max = 500, message = "计划说明不能超过 500 个字符")
        String description,

        LocalDate startDate,

        LocalDate endDate,

        String status
) {
}
