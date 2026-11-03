package com.example.erupt.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RedisCacheService 的行为测试：重点是【降级】与【熔断】必须真的生效。
 *
 * <p>这里刻意全部用 Mockito 模拟，不连真实 Redis —— 单元测试依赖外部组件会导致
 * 结果随环境变化（Redis 没起就红），失去回归保护的意义。真实连通性另由启动后的手工验证覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisCacheServiceTest {

    private static final TypeReference<String> TYPE_STRING = new TypeReference<String>() {
    };

    private static final Duration TTL = Duration.ofMinutes(10);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper mapper;
    private RedisCacheService cache;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        cache = new RedisCacheService(redis, mapper, enabledProps());
    }

    /* ==================== 基本读写 ==================== */

    @Test
    @DisplayName("getOrLoad_cacheHit_doesNotCallLoader")
    void getOrLoad_cacheHit_doesNotCallLoader() {
        when(valueOps.get("k")).thenReturn("\"from-cache\"");
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            return "from-db";
        };

        String result = cache.getOrLoad("k", TYPE_STRING, TTL, loader);

        assertThat(result).isEqualTo("from-cache");
        assertThat(loaderCalls.get()).isZero();
    }

    @Test
    @DisplayName("getOrLoad_cacheMiss_loadsFromSourceAndWritesBack")
    void getOrLoad_cacheMiss_loadsAndWritesBack() {
        when(valueOps.get("k")).thenReturn(null);

        String result = cache.getOrLoad("k", TYPE_STRING, TTL, () -> "from-db");

        assertThat(result).isEqualTo("from-db");
        verify(valueOps).set(eq("k"), eq("\"from-db\""), any(Duration.class));
    }

    /* ==================== 空值缓存（防穿透） ==================== */

    @Test
    @DisplayName("getOrLoad_loaderReturnsNull_writesNilMarkerAndLoaderRunsOnlyOnce")
    void getOrLoad_loaderReturnsNull_writesNilMarkerAndLoaderRunsOnce() {
        // 第一次：Redis 里没有 key；第二次：读到空值标记
        when(valueOps.get("k")).thenReturn(null, "nil");
        AtomicInteger loaderCalls = new AtomicInteger();

        String first = cache.getOrLoad("k", TYPE_STRING, TTL, countingNullLoader(loaderCalls));
        String second = cache.getOrLoad("k", TYPE_STRING, TTL, countingNullLoader(loaderCalls));

        assertThat(first).isNull();
        assertThat(second).isNull();
        // 关键断言：第二次不应再打到数据库，否则说明空值缓存没起作用
        assertThat(loaderCalls.get()).isEqualTo(1);
        verify(valueOps).set(eq("k"), eq("nil"), any(Duration.class));
    }

    private Supplier<String> countingNullLoader(AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            return null;
        };
    }

    /* ==================== 降级：连接异常 ==================== */

    @Test
    @DisplayName("getOrLoad_redisThrows_fallsBackToLoaderWithoutPropagating")
    void getOrLoad_redisThrows_fallsBackToLoader() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("Connection refused"));

        String result = cache.getOrLoad("k", TYPE_STRING, TTL, () -> "from-db");

        assertThat(result).isEqualTo("from-db");
    }

    @Test
    @DisplayName("write_redisThrows_doesNotPropagateException")
    void write_redisThrows_doesNotPropagate() {
        // set(...) 返回 void，Mockito 对 void 方法只能用 doThrow(...).when(...) 语法，
        // 直接写 when(valueOps.set(...)) 会编译不过
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        cache.write("k", "v", TTL);
        // 没有异常抛出即通过
    }

    /* ==================== 降级：总开关 ==================== */

    @Test
    @DisplayName("getOrLoad_cacheDisabled_shortCircuitsAndNeverTouchesRedis")
    void getOrLoad_cacheDisabled_shortCircuits() {
        RedisCacheService disabledCache = new RedisCacheService(redis, mapper,
                new CacheProperties(false, "p:", TTL, Duration.ofSeconds(60), 0, 500L, 5000L,
                        new CacheProperties.Navigation(null, null, null)));

        String result = disabledCache.getOrLoad("k", TYPE_STRING, TTL, () -> "from-db");

        assertThat(result).isEqualTo("from-db");
        verifyNoInteractions(valueOps);
    }

    /* ==================== 熔断 ==================== */

    @Test
    @DisplayName("getOrLoad_continuousFailures_opensCircuitThenStopsTouchingRedis")
    void getOrLoad_continuousFailures_opensCircuit() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        // 读也要失败：缓存未命中后会尝试回写，而写成功会重置失败计数。
        // 真实场景里 Redis 宕机时读写必然同时失败，所以这里两个操作都得 stub 成异常，
        // 否则构造出的是"读不行但写成功"这种现实中不会出现的矛盾状态
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // 注意：失败计数是按【每次 Redis 操作】而非【每次方法调用】累积的。
        // 一次 getOrLoad 会先读、未命中再回写，也就是两次操作、两次失败；
        // 第 3 次调用的写入阶段计数达到 5，熔断随即打开
        for (int i = 0; i < 3; i++) {
            assertThat(cache.getOrLoad("k", TYPE_STRING, TTL, () -> "from-db")).isEqualTo("from-db");
        }

        assertThat(cache.available()).isFalse();

        // 熔断期间：既不访问 Redis，也依然能正常返回数据（走降级）
        clearInvocations(valueOps);
        String duringCircuit = cache.getOrLoad("k", TYPE_STRING, TTL, () -> "from-db");

        assertThat(duringCircuit).isEqualTo("from-db");
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("recordSuccess_resetsFailureCounter")
    void recordSuccess_resetsFailureCounter() {
        when(valueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(null);

        // 先失败一次，再成功一次，计数器应归零，不应触发熔断
        cache.getOrLoad("k", TYPE_STRING, TTL, () -> "db1");
        cache.getOrLoad("k", TYPE_STRING, TTL, () -> "db2");

        assertThat(cache.available()).isTrue();
    }

    /* ==================== TTL 抖动（防雪崩） ==================== */

    @Test
    @DisplayName("jittered_withinConfiguredPercentRange")
    void jittered_withinRange() {
        // TTL=10min=600000ms，jitter=20% → 落在 [480000, 720000]
        for (int i = 0; i < 200; i++) {
            long millis = cache.jittered(TTL).toMillis();
            assertThat(millis).isBetween(480_000L, 720_000L);
        }
    }

    @Test
    @DisplayName("jittered_percentZero_returnsOriginalTtl")
    void jittered_percentZero_returnsOriginal() {
        RedisCacheService noJitter = new RedisCacheService(redis, mapper,
                new CacheProperties(true, "p:", TTL, Duration.ofSeconds(60), 0, 500L, 5000L,
                        new CacheProperties.Navigation(null, null, null)));

        assertThat(noJitter.jittered(TTL)).isEqualTo(TTL);
    }

    @Test
    @DisplayName("getOrLoad_garbageInCache_treatedAsMissAndKeyDeleted")
    void getOrLoad_garbageCache_treatedAsMiss() {
        // 模拟 Redis 里是另一套结构写的脏数据（字段名对不上会导致 Jackson 异常）
        when(valueOps.get("k")).thenReturn("{not-a-valid-json");

        String result = cache.getOrLoad("k", TYPE_STRING, TTL, () -> "from-db");

        assertThat(result).isEqualTo("from-db");
        verify(redis).delete("k");
    }

    /* ==================== 删除 ==================== */

    @Test
    @DisplayName("deleteByPrefix_redisThrows_doesNotPropagate")
    void deleteByPrefix_redisThrows_doesNotPropagate() {
        when(redis.scan(any())).thenThrow(new RedisConnectionFailureException("down"));

        cache.deleteByPrefix("navigation:");
        // 没有异常抛出即通过；清理失败最坏结果是数据多留一会儿，不能影响写请求的响应
    }

    @Test
    @DisplayName("deleteByPrefix_disabled_doesNotTouchRedis")
    void deleteByPrefix_disabled_doesNotTouch() {
        RedisCacheService disabled = new RedisCacheService(redis, mapper,
                new CacheProperties(false, "p:", TTL, Duration.ofSeconds(60), 0, 500L, 5000L,
                        new CacheProperties.Navigation(null, null, null)));

        disabled.deleteByPrefix("x:");

        verify(redis, never()).scan(any());
    }

    private static CacheProperties enabledProps() {
        return new CacheProperties(true, "test:", TTL, Duration.ofSeconds(60), 20, 500L, 5000L,
                new CacheProperties.Navigation(Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(30)));
    }
}
