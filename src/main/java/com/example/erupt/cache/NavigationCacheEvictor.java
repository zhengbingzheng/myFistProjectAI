package com.example.erupt.cache;

import com.example.erupt.cache.event.NavigationChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 导航模块的缓存失效器：监听写操作发出的 {@link NavigationChangedEvent}，清除导航相关缓存。
 *
 * <p>【为什么清除动作要放在事务提交之后，而不是直接写在 Service 方法里】
 * 写操作都带 {@code @Transactional}，如果清除动作和方法体在同一个事务里，会踩两个坑：</p>
 * <ol>
 *   <li><b>回滚后缓存已删</b>：事务最终回滚了，但缓存已经清掉了。虽然之后会重建，
 *       只是多一次回源，影响不算大</li>
 *   <li><b>更严重 —— 脏数据被永久写进缓存</b>：清除之后到事务提交之前这段时间窗口里，
 *       另一个并发请求会读到"尚未提交的旧数据"，并把它回填进刚被清掉的缓存 key。
 *       于是这份脏数据会一直待到 TTL 过期，且不会被任何机制纠正</li>
 * </ol>
 * 注册为 {@code AFTER_COMMIT} 可以避免这两点 —— 数据库状态尘埃落定之后才动缓存。
 *
 * <p>{@code fallbackExecution = true}：
 * 当发布事件的方法<strong>没有</strong>运行在事务中时（例如直接调用某个不带事务的方法、
 * 或测试环境没有配置事务管理器），该监听器依然会被调用。否则这类场景下的写操作就不会触发失效，
 * 表现为令人困惑的"数据已经改了，但列表还是旧内容"。</p>
 */
@Slf4j
@Component
public class NavigationCacheEvictor {

    private final NavigationCache navigationCache;

    public NavigationCacheEvictor(NavigationCache navigationCache) {
        this.navigationCache = navigationCache;
    }

    @TransactionalEventListener(fallbackExecution = true, phase = TransactionPhase.AFTER_COMMIT)
    public void onNavigationChanged(NavigationChangedEvent event) {
        log.info("导航数据变更，触发缓存失效. action={}", event.action());
        navigationCache.evictAll();
    }
}
