package com.example.erupt.service;

import com.example.erupt.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败次数限制（防暴力破解）。
 *
 * <p>策略：账号 + 客户端 IP 维度计数 + 滑动窗口。连续 {@code loginMaxFailures} 次失败后锁定，
 * 锁定时长 = 计数窗口长度（{@code loginFailWindow}）。
 * 锁定期间即使用户输入了正确密码也拒绝校验，并返回剩余锁定时间。</p>
 *
 * <p><b>为什么按「账号 + IP」而不是仅按账号？</b>仅按账号计数时，攻击者只需用错误密码
 * 连续失败几次，就能把任意账号（包括管理员）锁死，造成拒绝服务。加上 IP 维度后，
 * 攻击者只能锁定「该账号在攻击者 IP 上」的登录，真正的用户从自己 IP 登录不受影响。</p>
 *
 * <p>计数使用 Redis 而非内存：单机内存版在多实例部署时变成按实例计数，
 * 攻击者只要轮询实例就能避开；Redis 保证多实例共享同一份计数。</p>
 *
 * <p><b>Redis 不可用时降级为进程内计数</b>：多实例下不如 Redis 精确，但单机部署时
 * 防爆破仍然有效，远比「完全不设防」强。登录主流程绝不会因 Redis 故障而失败。</p>
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** Redis 故障时的进程内兜底计数：username|ip -> 失败次数（仅单实例有效） */
    private final Map<String, AtomicInteger> localFailures = new ConcurrentHashMap<>();

    /** Redis 故障时的进程内兜底锁定：username|ip -> 锁定到期时间（epoch millis） */
    private final Map<String, Long> localLockUntil = new ConcurrentHashMap<>();

    private final AuthProperties properties;
    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(AuthProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查账号在当前 IP 上是否被锁定，以及剩余锁定时间。
     *
     * @return 被锁定时返回剩余时长，未锁定返回空
     */
    public java.util.Optional<Duration> lockRemaining(String username, String clientIp) {
        String scope = scope(username, clientIp);
        String key = properties.getLoginLockPrefix() + scope;
        try {
            Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
            if (ttl == null || ttl <= 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            Long until = localLockUntil.get(scope);
            if (until == null || until <= System.currentTimeMillis()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(Duration.ofMillis(until - System.currentTimeMillis()));
        }
    }

    /**
     * 记录一次失败。连续失败达到阈值后写入锁定标记。
     *
     * @return 失败后的连续次数（未锁定时），或 -1（已锁定，避免暴露精确次数）
     */
    public int recordFailure(String username, String clientIp) {
        String scope = scope(username, clientIp);
        String failKey = properties.getLoginFailPrefix() + scope;
        String lockKey = properties.getLoginLockPrefix() + scope;
        try {
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count == null) {
                return 0;
            }
            // 第一次失败时设过期；后续 INCR 不重置 TTL，让窗口自然滚动
            if (count == 1L) {
                redisTemplate.expire(failKey, properties.getLoginFailWindow());
            }
            if (count >= properties.getLoginMaxFailures()) {
                // 锁定时长 = 窗口时长，到点后从 0 重新计数
                redisTemplate.opsForValue().set(lockKey, "1", properties.getLoginFailWindow());
                log.warn("账号登录失败 {} 次，已锁定 {} 分钟，username={}, ip={}", count,
                        properties.getLoginFailWindow().toMinutes(), username, clientIp);
                return -1;
            }
            return count.intValue();
        } catch (RuntimeException e) {
            // Redis 不可用：进程内兜底，保证单机部署下防爆破仍有效
            log.warn("记录登录失败次数失败，降级为进程内计数，username={}, ip={}, 原因={}",
                    username, clientIp, e.getMessage());
            int count = localFailures.computeIfAbsent(scope, k -> new AtomicInteger()).incrementAndGet();
            if (count >= properties.getLoginMaxFailures()) {
                localLockUntil.put(scope,
                        System.currentTimeMillis() + properties.getLoginFailWindow().toMillis());
                log.warn("账号登录失败 {} 次（进程内兜底），已锁定，username={}, ip={}", count, username, clientIp);
                return -1;
            }
            return count;
        }
    }

    /** 登录成功后清除失败计数与锁定标记（Redis 与进程内兜底一并清）。 */
    public void recordSuccess(String username, String clientIp) {
        String scope = scope(username, clientIp);
        try {
            redisTemplate.delete(properties.getLoginFailPrefix() + scope);
            redisTemplate.delete(properties.getLoginLockPrefix() + scope);
        } catch (RuntimeException e) {
            log.warn("清除登录失败计数失败，username={}, 原因={}", username, e.getMessage());
        }
        localFailures.remove(scope);
        localLockUntil.remove(scope);
    }

    /**
     * 是否需要输入验证码（连续失败 2 次及以上时启用，正常用户不受打扰）。
     */
    public boolean needCaptcha(String username, String clientIp) {
        if (properties.isCaptchaRequired()) {
            return true;
        }
        try {
            String countStr = redisTemplate.opsForValue().get(properties.getLoginFailPrefix() + scope(username, clientIp));
            int count = countStr == null ? 0 : Integer.parseInt(countStr);
            return count >= 2;
        } catch (RuntimeException e) {
            AtomicInteger local = localFailures.get(scope(username, clientIp));
            return local != null && local.get() >= 2;
        }
    }

    /**
     * 计数维度：账号 + 客户端 IP。用 | 分隔——账号经校验只含字母数字下划线，不会与 | 冲突。
     */
    private static String scope(String username, String clientIp) {
        return username + "|" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
    }
}
