package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.GanttDependency;
import com.example.erupt.repository.entity.GanttPlan;
import com.example.erupt.repository.entity.GanttTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 时间规划数据访问接口，对应 {@code t_gantt_plan} / {@code t_gantt_task} / {@code t_gantt_dependency}。
 *
 * <p>只做持久化语义表达，SQL 全部写在 {@code resources/mapper/GanttMapper.xml}。</p>
 */
@Mapper
public interface GanttMapper {

    /* ---------- 计划 ---------- */

    int insertPlan(GanttPlan plan);

    int updatePlan(GanttPlan plan);

    /** 软删计划本身；其任务与依赖由服务层一并软删 */
    int softDeletePlan(@Param("id") String id, @Param("updateBy") String updateBy);

    GanttPlan selectPlanById(@Param("id") String id);

    /**
     * 计划列表，带任务数 / 完成数 / 平均进度聚合。
     *
     * @param ownerId 数据范围：{@code null} 表示不限制（超级管理员），否则只返回该用户创建的计划
     */
    List<GanttPlanRow> selectPlans(@Param("keyword") String keyword, @Param("ownerId") String ownerId);

    /** 计划统计：任务数、里程碑数、已完成数、平均进度，一次聚合查回 */
    GanttPlanRow selectPlanStat(@Param("planId") String planId);

    /* ---------- 任务 ---------- */

    int insertTask(GanttTask task);

    int updateTask(GanttTask task);

    int softDeleteTask(@Param("id") String id, @Param("updateBy") String updateBy);

    /** 删除某计划下的全部任务（删计划时级联） */
    int softDeleteTasksByPlan(@Param("planId") String planId, @Param("updateBy") String updateBy);

    GanttTask selectTaskById(@Param("id") String id);

    /** 计划下全部任务，按父任务、排序号返回 */
    List<GanttTask> selectTasksByPlan(@Param("planId") String planId);

    /** 某任务的直接子任务，用于分组任务的进度汇总与删除保护 */
    List<GanttTask> selectChildren(@Param("parentId") String parentId);

    /** 统计同层最大排序号，新增任务时追加到末尾 */
    int selectMaxSortNum(@Param("planId") String planId, @Param("parentId") String parentId);

    /* ---------- 依赖 ---------- */

    int insertDependency(GanttDependency dependency);

    /** 软删单条依赖：置 del_flag='1'，记录保留以便追溯 */
    int softDeleteDependency(@Param("id") String id, @Param("updateBy") String updateBy);

    GanttDependency selectDependencyById(@Param("id") String id);

    GanttDependency selectDependencyByPair(@Param("fromTaskId") String fromTaskId,
                                            @Param("toTaskId") String toTaskId);

    List<GanttDependency> selectDependenciesByPlan(@Param("planId") String planId);

    /** 软删与某任务相关的全部依赖（任务被删时） */
    int softDeleteDependenciesByTask(@Param("taskId") String taskId, @Param("updateBy") String updateBy);

    int softDeleteDependenciesByPlan(@Param("planId") String planId, @Param("updateBy") String updateBy);

    /* ---------- 恢复（针对软删数据） ---------- */

    GanttPlan selectDeletedPlan(@Param("id") String id);

    GanttTask selectDeletedTask(@Param("id") String id);

    List<GanttTask> selectDeletedTasksByPlan(@Param("planId") String planId);

    List<GanttPlanRow> selectDeletedPlans(@Param("ownerId") String ownerId);

    /** 已软删的直接子任务：恢复分组任务时把整个家族带回来 */
    List<GanttTask> selectDeletedChildren(@Param("parentId") String parentId);

    int restoreTask(@Param("id") String id, @Param("updateBy") String updateBy);

    int restoreTasksByPlan(@Param("planId") String planId, @Param("updateBy") String updateBy);

    int restorePlan(@Param("id") String id, @Param("updateBy") String updateBy);

    GanttDependency selectDeletedDependency(@Param("id") String id);

    List<GanttDependency> selectDeletedDependenciesByPlan(@Param("planId") String planId);

    List<GanttDependency> selectDeletedDependenciesByTask(@Param("taskId") String taskId);

    int restoreDependency(@Param("id") String id, @Param("updateBy") String updateBy);

    /**
     * 计划 + 聚合统计。
     *
     * <p>用独立类型而不是 Map：MyBatis 映射 Map 时列名大小写随驱动变化，
     * 取值处要写两套 key，容易出现「本地有值、线上 null」。
     * 继承 {@link GanttPlan} 是为了让列表查询一次 LEFT JOIN 就把统计字段带回来，
     * 不需要 N+1 地逐个计划再查一次。</p>
     */
    class GanttPlanRow extends GanttPlan {
        private int taskCount;
        private int milestoneCount;
        private int doneCount;
        private int avgProgress;
        /** 创建人显示名（昵称优先，回落到账号）。超管看全部数据时用它区分归属 */
        private String ownerName;

        public String getOwnerName() {
            return ownerName;
        }

        public void setOwnerName(String ownerName) {
            this.ownerName = ownerName;
        }

        public int getTaskCount() {
            return taskCount;
        }

        public void setTaskCount(int taskCount) {
            this.taskCount = taskCount;
        }

        public int getMilestoneCount() {
            return milestoneCount;
        }

        public void setMilestoneCount(int milestoneCount) {
            this.milestoneCount = milestoneCount;
        }

        public int getDoneCount() {
            return doneCount;
        }

        public void setDoneCount(int doneCount) {
            this.doneCount = doneCount;
        }

        public int getAvgProgress() {
            return avgProgress;
        }

        public void setAvgProgress(int avgProgress) {
            this.avgProgress = avgProgress;
        }
    }
}
