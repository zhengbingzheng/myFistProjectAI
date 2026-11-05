package com.example.erupt.model.vo;

/**
 * 恢复操作的统计结果。
 *
 * <p>恢复是级联的——恢复一个分组会带回它的子任务、恢复计划会带回全部任务与依赖，
 * 只返回「成功」前端没法告诉用户实际回来了多少东西，所以带上计数。</p>
 *
 * @param tasks        恢复的任务数
 * @param dependencies 恢复的依赖数；同一对任务已存在有效依赖、或某端任务仍处于删除状态时跳过，
 *                     所以可能小于「当初删掉的条数」
 */
public record GanttRestoreVO(int tasks, int dependencies) {
}
