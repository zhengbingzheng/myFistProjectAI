package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.GanttDependencyRequest;
import com.example.erupt.model.dto.GanttPlanRequest;
import com.example.erupt.model.dto.GanttTaskRequest;
import com.example.erupt.model.vo.GanttDependencyVO;
import com.example.erupt.model.vo.GanttPlanDetailVO;
import com.example.erupt.model.vo.GanttPlanVO;
import com.example.erupt.model.vo.GanttDeletedPlanVO;
import com.example.erupt.model.vo.GanttDeletedTaskVO;
import com.example.erupt.model.vo.GanttRestoreVO;
import com.example.erupt.model.vo.GanttTaskVO;
import com.example.erupt.repository.entity.GanttDependency;
import com.example.erupt.repository.entity.GanttPlan;
import com.example.erupt.repository.entity.GanttTask;
import com.example.erupt.repository.mapper.GanttMapper;
import com.example.erupt.repository.mapper.GanttMapper.GanttPlanRow;
import com.example.erupt.service.DataScopeService;
import com.example.erupt.service.GanttService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 时间规划（甘特图）业务实现。
 *
 * <p>四条贯穿全类的约定：</p>
 * <ol>
 *   <li><b>数据按创建人隔离</b>：超级管理员可见全部计划，其余用户只能看到 / 操作自己创建的计划。
 *       范围由 {@link DataScopeService#dataScopeOwnerId()} 统一给出（{@code null} = 不限制），
 *       每个入口方法先取一次再往下传，避免同一请求里反复查角色。
 *       计划是归属单位：任务的归属跟随其所属计划，不单独看任务的 create_by——
 *       否则「超管在别人计划里建了个任务」会让任务与计划分属两个人，权限判断自相矛盾。</li>
 *   <li><b>父任务的时间与进度是派生的</b>：有子任务的节点，其起止取子任务的最小/最大日期、
 *       进度取子任务平均值。库里仍存着用户填过的值，但读取时一律以子任务为准，
 *       避免「改了子任务、父任务的条子还停在老位置」。</li>
 *   <li><b>依赖不允许成环</b>：新增依赖前先做一次 DFS，能走通就说明会形成循环依赖，
 *       直接拒绝——否则甘特图上会出现首尾相接的箭头，任何关键路径计算都会死循环。</li>
 *   <li><b>越权返回 403 而不是 404</b>：直接说「这不是你的」比伪装成「不存在」更省沟通，
 *       否则用户会以为数据丢了反复刷新。见 {@link DataScopeService#requireVisible}。</li>
 * </ol>
 */
@Service
public class GanttServiceImpl implements GanttService {

    private static final Logger log = LoggerFactory.getLogger(GanttServiceImpl.class);

    private static final Set<String> DEP_TYPES = Set.of("FS", "SS", "FF", "SF");
    private static final Set<String> TASK_STATUSES = Set.of("todo", "doing", "done", "blocked");
    private static final Set<String> PLAN_STATUSES = Set.of("active", "archived");
    private static final String DEFAULT_DEP_TYPE = "FS";
    private static final String DEFAULT_TASK_STATUS = "todo";
    /** 任务嵌套层数上限：顶层分组 → 二级分组 → 任务。递归汇总与缩进渲染都按这个深度设计 */
    private static final int MAX_TASK_DEPTH = 3;

    private final GanttMapper ganttMapper;
    private final DataScopeService dataScope;

    public GanttServiceImpl(GanttMapper ganttMapper, DataScopeService dataScope) {
        this.ganttMapper = ganttMapper;
        this.dataScope = dataScope;
    }

    /* ==================== 计划 ==================== */

    @Override
    @Transactional(readOnly = true)
    public List<GanttPlanVO> listPlans(String keyword) {
        // 非超管只查自己创建的；超管拿到 null，SQL 里不加 create_by 条件
        String scope = dataScope.dataScopeOwnerId();
        return ganttMapper.selectPlans(normalize(keyword), scope).stream().map(this::toPlanVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GanttPlanVO createPlan(GanttPlanRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String name = requireNonBlank(request.name(), "计划名称不能为空");
        LocalDate start = requireDate(request.startDate(), "计划开始日期不能为空");
        LocalDate end = requireDate(request.endDate(), "计划结束日期不能为空");
        requireValidRange(start, end, "计划结束日期不能早于开始日期");

        String status = normalize(request.status());
        if (status != null && !PLAN_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "计划状态只能是 active 或 archived");
        }

        GanttPlan plan = new GanttPlan();
        plan.setId(newId());
        plan.setPlanName(name);
        plan.setPlanDesc(normalize(request.description()));
        plan.setStartDate(start);
        plan.setEndDate(end);
        plan.setStatus(status == null ? "active" : status);
        plan.setCreateBy(AuthContext.currentUserId());
        plan.setUpdateBy(plan.getCreateBy());
        ganttMapper.insertPlan(plan);
        log.info("新建时间规划计划，id={}, name={}", plan.getId(), name);

        GanttPlanRow row = ganttMapper.selectPlanStat(plan.getId());
        return toPlanVO(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updatePlan(String id, GanttPlanRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        GanttPlan existing = requireAccessiblePlan(id, dataScope.dataScopeOwnerId());

        // 先把「合并后的最终值」算出来再校验：只传 end 时，必须拿库里的 start 一起比较，
        // 否则单独把结束日改到开始日之前也能通过。
        LocalDate start = request.startDate() != null ? request.startDate() : existing.getStartDate();
        LocalDate end = request.endDate() != null ? request.endDate() : existing.getEndDate();
        requireValidRange(start, end, "计划结束日期不能早于开始日期");

        String status = normalize(request.status());
        if (status != null && !PLAN_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "计划状态只能是 active 或 archived");
        }

        GanttPlan patch = new GanttPlan();
        patch.setId(id);
        patch.setPlanName(request.name() != null ? requireNonBlank(request.name(), "计划名称不能为空") : null);
        // 描述允许清空（null 写 NULL）；日期写合并后的最终值，只传一个时另一个保持原值
        patch.setPlanDesc(request.description());
        patch.setStartDate(start);
        patch.setEndDate(end);
        patch.setStatus(status);
        patch.setUpdateBy(AuthContext.currentUserId());
        ganttMapper.updatePlan(patch);
        return Result.success(null, "更新成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deletePlan(String id) {
        requireAccessiblePlan(id, dataScope.dataScopeOwnerId());
        String uid = AuthContext.currentUserId();
        // 三步必须同事务：只软删计划而漏掉任务，会留下一批「计划已不可见但仍占着甘特图数据」的孤儿行
        ganttMapper.softDeleteTasksByPlan(id, uid);
        ganttMapper.softDeleteDependenciesByPlan(id, uid);
        ganttMapper.softDeletePlan(id, uid);
        log.info("删除时间规划计划，id={}", id);
        return Result.success(null, "删除成功");
    }

    @Override
    @Transactional(readOnly = true)
    public GanttPlanDetailVO detail(String planId) {
        GanttPlanRow row = ganttMapper.selectPlanStat(planId);
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "计划不存在或已删除");
        }
        // 详情是最容易被「猜 id」的入口：统计行里已带 create_by，直接用它校验，不再多查一次计划
        dataScope.requireVisible(dataScope.dataScopeOwnerId(), row.getCreateBy(), "计划");
        List<GanttTask> tasks = ganttMapper.selectTasksByPlan(planId);

        // 一次分组，避免在递归汇总里对每个任务再扫一遍列表（N²）
        Map<String, List<GanttTask>> childrenMap = childrenMapOf(tasks);

        // 从顶层展开整棵树：递归汇总让二/三级分组的日期进度都由其全部后代派生。
        // 输出是「父在前、子随后」的平铺列表（前端按此顺序渲染），顺序 = 分组遍历顺序
        List<GanttTaskVO> taskVOs = tasks.stream()
                .filter(t -> t.getParentId() == null || t.getParentId().isBlank())
                .flatMap(t -> toTaskVOTree(t, childrenMap).stream())
                .toList();
        List<GanttDependencyVO> deps = ganttMapper.selectDependenciesByPlan(planId).stream()
                .map(d -> new GanttDependencyVO(d.getId(), d.getFromTaskId(), d.getToTaskId(),
                        d.getDepType(), d.getLagDays()))
                .toList();
        return new GanttPlanDetailVO(toPlanVO(row), taskVOs, deps);
    }

    /* ==================== 任务 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> createTask(String planId, GanttTaskRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        requireAccessiblePlan(planId, dataScope.dataScopeOwnerId());

        String name = requireNonBlank(request.name(), "任务名称不能为空");
        LocalDate start = requireDate(request.startDate(), "任务开始日期不能为空");
        LocalDate end = requireDate(request.endDate(), "任务结束日期不能为空");
        boolean milestone = Boolean.TRUE.equals(request.milestone());
        // 里程碑是「某一时点」，起止必须同一天；前端即使传了区间，也按开始日收敛，
        // 否则 UI 上会渲染出一个宽度不为 0 的菱形，语义就错了。
        if (milestone) {
            end = start;
        }
        requireValidRange(start, end, "任务结束日期不能早于开始日期");

        String parentId = normalize(request.parentId());
        if (parentId != null) {
            requireValidParent(planId, parentId, null);
        }

        String status = normalize(request.status());
        if (status != null && !TASK_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务状态非法");
        }

        GanttTask task = new GanttTask();
        task.setId(newId());
        task.setPlanId(planId);
        task.setParentId(parentId);
        task.setTaskName(name);
        task.setTaskDesc(normalize(request.description()));
        task.setStartDate(start);
        task.setEndDate(end);
        task.setProgress(request.progress() == null ? 0 : request.progress());
        task.setStatus(status == null ? DEFAULT_TASK_STATUS : status);
        task.setAssignee(normalize(request.assignee()));
        task.setPriority(request.priority() == null ? 2 : request.priority());
        task.setIsMilestone(milestone ? 1 : 0);
        task.setColor(normalize(request.color()));
        // 追加到同层末尾：不传排序号时按创建顺序排列，符合「新加的任务排最后」的直觉
        task.setSortNum(request.sortNum() != null
                ? request.sortNum()
                : ganttMapper.selectMaxSortNum(planId, parentId) + 10);
        task.setCreateBy(AuthContext.currentUserId());
        task.setUpdateBy(task.getCreateBy());
        ganttMapper.insertTask(task);
        log.info("新建甘特图任务，planId={}, taskId={}, name={}", planId, task.getId(), name);
        return Result.success(task.getId(), "新增成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateTask(String taskId, GanttTaskRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        GanttTask existing = requireAccessibleTask(taskId, dataScope.dataScopeOwnerId());

        LocalDate start = request.startDate() != null ? request.startDate() : existing.getStartDate();
        LocalDate end = request.endDate() != null ? request.endDate() : existing.getEndDate();
        boolean milestone = request.milestone() != null
                ? request.milestone()
                : Integer.valueOf(1).equals(existing.getIsMilestone());
        if (milestone) {
            end = start;
        }
        requireValidRange(start, end, "任务结束日期不能早于开始日期");

        // null = 提升为顶层任务。这里必须真的落 NULL，不能像以前那样「null 就沿用旧值」，
        // 否则「把子任务从分组里拿出来」永远保存不上，而接口还返回成功。
        String parentId = normalize(request.parentId());
        if (parentId != null) {
            requireValidParent(existing.getPlanId(), parentId, taskId);
        }

        String status = normalize(request.status());
        if (status != null && !TASK_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务状态非法");
        }

        GanttTask patch = new GanttTask();
        patch.setId(taskId);
        patch.setParentId(parentId);
        patch.setTaskName(request.name() != null ? requireNonBlank(request.name(), "任务名称不能为空") : null);
        patch.setTaskDesc(request.description());
        patch.setStartDate(start);
        patch.setEndDate(end);
        // 分组任务的进度是子任务算出来的，直接写库没有意义（读的时候又会被覆盖），
        // 这里显式忽略，避免出现「库里 80%、界面显示 35%」的对不上账。
        boolean isGroup = !ganttMapper.selectChildren(taskId).isEmpty();
        patch.setProgress(isGroup ? null : request.progress());
        patch.setStatus(status);
        patch.setAssignee(request.assignee());
        patch.setPriority(request.priority());
        patch.setIsMilestone(milestone ? 1 : 0);
        patch.setColor(request.color());
        patch.setSortNum(request.sortNum());
        patch.setUpdateBy(AuthContext.currentUserId());
        ganttMapper.updateTask(patch);
        return Result.success(null, "更新成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteTask(String taskId, boolean cascade) {
        GanttTask task = requireAccessibleTask(taskId, dataScope.dataScopeOwnerId());
        String operator = AuthContext.currentUserId();
        // 一次取回计划内全部有效任务，后代集合在内存里算（后序遍历：子在前、父在后），
        // 不再逐节点 selectChildren——多层嵌套下那是 N+1 次查询
        List<String> descendants = collectDescendants(taskId, childrenMapOf(ganttMapper.selectTasksByPlan(task.getPlanId())));
        if (!descendants.isEmpty() && !cascade) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "该任务下还有 " + descendants.size() + " 个后代任务，请先删除或移动子任务，或选择级联删除");
        }
        // 级联按「先子后父」软删：同一次事务里时间戳逐条递增，父任务在回收站里排在最前。
        // 依赖必须一起清掉，否则留下指向已删任务的边，画布上会出现指向空处的箭头。
        for (String id : descendants) {
            ganttMapper.softDeleteDependenciesByTask(id, operator);
            ganttMapper.softDeleteTask(id, operator);
        }
        ganttMapper.softDeleteDependenciesByTask(taskId, operator);
        ganttMapper.softDeleteTask(taskId, operator);
        if (!descendants.isEmpty()) {
            log.info("级联删除甘特图任务，taskId={}, 后代 {} 个", taskId, descendants.size());
        }
        return Result.success(null, "删除成功");
    }

    /** 有效任务按父 id 分组，供树形遍历（删除 / 汇总）一次建图复用 */
    private static Map<String, List<GanttTask>> childrenMapOf(List<GanttTask> tasks) {
        return tasks.stream()
                .filter(t -> t.getParentId() != null && !t.getParentId().isBlank())
                .collect(Collectors.groupingBy(GanttTask::getParentId));
    }

    /** taskId 的全部后代（后序：子在前、父在后）。深度受 MAX_TASK_DEPTH 约束，不会失控 */
    private List<String> collectDescendants(String taskId, Map<String, List<GanttTask>> childrenMap) {
        List<String> result = new ArrayList<>();
        collectDescendantsInto(taskId, childrenMap, result);
        return result;
    }

    private void collectDescendantsInto(String taskId, Map<String, List<GanttTask>> childrenMap,
                                        List<String> out) {
        for (GanttTask child : childrenMap.getOrDefault(taskId, List.of())) {
            collectDescendantsInto(child.getId(), childrenMap, out);
            out.add(child.getId());
        }
    }

    /* ==================== 依赖 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> createDependency(GanttDependencyRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String fromId = requireNonBlank(request.fromTaskId(), "前置任务不能为空");
        String toId = requireNonBlank(request.toTaskId(), "后置任务不能为空");
        if (fromId.equals(toId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务不能依赖自己");
        }
        // 两个端点都要可见：只校验 from 的话，拿到别人的 taskId 仍能建依赖，把链接进别人的甘特图
        String scope = dataScope.dataScopeOwnerId();
        GanttTask from = requireAccessibleTask(fromId, scope);
        GanttTask to = requireAccessibleTask(toId, scope);
        if (!Objects.equals(from.getPlanId(), to.getPlanId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "两个任务不属于同一个计划，无法建立依赖");
        }

        String type = normalize(request.type());
        type = type == null ? DEFAULT_DEP_TYPE : type.toUpperCase();
        if (!DEP_TYPES.contains(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "依赖类型只能是 FS / SS / FF / SF");
        }
        if (ganttMapper.selectDependencyByPair(fromId, toId) != null) {
            throw new BusinessException(ResultCode.CONFLICT, "这两个任务之间已存在依赖");
        }
        requireNoCycle(from.getPlanId(), fromId, toId);

        GanttDependency dep = new GanttDependency();
        dep.setId(newId());
        dep.setPlanId(from.getPlanId());
        dep.setFromTaskId(fromId);
        dep.setToTaskId(toId);
        dep.setDepType(type);
        dep.setLagDays(request.lagDays() == null ? 0 : request.lagDays());
        dep.setCreateBy(AuthContext.currentUserId());
        ganttMapper.insertDependency(dep);
        log.info("新增任务依赖，planId={}, {} -> {}, type={}", dep.getPlanId(), fromId, toId, type);
        return Result.success(null, "依赖已添加");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteDependency(String id) {
        GanttDependency dep = ganttMapper.selectDependencyById(id);
        if (dep == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "依赖不存在或已被删除");
        }
        // 依赖本身没有 create_by 语义（它连着两个任务），归属按所属计划判断
        requireAccessiblePlan(dep.getPlanId(), dataScope.dataScopeOwnerId());
        ganttMapper.softDeleteDependency(id, AuthContext.currentUserId());
        return Result.success(null, "依赖已删除");
    }

    /* ==================== 恢复 ==================== */

    @Override
    @Transactional(readOnly = true)
    public Result<List<GanttDeletedPlanVO>> listDeletedPlans() {
        String ownerId = dataScope.dataScopeOwnerId();
        // 统计行不带 owner_name 以外的派生语义，这里 task_count 借位存「已删任务数」
        List<GanttDeletedPlanVO> result = ganttMapper.selectDeletedPlans(ownerId).stream()
                .map(row -> new GanttDeletedPlanVO(
                        row.getId(),
                        row.getPlanName(),
                        row.getStartDate(),
                        row.getEndDate(),
                        row.getTaskCount(),
                        row.getUpdateBy(),
                        row.getUpdateTime()))
                .collect(Collectors.toList());
        return Result.success(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Result<List<GanttDeletedTaskVO>> listDeletedTasks(String planId) {
        GanttPlan plan = ganttMapper.selectPlanById(planId);
        if (plan == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "计划不存在");
        }
        dataScope.requireVisible(dataScope.dataScopeOwnerId(), plan.getCreateBy(), "计划");
        List<GanttTask> deleted = ganttMapper.selectDeletedTasksByPlan(planId);
        // 分组恢复会把已删后代一起带回来（按家族恢复），提前算好数量——多层下是全部后代
        Map<String, List<GanttTask>> deletedByParent = deleted.stream()
                .filter(t -> t.getParentId() != null && !t.getParentId().isBlank())
                .collect(Collectors.groupingBy(GanttTask::getParentId));
        List<GanttDeletedTaskVO> result = deleted.stream()
                .map(t -> new GanttDeletedTaskVO(
                        t.getId(),
                        t.getTaskName(),
                        t.getParentId(),
                        Integer.valueOf(1).equals(t.getIsMilestone()),
                        t.getStartDate(),
                        t.getEndDate(),
                        t.getUpdateBy(),
                        t.getUpdateTime(),
                        countDescendantsIn(deletedByParent, t.getId())))
                .collect(Collectors.toList());
        return Result.success(result);
    }

    /** 在已删任务集合内递归数后代（含各级），深度有界 */
    private int countDescendantsIn(Map<String, List<GanttTask>> deletedByParent, String taskId) {
        int count = 0;
        for (GanttTask child : deletedByParent.getOrDefault(taskId, List.of())) {
            count += 1 + countDescendantsIn(deletedByParent, child.getId());
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<GanttRestoreVO> restorePlan(String id) {
        GanttPlan plan = ganttMapper.selectDeletedPlan(id);
        if (plan == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "计划不存在或未被删除");
        }
        dataScope.requireVisible(dataScope.dataScopeOwnerId(), plan.getCreateBy(), "计划");
        String uid = AuthContext.currentUserId();

        // 先恢复计划本身，再恢复其下任务与依赖——顺序颠倒的话，
        // 恢复途中任何一次按有效计划过滤的查询都会把这次恢复当成孤儿数据
        ganttMapper.restorePlan(id, uid);
        int tasks = ganttMapper.restoreTasksByPlan(id, uid);
        // 依赖逐条恢复：同一对任务历史上反复删建会有多条墓碑，一把 UPDATE 会撞唯一键。
        // 已有有效依赖的对直接跳过——用户看到恢复计数小于删除计数时，原因可解释。
        int deps = 0;
        for (GanttDependency d : ganttMapper.selectDeletedDependenciesByPlan(id)) {
            if (ganttMapper.selectDependencyByPair(d.getFromTaskId(), d.getToTaskId()) == null) {
                ganttMapper.restoreDependency(d.getId(), uid);
                deps++;
            }
        }
        log.info("恢复甘特图计划，id={}, 任务 {} 个、依赖 {} 条", id, tasks, deps);
        return Result.success(new GanttRestoreVO(tasks, deps), "计划已恢复");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<GanttRestoreVO> restoreTask(String id) {
        GanttTask task = ganttMapper.selectDeletedTask(id);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务不存在或未被删除");
        }
        // 计划本身也删了时不能只恢复任务：任务挂在已删计划下等于仍然不可见，
        // 与其让用户困惑「恢复了怎么还是看不到」，不如明确让他先恢复计划。
        GanttPlan plan = ganttMapper.selectPlanById(task.getPlanId());
        if (plan == null) {
            throw new BusinessException(ResultCode.CONFLICT, "所属计划已被删除，请先恢复计划");
        }
        dataScope.requireVisible(dataScope.dataScopeOwnerId(), plan.getCreateBy(), "计划");
        String uid = AuthContext.currentUserId();

        // 按家族恢复：删分组时子任务是一起删的，恢复任意成员都把全家带回来。
        Set<String> restored = new HashSet<>();
        restoreTaskFamily(task, uid, restored);

        // 依赖只恢复两端都有效的：另一端还删着就先留着，等那一端恢复时再回来。
        int deps = 0;
        Set<String> handled = new HashSet<>();
        for (String taskId : restored) {
            for (GanttDependency d : ganttMapper.selectDeletedDependenciesByTask(taskId)) {
                if (!handled.add(d.getId())) continue;
                GanttTask from = ganttMapper.selectTaskById(d.getFromTaskId());
                GanttTask to = ganttMapper.selectTaskById(d.getToTaskId());
                if (from == null || to == null) continue;
                if (ganttMapper.selectDependencyByPair(d.getFromTaskId(), d.getToTaskId()) != null) continue;
                ganttMapper.restoreDependency(d.getId(), uid);
                deps++;
            }
        }
        log.info("恢复甘特图任务，id={}, 任务 {} 个、依赖 {} 条", id, restored.size(), deps);
        return Result.success(new GanttRestoreVO(restored.size(), deps), "任务已恢复");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<GanttRestoreVO> restoreDependency(String id) {
        GanttDependency dep = ganttMapper.selectDeletedDependency(id);
        if (dep == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "依赖不存在或未被删除");
        }
        GanttPlan plan = ganttMapper.selectPlanById(dep.getPlanId());
        if (plan == null) {
            throw new BusinessException(ResultCode.CONFLICT, "所属计划已被删除，请先恢复计划");
        }
        dataScope.requireVisible(dataScope.dataScopeOwnerId(), plan.getCreateBy(), "计划");
        GanttTask from = ganttMapper.selectTaskById(dep.getFromTaskId());
        GanttTask to = ganttMapper.selectTaskById(dep.getToTaskId());
        if (from == null || to == null) {
            throw new BusinessException(ResultCode.CONFLICT, "前置或后置任务已被删除，请先恢复任务");
        }
        if (ganttMapper.selectDependencyByPair(dep.getFromTaskId(), dep.getToTaskId()) != null) {
            throw new BusinessException(ResultCode.CONFLICT, "同一对任务已存在依赖，无需恢复");
        }
        ganttMapper.restoreDependency(id, AuthContext.currentUserId());
        return Result.success(new GanttRestoreVO(0, 1), "依赖已恢复");
    }

    /**
     * 恢复任务及其家族（多层版）。
     *
     * <p>先递归恢复父链（已删的祖先连带它下面全部已删后代），再递归恢复自己的已删后代——
     * 恢复任意一个成员都会把整棵连通子树带回来，不会留孤儿挂在画布外。</p>
     */
    private void restoreTaskFamily(GanttTask task, String uid, Set<String> restored) {
        // 向上：祖先链还删着就先恢复它（递归进入又会先处理更上层的祖先）
        if (task.getParentId() != null && !restored.contains(task.getParentId())) {
            GanttTask parent = ganttMapper.selectDeletedTask(task.getParentId());
            if (parent != null) {
                restoreTaskFamily(parent, uid, restored);
            }
        }
        if (restored.add(task.getId())) {
            ganttMapper.restoreTask(task.getId(), uid);
        }
        // 向下：全部已删后代逐个递归带回
        for (GanttTask child : ganttMapper.selectDeletedChildren(task.getId())) {
            if (!restored.contains(child.getId())) {
                restoreTaskFamily(child, uid, restored);
            }
        }
    }

    /* ==================== 内部工具 ==================== */

    /**
     * 取出计划并校验数据范围。
     *
     * @param scopeOwnerId 当前用户的数据范围，{@code null} 表示超管（不校验）
     */
    private GanttPlan requireAccessiblePlan(String id, String scopeOwnerId) {
        GanttPlan plan = ganttMapper.selectPlanById(requireNonBlank(id, "计划 id 不能为空"));
        if (plan == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "计划不存在或已删除");
        }
        dataScope.requireVisible(scopeOwnerId, plan.getCreateBy(), "计划");
        return plan;
    }

    /**
     * 取出任务并校验其所属计划的可见范围。
     *
     * <p>归属看计划、不看任务自己的 create_by：超管可能在别人的计划里代建任务，
     * 若按任务的创建人判断，那条任务会对计划主人不可见，而超管又能在图上看到它——
     * 同一条数据两种视角，权限就说不清了。</p>
     */
    private GanttTask requireAccessibleTask(String id, String scopeOwnerId) {
        GanttTask task = ganttMapper.selectTaskById(requireNonBlank(id, "任务 id 不能为空"));
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务不存在或已删除");
        }
        requireAccessiblePlan(task.getPlanId(), scopeOwnerId);
        return task;
    }

    /**
     * 父任务合法性校验。
     *
     * <p>支持最多三层嵌套（顶层分组 → 二级分组 → 任务）：父任务可以是任何未达深度上限的分组。
     * 深度上限让「汇总进度」的递归深度可控，甘特图缩进也不会无限加深压垮窄屏。
     * 改父只允许叶子任务：整棵子树搬家牵扯移动端布局与拖拽约束，收益不值当。</p>
     */
    private void requireValidParent(String planId, String parentId, String selfId) {
        if (Objects.equals(parentId, selfId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务不能作为自己的父任务");
        }
        GanttTask parent = ganttMapper.selectTaskById(parentId);
        if (parent == null || !Objects.equals(parent.getPlanId(), planId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "父任务不存在或不属于当前计划");
        }
        if (selfId != null) {
            // 祖先链上出现自己 = 成环（A 挂到 B、B 挂到 A），逐级向上查，层数上限内可控
            String cursor = parentId;
            while (cursor != null && !cursor.isBlank()) {
                if (cursor.equals(selfId)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "父任务链上包含当前任务，会造成循环嵌套");
                }
                GanttTask ancestor = ganttMapper.selectTaskById(cursor);
                if (ancestor == null) {
                    break;
                }
                cursor = ancestor.getParentId();
            }
            if (!ganttMapper.selectChildren(selfId).isEmpty()) {
                throw new BusinessException(ResultCode.CONFLICT, "该任务下已有子任务，不能再设为其它任务的子任务");
            }
        }
        if (taskDepth(parent) + 1 > MAX_TASK_DEPTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "最多支持 " + MAX_TASK_DEPTH + " 层嵌套，无法再往下挂");
        }
    }

    /** 任务嵌套深度：顶层为 1，逐级 +1。沿父链向上数，层级受 MAX_TASK_DEPTH 约束 */
    private int taskDepth(GanttTask task) {
        int depth = 1;
        String cursor = task.getParentId();
        while (cursor != null && !cursor.isBlank()) {
            depth++;
            GanttTask p = ganttMapper.selectTaskById(cursor);
            if (p == null) {
                break;
            }
            cursor = p.getParentId();
        }
        return depth;
    }

    /**
     * 成环检测：把「新增 from → to」加到图上，从 to 出发能否走回 from。
     *
     * <p>能走通说明这条边会闭合一个环。用迭代式 DFS 而不是递归：
     * 任务链可能很长，递归深度取决于用户数据，栈溢出属于不可控故障。</p>
     */
    private void requireNoCycle(String planId, String fromId, String toId) {
        Map<String, List<String>> graph = new HashMap<>();
        for (GanttDependency d : ganttMapper.selectDependenciesByPlan(planId)) {
            graph.computeIfAbsent(d.getFromTaskId(), k -> new ArrayList<>()).add(d.getToTaskId());
        }
        graph.computeIfAbsent(fromId, k -> new ArrayList<>()).add(toId);

        Set<String> visited = new HashSet<>();
        LinkedList<String> stack = new LinkedList<>();
        stack.push(toId);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (Objects.equals(current, fromId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "新增该依赖会形成循环依赖，请检查任务之间的前后置关系");
            }
            if (!visited.add(current)) {
                continue;
            }
            for (String next : graph.getOrDefault(current, List.of())) {
                stack.push(next);
            }
        }
    }

    private void requireValidRange(LocalDate start, LocalDate end, String message) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
    }

    private String requireNonBlank(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return normalized;
    }

    private LocalDate requireDate(LocalDate value, String message) {
        if (value == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return value;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /* ==================== 实体 → VO ==================== */

    private GanttPlanVO toPlanVO(GanttPlanRow row) {
        return new GanttPlanVO(
                row.getId(),
                row.getPlanName(),
                row.getPlanDesc(),
                row.getStartDate(),
                row.getEndDate(),
                row.getStatus(),
                row.getTaskCount(),
                row.getMilestoneCount(),
                row.getDoneCount(),
                row.getAvgProgress(),
                row.getCreateBy(),
                row.getOwnerName(),
                // 归属在服务端判定：前端不需要「我的 id」，也就不受登录态是否已加载影响
                Objects.equals(row.getCreateBy(), AuthContext.currentUserId()));
    }

    /**
     * 任务子树 → 平铺 VO 列表（父在前、子随后）。
     *
     * <p>分组条的起止、进度、状态由其**全部后代**递归派生：二/三级分组的汇总值
     * 基于子 VO 的汇总值再汇总，而不是各自实体里可能过期的字段。
     * 叶子任务直接用自身值。</p>
     */
    private List<GanttTaskVO> toTaskVOTree(GanttTask task, Map<String, List<GanttTask>> childrenMap) {
        List<GanttTask> children = childrenMap.getOrDefault(task.getId(), List.of());
        LocalDate start = task.getStartDate();
        LocalDate end = task.getEndDate();
        Integer progress = task.getProgress();
        String status = task.getStatus();
        List<GanttTaskVO> childVOs = List.of();

        if (!children.isEmpty()) {
            childVOs = children.stream()
                    .flatMap(c -> toTaskVOTree(c, childrenMap).stream())
                    .toList();
            start = childVOs.stream().map(GanttTaskVO::startDate).filter(Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(start);
            end = childVOs.stream().map(GanttTaskVO::endDate).filter(Objects::nonNull)
                    .max(LocalDate::compareTo).orElse(end);
            progress = (int) Math.round(childVOs.stream()
                    .mapToInt(GanttTaskVO::progress)
                    .average().orElse(0));
            status = summarizeStatuses(childVOs.stream().map(GanttTaskVO::status).toList());
        }

        int durationDays = 1;
        if (start != null && end != null) {
            durationDays = (int) ChronoUnit.DAYS.between(start, end) + 1;
        }

        GanttTaskVO self = new GanttTaskVO(
                task.getId(),
                task.getPlanId(),
                task.getParentId(),
                task.getTaskName(),
                task.getTaskDesc(),
                start,
                end,
                progress == null ? 0 : progress,
                status,
                task.getAssignee(),
                task.getPriority(),
                Integer.valueOf(1).equals(task.getIsMilestone()),
                task.getColor(),
                task.getSortNum(),
                Math.max(durationDays, 1));

        List<GanttTaskVO> result = new ArrayList<>(childVOs.size() + 1);
        result.add(self);
        result.addAll(childVOs);
        return result;
    }

    /** 子任务全完成 → 分组完成；只要有后代在推进 → 进行中；否则待开始（树形版基于子 VO 状态） */
    private String summarizeStatuses(List<String> statuses) {
        boolean allDone = statuses.stream().allMatch("done"::equals);
        if (allDone) {
            return "done";
        }
        boolean anyRunning = statuses.stream().anyMatch(s -> "doing".equals(s) || "done".equals(s));
        return anyRunning ? "doing" : DEFAULT_TASK_STATUS;
    }
}
