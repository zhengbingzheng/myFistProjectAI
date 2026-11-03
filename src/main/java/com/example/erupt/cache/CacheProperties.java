package com.example.erupt.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis 缓存策略配置：绑定 {@code app.cache.redis} 段。
 *
 * <p>所有字段都允许为空，缺失时走本类的兜底常量。这么设计的目的是：
 * 即便配置文件被改坏，或在某个未加载 application.yml 的测试上下文中注入本类，
 * 也不会 NPE，而是退化成一组安全的默认值。</p>
 *
 * <p>注意 Accessor 命名：{@code record} 会自动为 {@code enabled} 生成 {@code enabled()}，
 * 因此带默认值的读取方法统一加 {@code is} / {@code Or} 后缀区分。</p>
 */
@ConfigurationProperties(prefix = "app.cache.redis")
public record CacheProperties(
        Boolean enabled,
        String keyPrefix,
        Duration defaultTtl,
        Duration nullValueTtl,
        Integer ttlJitterPercent,
        Long lockWaitMs,
        Long lockLeaseMs,
        Navigation navigation) {

    /** 导航模块专属过期策略 */
    public record Navigation(Duration detailTtl, Duration listTtl, Duration catTtl) {

        public Duration detailTtlOr() {
            return detailTtl != null ? detailTtl : DEFAULT_DETAIL_TTL;
        }

        public Duration listTtlOr() {
            return listTtl != null ? listTtl : DEFAULT_LIST_TTL;
        }

        public Duration catTtlOr() {
            return catTtl != null ? catTtl : DEFAULT_CAT_TTL;
        }
    }

    private static final String DEFAULT_KEY_PREFIX = "erupt-example:cache:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_NULL_TTL = Duration.ofSeconds(60);
    private static final int DEFAULT_JITTER_PERCENT = 20;
    private static final long DEFAULT_LOCK_WAIT_MS = 500L;
    private static final long DEFAULT_LOCK_LEASE_MS = 5_000L;

    private static final Duration DEFAULT_DETAIL_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_LIST_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CAT_TTL = Duration.ofMinutes(30);

    /** 紧凑构造器：给缺失项填默认值（Spring Boot 绑定 record 时会调用它） */
    public CacheProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = DEFAULT_KEY_PREFIX;
        }
        if (navigation == null) {
            navigation = new Navigation(null, null, null);
        }
    }

    /** 缓存总开关。缺省视为开启 */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public String keyPrefixOr() {
        return keyPrefix;
    }

    public Duration defaultTtlOr() {
        return defaultTtl != null ? defaultTtl : DEFAULT_TTL;
    }

    public Duration nullValueTtlOr() {
        return nullValueTtl != null ? nullValueTtl : DEFAULT_NULL_TTL;
    }

    public int ttlJitterPercentOr() {
        return ttlJitterPercent != null ? ttlJitterPercent : DEFAULT_JITTER_PERCENT;
    }

    public long lockWaitMsOr() {
        return lockWaitMs != null ? lockWaitMs : DEFAULT_LOCK_WAIT_MS;
    }

    public long lockLeaseMsOr() {
        return lockLeaseMs != null ? lockLeaseMs : DEFAULT_LOCK_LEASE_MS;
    }
}
