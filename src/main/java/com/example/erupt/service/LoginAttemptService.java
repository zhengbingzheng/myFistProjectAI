package com.example.erupt.service;

import com.example.erupt.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录失败次数限制（防暴力破解）。
 *
 * <p>策略：账号维度计数 + 滑动窗口。连续 {@code loginMaxFailures} 次失败后锁定，
 * 锁定时长 = 计数窗口长度（{@code loginFailWindow}）。
 * 锁定期间即使用户输入了正确密码也拒绝校验，并返回剩余锁定时间。</p>
 *
 * <p>计数使用 Redis 而非内存：单机内存版在多实例部署时变成按实例计数，
 * 攻击者只要轮询实例就能避开；Redis 保证多实例共享同一份计数。</p>
 *
 * <p>Redis 不可用时降级为"只读放行"：宁可在极端情况下让防爆裂失效，
 * 也不可让 Redis 故障拖垮登录主流程（登录失败的副作用是再多一次失败而已）。</p>
 */
@Slf4j
@Service
public class LoginAttemptService {

    private final AuthProperties properties;
    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(AuthProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查账号是否被锁定，以及剩余锁定时间。
     *
     * @return 被锁定时返回剩余时长，未锁定返回空
     */
    public java.util.Optional<Duration> lockRemaining(String username) {
        String key = properties.getLoginLockPrefix() + username;
        try {
            Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
            if (ttl == null || ttl <= 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            log.warn("查询登录锁定状态失败，降级放行，username={}, 原因={}", username, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 记录一次失败。连续失败达到阈值后写入锁定标记。
     *
     * @return 失败后的连续次数（未锁定时），或 -1（已锁定，避免暴露精确次数）
     */
    public int recordFailure(String username) {
        String failKey = properties.getLoginFailPrefix() + username;
        String lockKey = properties.getLoginLockPrefix() + username;
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
                log.warn("账号登录失败 {} 次，已锁定 {} 分钟，username={}", count,
                        properties.getLoginFailWindow().toMinutes(), username);
                return -1;
            }
            return count.intValue();
        } catch (RuntimeException e) {
            log.warn("记录登录失败次数失败，username={}, 原因={}", username, e.getMessage());
            return 0;
        }
    }

    /** 登录成功后清除失败计数与锁定标记。 */
    public void recordSuccess(String username) {
        try {
            redisTemplate.delete(properties.getLoginFailPrefix() + username);
            redisTemplate.delete(properties.getLoginLockPrefix() + username);
        } catch (RuntimeException e) {
            log.warn("清除登录失败计数失败，username={}, 原因={}", username, e.getMessage());
        }
    }

    /**
     * 是否需要输入验证码（连续失败 2 次及以上时启用，正常用户不受打扰）。
     */
    public boolean needCaptcha(String username) {
        if (properties.isCaptchaRequired()) {
            return true;
        }
        try {
            String countStr = redisTemplate.opsForValue().get(properties.getLoginFailPrefix() + username);
            int count = countStr == null ? 0 : Integer.parseInt(countStr);
            return count >= 2;
        } catch (RuntimeException e) {
            // NumberFormatException 是 RuntimeException 子类，这里一并兜住
            return false;
        }
    }
}