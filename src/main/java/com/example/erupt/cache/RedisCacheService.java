package com.example.erupt.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 统一 Redis 缓存客户端：所有业务模块的缓存读写都收敛到这里。
 *
 * <p>本类的核心职责不只是把数据塞进 Redis 再取出来，更重要的是保证一件事：
 * <b>Redis 不可用时业务不受影响</b>。因此每条 Redis 操作都单独包了异常处理，
 * 任何异常都不会向业务层抛出，只记录在日志里并回退到"直接查数据库"。</p>
 *
 * <p>三层防护：</p>
 * <ol>
 *   <li><b>开关</b>：{@code app.cache.redis.enabled=false} 时全部短路，用于快速对比排障</li>
 *   <li><b>异常降级</b>：连接失败/超时/命令异常 → 记日志 + 回源数据库</li>
 *   <li><b>熔断</b>：连续失败达到阈值后，接下来 30 秒完全不碰 Redis。
 *       这是必要的 —— 若每次请求都要等 2 秒超时才走降级，突发流量下线程池会被打满</li>
 * </ol>
 */
@Slf4j
@Service
public class RedisCacheService {

    /**
     * 空值标记。代表"这个 key 查过了，但数据库里确实没有"。
     * 用它区分"从没查过"和"查过、结果为 null"，是防穿透的前提。
     */
    private static final String NULL_MARKER = "nil";

    /** 连续失败多少次后打开熔断 */
    private static final int FAILURE_THRESHOLD = 5;

    /** 熔断持续时间：期间不再访问 Redis，让连接池和 Redis 本身有喘息机会 */
    private static final Duration CIRCUIT_OPEN_DURATION = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheProperties properties;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntilNanos = 0L;

    public RedisCacheService(StringRedisTemplate redis,
                             @Qualifier("cacheObjectMapper") ObjectMapper mapper,
                             CacheProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
    }

    /* ==================== 对外 API ==================== */

    /**
     * 当前是否允许访问 Redis（开关 + 熔断状态的综合判断）。
     */
    public boolean available() {
        if (!properties.isEnabled()) {
            return false;
        }
        return System.nanoTime() >= circuitOpenUntilNanos;
    }

    /**
     * 读缓存，未命中则回源并把结果写回。业务模块的主入口。
     *
     * @param key     缓存 key，取自 {@link CacheKeys}
     * @param type    反序列化的目标类型。泛型类型（如 {@code PageResult<NavigationVO>}）
     *                必须用 {@code TypeReference} 才能带上范型信息，直接传 {@code Class} 会丢
     * @param ttl     存活时长，实际写入前会做随机抖动（见 {@link #jittered}）
     * @param loader  回源逻辑，仅在未命中或缓存不可用时执行
     */
    public <T> T getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        if (!available()) {
            return loader.get();
        }
        Read<T> read = readKey(key, type);
        if (read.hit()) {
            return read.value();
        }
        T loaded = loader.get();
        if (loaded == null) {
            // 数据库里确实没有：也短期记一笔，否则同一时刻大量相同请求会全部穿到库上
            writeNullMarker(key);
        } else {
            write(key, loaded, ttl);
        }
        return loaded;
    }

    /** 写入（会做 TTL 抖动） */
    public void write(String key, Object value, Duration ttl) {
        if (!available()) {
            return;
        }
        try {
            String json = mapper.writer().writeValueAsString(value);
            redis.opsForValue().set(key, json, jittered(ttl));
            recordSuccess();
        } catch (JacksonException e) {
            // 写不进去最多等于没命中，不能影响业务，记录后放行
            log.warn("缓存序列化失败，跳过本次写入. key={}, 原因={}", key, e.getMessage());
        } catch (RuntimeException e) {
            recordFailure("写入缓存", key, e);
        }
    }

    /** 删除单个 key */
    public void delete(String key) {
        if (!available()) {
            return;
        }
        try {
            redis.delete(key);
            recordSuccess();
        } catch (RuntimeException e) {
            recordFailure("删除缓存", key, e);
        }
    }

    /**
     * 按前缀批量清除：业务数据发生写操作时，用它将该模块的所有 key 一次性失效。
     *
     * <p>这里用 {@code SCAN} 而不是 {@code KEYS}：{@code KEYS} 会一次遍历完整个 keyspace
     * 并阻塞 Redis 的单线程，key 量大时足以让 Redis 短暂无响应；{@code SCAN} 游标分批不会久占。</p>
     */
    public void deleteByPrefix(String prefix) {
        if (!available()) {
            return;
        }
        int removed = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(200)
                .build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
                removed++;
            }
            recordSuccess();
            if (removed > 0) {
                log.info("按前缀清除缓存完成. prefix={}*, 删除 key 数={}", prefix, removed);
            }
        } catch (RuntimeException e) {
            recordFailure("按前缀清除缓存", prefix, e);
        } catch (Exception e) {
            // Cursor.close() 抛受检异常：走到这里说明连接中断，已删除的部分无需回滚（本来就是要删）
            log.warn("按前缀清除缓存中断. prefix={}, 已删除={} 个", prefix, removed, e);
        }
    }

    /**
     * 连通性探测，供健康检查或人工排障调用。
     * 注意它本身也会更新熔断状态，不要放在高频调用的链路上。
     */
    public boolean ping() {
        try {
            redis.hasKey(NULL_MARKER);
            recordSuccess();
            return true;
        } catch (RuntimeException e) {
            recordFailure("连通性探测", "-", e);
            return false;
        }
    }

    /**
     * 给 TTL 叠加随机抖动，防止缓存雪崩。
     *
     * <p>同时写入、TTL 都是 10 分钟的 key 会在同一秒集体失效，届时全部请求一起压到数据库。
     * 加上 ±20% 抖动后，它们的失效时刻会分散在一个区间内，压力被摊开。</p>
     */
    public Duration jittered(Duration ttl) {
        if (ttl == null) {
            ttl = properties.defaultTtlOr();
        }
        if (ttl.isZero() || ttl.isNegative()) {
            return ttl;
        }
        int percent = properties.ttlJitterPercentOr();
        if (percent <= 0) {
            return ttl;
        }
        long base = ttl.toMillis();
        long maxDrift = base * percent / 100L;
        long drift = ThreadLocalRandom.current().nextLong(-maxDrift, maxDrift + 1);
        return Duration.ofMillis(Math.max(1L, base + drift));
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 读取结果。刻意用 {@code hit} 区分三种状态，这是空值缓存能成立的关键：
     * <ol>
     *   <li>MISS：key 不存在 → 需要回源</li>
     *   <li>命中空值标记：之前查过且库里没有 → 直接返回 null，不再回源</li>
     *   <li>命中正常值</li>
     * </ol>
     * 若用 {@code Optional} 表达会丢掉第 2 种与第 1 种的区别。
     */
    private record Read<T>(boolean hit, T value) {

        static <T> Read<T> miss() {
            return new Read<>(false, null);
        }

        static <T> Read<T> ofNull() {
            return new Read<>(true, null);
        }

        static <T> Read<T> of(T value) {
            return new Read<>(true, value);
        }
    }

    private <T> Read<T> readKey(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Read.miss();
            }
            if (NULL_MARKER.equals(json)) {
                return Read.ofNull();
            }
            T value = mapper.readerFor(type).readValue(json);
            recordSuccess();
            return Read.of(value);
        } catch (JacksonException e) {
            // 序列化契约变了（字段类型改了、枚举取值变了等）：按未命中处理让它回源重写，
            // 顺手把这条坏数据删掉，避免每次读到它都走一遍反序列化失败
            log.warn("缓存反序列化失败，按未命中回源并清理该 key. key={}, 原因={}", key, e.getMessage());
            delete(key);
            return Read.miss();
        } catch (RuntimeException e) {
            recordFailure("读取缓存", key, e);
            return Read.miss();
        }
    }

    private void writeNullMarker(String key) {
        if (!available()) {
            return;
        }
        try {
            redis.opsForValue().set(key, NULL_MARKER, jittered(properties.nullValueTtlOr()));
            recordSuccess();
        } catch (RuntimeException e) {
            recordFailure("写入空值标记", key, e);
        }
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
    }

    private void recordFailure(String operation, String key, RuntimeException cause) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            circuitOpenUntilNanos = System.nanoTime() + CIRCUIT_OPEN_DURATION.toNanos();
            log.warn("Redis 操作异常已连续 {} 次，熔断 {} 秒，期间缓存全部跳过直接查库. 操作={}, key={}, 原因={}",
                    failures, CIRCUIT_OPEN_DURATION.toSeconds(), operation, key, cause.getMessage());
        } else {
            log.warn("Redis 操作异常，本次降级查库. 操作={}, key={}, 连续失败={}/{}, 原因={}",
                    operation, key, failures, FAILURE_THRESHOLD, cause.getMessage());
        }
    }
}
