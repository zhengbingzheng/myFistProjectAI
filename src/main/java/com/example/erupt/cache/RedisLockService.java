package com.example.erupt.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Redis 的简单互斥锁，当前唯一用途是<b>缓存击穿保护</b>。
 *
 * <p>击穿场景：某个热点 key 失效的瞬间，大量请求同时发现"未命中"，于是一起冲向数据库
 * 查同一份数据——这份压力本该只由一次请求承担。用锁让第一个进去的请求负责回源写缓存，
 * 其余请求等一小会儿后直接读缓存即可。</p>
 *
 * <p>【定位声明】这把锁<b>只用于缓解并发压力，不承担数据正确性</b>。
 * 因此任何 Redis 异常都一律按"没拿到锁"处理，让请求照常继续回源，绝不因为锁而阻塞或失败。
 * 如果需要真正意义上的分布式锁（防止重复扣款、防重复提交等），应当引入 Redisson，
 * 它的 {@code RLock} 带看门狗自动续期，而本类的锁在 holder 执行超过租约后会自动过期失效。</p>
 */
@Slf4j
@Service
public class RedisLockService {

    /**
     * 释放锁的 Lua 脚本：先比对 token 再删除。
     *
     * <p>必须写成一个脚本的原因：持有锁的时间若超过租约，锁会自动过期并被别的线程抢到。
     * 此时若直接 {@code DEL}，删掉的其实是别人的锁。 {@code GET → 比对 → DEL} 三步
     * 只有在一个脚本里执行才具备原子性。</p>
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final CacheProperties properties;

    public RedisLockService(StringRedisTemplate redis, CacheProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 尝试加锁，使用配置里的默认租约时长。
     *
     * @return 抢到时返回 token（释放时需原样传回以便比对持有者身份），未抢到返回 {@code Optional.empty()}
     */
    public Optional<String> tryLock(String lockKey) {
        return tryLock(lockKey, Duration.ofMillis(properties.lockLeaseMsOr()));
    }

    /**
     * 尝试加锁。
     *
     * @param lease 租约时长。必须设，目的是线程卡死时锁也能自动释放，
     *              否则会变成永久死锁，届时所有请求都无法回源
     */
    public Optional<String> tryLock(String lockKey, Duration lease) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, lease);
            return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
        } catch (RuntimeException e) {
            // 拿不到锁的后果仅是"多查一次库"，可以接受，因此这里不向上抛
            log.warn("获取分布式锁异常，按未获取处理. lockKey={}, 原因={}", lockKey, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 释放锁。只释放自己持有的那把。
     */
    public void unlock(String lockKey, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(lockKey), token);
        } catch (RuntimeException e) {
            log.warn("释放分布式锁异常，锁将依赖租约自动过期. lockKey={}, 原因={}", lockKey, e.getMessage());
        }
    }
}
