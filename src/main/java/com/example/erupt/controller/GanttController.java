package com.example.erupt.controller;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.GanttDependencyRequest;
import com.example.erupt.model.dto.GanttPlanRequest;
import com.example.erupt.model.dto.GanttTaskRequest;
import com.example.erupt.model.vo.GanttDeletedPlanVO;
import com.example.erupt.model.vo.GanttDeletedTaskVO;
import com.example.erupt.model.vo.GanttPlanDetailVO;
import com.example.erupt.model.vo.GanttPlanVO;
import com.example.erupt.model.vo.GanttRestoreVO;
import com.example.erupt.service.DataScopeService;
import com.example.erupt.service.GanttService;
import com.example.erupt.service.TaskReminderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 时间规划（甘特图）接口。
 *
 * <p>资源路径刻意分成三段：{@code plans} / {@code tasks} / {@code dependencies}。
 * 任务是独立资源（可以直接按 id 改，不需要带 planId），只有「新建」挂在计划下——
 * 新建时必须知道往哪个计划里加，用路径表达比让前端在 body 里塞 planId 更难出错。</p>
 */
@RestController
@RequestMapping("/api/gantt")
@Validated
public class GanttController {

    private final GanttService ganttService;
    private final TaskReminderService taskReminderService;
    private final DataScopeService dataScope;

    public GanttController(GanttService ganttService, TaskReminderService taskReminderService,
                           DataScopeService dataScope) {
        this.ganttService = ganttService;
        this.taskReminderService = taskReminderService;
        this.dataScope = dataScope;
    }

    /* ---------- 计划 ---------- */

    @GetMapping("/plans")
    public ResponseEntity<Result<List<GanttPlanVO>>> plans(@RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(Result.success(ganttService.listPlans(keyword)));
    }

    @PostMapping("/plans")
    public ResponseEntity<Result<GanttPlanVO>> createPlan(@Valid @RequestBody GanttPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(ganttService.createPlan(request), "创建成功"));
    }

    @PutMapping("/plans/{id}")
    public ResponseEntity<Result<Void>> updatePlan(@PathVariable String id,
                                                   @Valid @RequestBody GanttPlanRequest request) {
        return ResponseEntity.ok(ganttService.updatePlan(id, request));
    }

    @DeleteMapping("/plans/{id}")
    public ResponseEntity<Result<Void>> deletePlan(@PathVariable String id) {
        return ResponseEntity.ok(ganttService.deletePlan(id));
    }

    /**
     * 甘特图详情：计划 + 任务 + 依赖一次返回。
     */
    @GetMapping("/plans/{id}")
    public ResponseEntity<Result<GanttPlanDetailVO>> detail(@PathVariable String id) {
        return ResponseEntity.ok(Result.success(ganttService.detail(id)));
    }

    /* ---------- 任务 ---------- */

    @PostMapping("/plans/{planId}/tasks")
    public ResponseEntity<Result<String>> createTask(@PathVariable String planId,
                                                     @Valid @RequestBody GanttTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ganttService.createTask(planId, request));
    }

    @PutMapping("/tasks/{id}")
    public ResponseEntity<Result<Void>> updateTask(@PathVariable String id,
                                                   @Valid @RequestBody GanttTaskRequest request) {
        return ResponseEntity.ok(ganttService.updateTask(id, request));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Result<Void>> deleteTask(@PathVariable String id,
                                                   @RequestParam(defaultValue = "false") boolean cascade) {
        return ResponseEntity.ok(ganttService.deleteTask(id, cascade));
    }

    /* ---------- 依赖 ---------- */

    @PostMapping("/dependencies")
    public ResponseEntity<Result<Void>> createDependency(@Valid @RequestBody GanttDependencyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ganttService.createDependency(request));
    }

    @DeleteMapping("/dependencies/{id}")
    public ResponseEntity<Result<Void>> deleteDependency(@PathVariable String id) {
        return ResponseEntity.ok(ganttService.deleteDependency(id));
    }

    /* ---------- 恢复 ---------- */

    /** 回收站：计划下已删任务列表（按删除时间倒序），供随时恢复 */
    @GetMapping("/plans/{id}/deleted-tasks")
    public ResponseEntity<Result<List<GanttDeletedTaskVO>>> listDeletedTasks(@PathVariable String id) {
        return ResponseEntity.ok(ganttService.listDeletedTasks(id));
    }

    /** 计划级回收站：可见范围内已删的计划，删计划刷新后仍可从这里恢复 */
    @GetMapping("/deleted-plans")
    public ResponseEntity<Result<List<GanttDeletedPlanVO>>> listDeletedPlans() {
        return ResponseEntity.ok(ganttService.listDeletedPlans());
    }

    @PostMapping("/plans/{id}/restore")
    public ResponseEntity<Result<GanttRestoreVO>> restorePlan(@PathVariable String id) {
        return ResponseEntity.ok(ganttService.restorePlan(id));
    }

    @PostMapping("/tasks/{id}/restore")
    public ResponseEntity<Result<GanttRestoreVO>> restoreTask(@PathVariable String id) {
        return ResponseEntity.ok(ganttService.restoreTask(id));
    }

    @PostMapping("/dependencies/{id}/restore")
    public ResponseEntity<Result<GanttRestoreVO>> restoreDependency(@PathVariable String id) {
        return ResponseEntity.ok(ganttService.restoreDependency(id));
    }

    /**
     * 手动触发一轮任务到期提醒（仅超管）。
     *
     * <p>定时任务每天上午 9 点自动跑；这个入口用于验证与补跑——
     * 「提醒没发出来？」不用等到第二天上午，超管点一下立刻见分晓。
     * 限超管：普通用户能触发就等于能反复给自己刷通知。</p>
     */
    @PostMapping("/reminders/run")
    public ResponseEntity<Result<Integer>> runReminders() {
        if (dataScope.dataScopeOwnerId() != null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅超级管理员可手动触发提醒扫描");
        }
        return ResponseEntity.ok(Result.success(taskReminderService.runOnce()));
    }
}
