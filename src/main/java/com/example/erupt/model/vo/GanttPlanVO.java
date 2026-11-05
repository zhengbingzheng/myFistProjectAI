package com.example.erupt.model.vo;

import java.time.LocalDate;

/**
 * 计划列表项。
 *
 * <p>带上统计字段是为了让列表页直接显示「12 个任务 · 完成 58%」，
 * 不必为每个计划再发一次详情请求。</p>
 *
 * <p>带上 {@code ownerId / ownerName} 是因为数据按创建人隔离：
 * 超级管理员能在列表里看到别人的计划，必须能分辨「这是谁的」，
 * 否则一堆同名排期无从下手。</p>
 *
 * @param id          计划 id
 * @param name        名称
 * @param description 说明
 * @param startDate   起始日
 * @param endDate     结束日
 * @param status      active / archived
 * @param taskCount   未删除的任务数（不含里程碑）
 * @param milestoneCount 里程碑数
 * @param doneCount   状态为 done 的任务数
 * @param avgProgress 任务进度平均值（0-100，无任务时为 0）
 * @param ownerId     创建人主键
 * @param ownerName   创建人显示名（昵称优先，回落账号）
 * @param owned       是否由当前登录用户创建。由服务端算好，前端不必再比对
 *                    「我的 id」——前端未取到用户信息时（如本地 mock 环境）会误判成「都是别人的」
 */
public record GanttPlanVO(
        String id,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        int taskCount,
        int milestoneCount,
        int doneCount,
        int avgProgress,
        String ownerId,
        String ownerName,
        boolean owned
) {
}
