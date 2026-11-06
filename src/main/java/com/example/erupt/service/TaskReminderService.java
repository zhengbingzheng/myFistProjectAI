package com.example.erupt.service;

/**
 * 任务到期提醒：扫描甘特任务，把「快到期 / 已逾期还没完成」的任务推给计划创建者。
 *
 * <p>去重与停发规则（在 SQL 层实现，见 GanttMapper.selectTasksDueReminder）：</p>
 * <ul>
 *   <li>同一任务在重复间隔天数内提醒过就不再发——逾期任务每隔几天被重新提一次，
 *       直到被完成或删除；</li>
 *   <li>任务 status 变成 done 后自动停发；</li>
 *   <li>用户「删掉」提醒通知后仍可能再收到同任务提醒——软删的通知不参与去重，
 *       这是故意的：删掉提醒等于要求换条新的再提醒我，已删记录不算「已提醒」凭证。</li>
 * </ul>
 */
public interface TaskReminderService {

    /**
     * 跑一轮扫描并发通知。
     *
     * @return 本轮实际发送的通知条数
     */
    int runOnce();
}
