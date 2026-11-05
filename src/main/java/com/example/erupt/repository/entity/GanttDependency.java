package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 时间规划-任务依赖实体，对应库表 {@code t_gantt_dependency}。
 *
 * <p>方向固定为 {@code fromTaskId → toTaskId}，含义由 {@code depType} 决定：</p>
 * <ul>
 *   <li>FS 完成-开始：前置完成后后置才能开始（默认，最常用）</li>
 *   <li>SS 开始-开始：前置开始后后置才能开始（可并行）</li>
 *   <li>FF 完成-完成：前置完成后后置才能完成</li>
 *   <li>SF 开始-完成：前置开始后后置才能完成</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GanttDependency {

    private String id;
    private String planId;
    /** 前置任务 id */
    private String fromTaskId;
    /** 后置任务 id */
    private String toTaskId;
    /** FS / SS / FF / SF */
    private String depType;
    /** 滞后天数，可为负（表示可重叠） */
    private Integer lagDays;
    private String createBy;
    private LocalDateTime createTime;
}
