package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 时间规划-任务实体，对应库表 {@code t_gantt_task}。
 *
 * <p>三种形态共用一张表：</p>
 * <ul>
 *   <li>普通任务：{@code isMilestone = 0}，起止可跨多天；</li>
 *   <li>里程碑（时间节点）：{@code isMilestone = 1}，服务层强制起止同一天；</li>
 *   <li>分组任务：{@code parentId} 为空、且有子任务指向它；进度由子任务汇总，不允许直接编辑进度。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GanttTask {

    private String id;
    /** 所属计划，关联 t_gantt_plan.id */
    private String planId;
    /** 父任务 id；null 表示顶层。最多三层嵌套（见服务层 MAX_TASK_DEPTH） */
    private String parentId;
    private String taskName;
    private String taskDesc;
    private LocalDate startDate;
    private LocalDate endDate;
    /** 完成度 0-100 */
    private Integer progress;
    /** 状态：todo / doing / done / blocked */
    private String status;
    /** 负责人姓名（自由文本） */
    private String assignee;
    /** 优先级：1 高 / 2 中 / 3 低 */
    private Integer priority;
    /** 1 = 里程碑 */
    private Integer isMilestone;
    /** 自定义色值，空则按状态取默认色 */
    private String color;
    /** 同层排序号 */
    private Integer sortNum;
    private String delFlag;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
