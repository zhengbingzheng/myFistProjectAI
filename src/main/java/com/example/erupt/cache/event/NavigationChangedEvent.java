package com.example.erupt.cache.event;

/**
 * 导航数据发生变更的领域事件。
 *
 * <p>用它来触发缓存失效，而不是在 Service 方法里直接调用清除，是为了拿捏执行的时机
 * —— 监听器注册在"事务提交之后"（见 {@code NavigationCacheEvictor}）。</p>
 *
 * @param action 变更动作，用于日志追溯（新增/更新/删除/批量删除）
 */
public record NavigationChangedEvent(String action) {
}
