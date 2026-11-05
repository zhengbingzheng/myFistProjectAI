package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 时间规划-计划实体，对应库表 {@code t_gantt_plan}。
 *
 * <p>一张计划 = 一张甘特图。任务与依赖都挂在 plan_id 下，
 * 删除计划时连同其任务与依赖一起软删。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GanttPlan {

    /** 主键，32 位字符串（应用层 UUID 去横线） */
    private String id;
    /** 计划名称 */
    private String planName;
    /** 计划说明 */
    private String planDesc;
    /** 计划起始日（仅作初始视图范围，任务允许超出） */
    private LocalDate startDate;
    /** 计划结束日 */
    private LocalDate endDate;
    /** 状态：active 进行中 / archived 已归档 */
    private String status;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    /** 创建人 ID（t_user.id） */
    private String createBy;
    /** 最后修改人 ID */
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
