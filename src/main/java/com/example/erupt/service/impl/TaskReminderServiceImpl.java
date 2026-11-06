package com.example.erupt.service.impl;

import com.example.erupt.repository.mapper.GanttMapper;
import com.example.erupt.service.NotificationService;
import com.example.erupt.service.TaskReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 任务提醒实现：一条查询拿齐「任务 + 计划名 + 接收人」，逐条发通知。
 *
 * <p>定时与手动触发共用 {@link #runOnce()}：定时是每日上午 9 点的节奏，
 * 手动触发（超管）用于验证与补跑，两条路径的去重规则完全一致。</p>
 */
@Service
public class TaskReminderServiceImpl implements TaskReminderService {

    private static final Logger log = LoggerFactory.getLogger(TaskReminderServiceImpl.class);

    /** 提前量：截止日在今天 ~ 3 天内的未完成任务都提醒（含今天到期与已逾期） */
    private static final int DUE_IN_DAYS = 3;
    /** 重复间隔：同一任务 3 天内提醒过就不再发；逾期任务约每 3 天被重新提一次 */
    private static final int REPEAT_INTERVAL_DAYS = 3;
    /** 通知标题里任务名的截断长度，防止超长任务名撑爆标题 */
    private static final int TITLE_MAX = 30;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("M月d日");

    private final GanttMapper ganttMapper;
    private final NotificationService notificationService;

    public TaskReminderServiceImpl(GanttMapper ganttMapper, NotificationService notificationService) {
        this.ganttMapper = ganttMapper;
        this.notificationService = notificationService;
    }

    /**
     * 每日上午 9 点扫描。选 9 点而不是 0 点：0 点发的提醒会被用户当成「半夜骚扰」，
     * 上午开工前看到当天要收尾的任务才有行动价值。
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void scheduledScan() {
        try {
            int sent = runOnce();
            if (sent > 0) {
                log.info("任务到期提醒：本轮发送 {} 条", sent);
            }
        } catch (Exception e) {
            // 定时任务绝不能让异常杀掉调度线程：记日志，下轮再试
            log.warn("任务到期提醒扫描失败: {}", e.getMessage());
        }
    }

    @Override
    public synchronized int runOnce() {
        // synchronized：手动触发与定时扫描撞车时串行执行，配合 SQL 层 NOT EXISTS 去重，
        // 单机部署下杜绝同一任务被并发双发；多实例部署需改分布式锁（当前不适用）
        List<GanttMapper.TaskReminderRow> tasks =
                ganttMapper.selectTasksDueReminder(DUE_IN_DAYS, REPEAT_INTERVAL_DAYS);
        int sent = 0;
        LocalDate today = LocalDate.now();
        for (GanttMapper.TaskReminderRow task : tasks) {
            try {
                notificationService.notify(task.getOwnerId(), buildTitle(task, today),
                        buildContent(task, today), "task", "/gantt", task.getId());
                sent++;
            } catch (Exception e) {
                // 单条失败不影响其他任务：下一轮扫描会因去重窗口未过而自然重试
                log.warn("任务提醒发送失败 taskId={} 原因={}", task.getId(), e.getMessage());
            }
        }
        return sent;
    }

    private static String buildTitle(GanttMapper.TaskReminderRow task, LocalDate today) {
        String name = task.getTaskName().length() > TITLE_MAX
                ? task.getTaskName().substring(0, TITLE_MAX) + "…" : task.getTaskName();
        long overdue = ChronoUnit.DAYS.between(task.getEndDate(), today);
        if (overdue > 0) {
            return "任务已逾期 " + overdue + " 天：" + name;
        }
        if (overdue == 0) {
            return "任务今天截止：" + name;
        }
        return "任务将于 " + task.getEndDate().format(DATE_FMT) + " 截止：" + name;
    }

    private static String buildContent(GanttMapper.TaskReminderRow task, LocalDate today) {
        long overdue = ChronoUnit.DAYS.between(task.getEndDate(), today);
        StringBuilder sb = new StringBuilder();
        sb.append("计划「").append(task.getPlanName()).append("」中的任务「").append(task.getTaskName()).append("」");
        if (overdue > 0) {
            sb.append("已逾期 ").append(overdue).append(" 天（截止 ")
              .append(task.getEndDate().format(DATE_FMT)).append("）");
        } else if (overdue == 0) {
            sb.append("今天到期");
        } else {
            sb.append("将于 ").append(task.getEndDate().format(DATE_FMT)).append(" 到期");
        }
        if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
            sb.append("，负责人：").append(task.getAssignee());
        }
        sb.append("，请及时处理。");
        return sb.toString();
    }
}
