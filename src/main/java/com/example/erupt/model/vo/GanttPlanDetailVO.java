package com.example.erupt.model.vo;

import java.util.List;

/**
 * 甘特图一次渲染所需的全部数据。
 *
 * <p>刻意做成一个聚合接口而不是「先查计划、再查任务、再查依赖」三个请求：
 * 甘特图上任务与依赖必须同时到位才能画线，拆开会让首屏出现「条子在、线还没到」的闪烁，
 * 移动端还要多付两次往返延迟。</p>
 *
 * @param plan         计划本身（含统计）
 * @param tasks        全部任务，已按父任务 → 同层 sortNum 排序
 * @param dependencies 全部依赖
 */
public record GanttPlanDetailVO(
        GanttPlanVO plan,
        List<GanttTaskVO> tasks,
        List<GanttDependencyVO> dependencies
) {
}
