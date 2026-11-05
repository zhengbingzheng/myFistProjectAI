package com.example.erupt.service;

import com.example.erupt.common.Result;
import com.example.erupt.model.dto.GanttDependencyRequest;
import com.example.erupt.model.dto.GanttPlanRequest;
import com.example.erupt.model.dto.GanttTaskRequest;
import com.example.erupt.model.vo.GanttDeletedPlanVO;
import com.example.erupt.model.vo.GanttDeletedTaskVO;
import com.example.erupt.model.vo.GanttPlanDetailVO;
import com.example.erupt.model.vo.GanttPlanVO;
import com.example.erupt.model.vo.GanttRestoreVO;

import java.util.List;

/**
 * 时间规划（甘特图）业务接口。
 *
 * <p><b>数据权限</b>：所有方法都按「创建人」隔离——超级管理员可见并操作全部计划，
 * 其他用户只能看到 / 修改自己创建的计划（含其下任务与依赖）。
 * 越权访问返回 403，而不是伪装成「不存在」。</p>
 */
public interface GanttService {

    /** 计划列表，含任务数 / 完成数 / 平均进度；按当前用户的数据范围过滤 */
    List<GanttPlanVO> listPlans(String keyword);

    /** 新建计划，返回带 id 的 VO 供前端直接切换过去 */
    GanttPlanVO createPlan(GanttPlanRequest request);

    Result<Void> updatePlan(String id, GanttPlanRequest request);

    /** 删除计划：级联软删任务、清理依赖 */
    Result<Void> deletePlan(String id);

    /** 甘特图渲染所需的完整数据：计划 + 任务 + 依赖 */
    GanttPlanDetailVO detail(String planId);

    /**
     * 新建任务，返回新任务 id。
     *
     * <p>返回 id 是为了让前端在建完任务后能立刻追加依赖（依赖需要 from/to 两个 id）。
     * 若只返回成功，前端就得重新拉一次详情再靠名字反查，既多一次请求也不可靠。</p>
     */
    Result<String> createTask(String planId, GanttTaskRequest request);

    Result<Void> updateTask(String taskId, GanttTaskRequest request);

    /**
     * 删除任务。
     *
     * <p>{@code cascade = false} 时，任务下有子任务会被拒绝（409），避免子任务变成孤儿挂在画布外；
     * {@code cascade = true} 时连同子任务一并删除。前端在二次确认里明确告知「会一起删掉 N 个子任务」
     * 之后再传 true——把选择权交给用户，而不是让他先逐个删子任务。</p>
     */
    Result<Void> deleteTask(String taskId, boolean cascade);

    /**
     * 回收站：列出计划下处于软删状态的任务（按删除时间倒序）。
     *
     * <p>撤销横条只覆盖「刚删完」的场景，刷新页面就没了；
     * 回收站让删除过的东西随时可查、可恢复，不依赖页面状态。</p>
     */
    Result<List<GanttDeletedTaskVO>> listDeletedTasks(String planId);

    /**
     * 计划级回收站：可见范围内已删的计划列表（按删除时间倒序）。
     *
     * <p>删计划后撤销横条刷新即消失、任务回收站又挂在当前计划下——没有这个入口，
     * 删掉的计划刷新后就真的找不回来了。</p>
     */
    Result<List<GanttDeletedPlanVO>> listDeletedPlans();

    /**
     * 恢复软删的计划：连同其下全部任务与依赖。
     *
     * <p>依赖按「同一对任务只恢复最新一条」处理——历史上反复删建会留下多条墓碑，
     * 全部恢复会撞唯一键；任务没有唯一约束，可以整体恢复。</p>
     */
    Result<GanttRestoreVO> restorePlan(String id);

    /**
     * 恢复软删的任务。恢复是「按家族」的：
     * 分组被删时子任务一起删了，恢复任意成员都把整个家族带回来，
     * 否则恢复一个子任务后它会变成孤儿挂在画布外（顶层脏数据兜底区）。
     * 依赖只恢复两端都处于有效状态的那部分。
     */
    Result<GanttRestoreVO> restoreTask(String id);

    /** 恢复单条软删依赖：要求所属计划与两端任务均为有效状态 */
    Result<GanttRestoreVO> restoreDependency(String id);

    Result<Void> createDependency(GanttDependencyRequest request);

    Result<Void> deleteDependency(String id);
}
