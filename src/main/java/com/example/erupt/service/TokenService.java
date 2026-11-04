package com.example.erupt.service;

import com.example.erupt.config.AuthProperties;
import com.example.erupt.repository.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 登录凭证（Token）的签发、校验与吊销。
 *
 * <p><b>为什么不用 JWT 库？</b>本项目未引入 Spring Security，为一个登录功能引入整套
 * JWT 依赖并不划算；而 Token 本身的原理很简单——「载荷 + 签名」，用 JDK 自带的
 * {@link Mac} 就能实现，不引入任何依赖也没有安全损失。</p>
 *
 * <p><b>Token 结构</b>（{@code base64(payload).base64(signature)}）：
 * <pre>
 * payload   = userId|username|过期时间戳(秒)
 * signature = HMAC-SHA256(payload, 密钥)   ← 服务端用密钥算出，客户端改不了
 * </pre>
 *
 * <p><b>为什么还要落 Redis？</b>纯签名 Token 无法吊销——用户点了"退出登录"，
 * 手里的 Token 在有效期内依然能用。落一份 Redis 会话后，登出即可立即失效，
 * 这对后台管理系统是必需的（否则管理员无法踢下线）。
 * 代价是 Token 变成有状态的，好处是可控可撤销。</p>
 *
 * <p><b>Redis 不可用时的降级</b>：会话查询失败会降级为「只验签名和有效期」。
 * 此时安全性降级为无状态 Token 的水平，但业务仍可用——
 * 这正是缓存/会话类中间件的定位：不可用不能拖垮业务。</p>
 */
@Slf4j
@Service
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** WebSocket 握手临时凭证前缀与有效期（短、一次性，代替把长期 token 放进 URL） */
    private static final String WS_TICKET_PREFIX = "ws:ticket:";
    private static final Duration WS_TICKET_TTL = Duration.ofSeconds(60);

    /** Token 载荷分隔符。用 | 而非 , 是因为业务字段里不会出现它 */
    private static final String DELIMITER = "|";

    private final AuthProperties properties;
    private final StringRedisTemplate redisTemplate;

    public TokenService(AuthProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 为用户签发登录凭证。
     *
     * @param user 登录成功的用户
     * @return 可直接下发给前端的 Token 字符串
     */
    public String issue(User user) {
        long expiresAt = Instant.now().plusSeconds(properties.getTokenTtl().toSeconds()).getEpochSecond();
        String payload = String.join(DELIMITER,
                nullSafe(user.getId()),
                nullSafe(user.getUsername()),
                String.valueOf(expiresAt));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(encodedPayload));

        String token = encodedPayload + "." + signature;

        // 会话落 Redis，TTL 与 Token 有效期一致；写失败只记日志，不阻断登录
        try {
            redisTemplate.opsForValue().set(sessionKey(token), user.getId(),
                    properties.getTokenTtl());
        } catch (RuntimeException e) {
            log.warn("登录会话写入 Redis 失败，Token 降级为无状态模式，username={}, 原因={}",
                    user.getUsername(), e.getMessage());
        }
        return token;
    }

    /**
     * 校验 Token 并返回其中携带的用户身份。
     *
     * <p>校验顺序刻意设计为「先验签、再查会话」：验签是纯计算、极快，
     * 而查 Redis 是一次网络往返。绝大多数非法请求会在验签阶段就被拦下，
     * 不会给 Redis 增加无谓压力。</p>
     *
     * @return 校验通过返回身份信息，否则为空
     */
    public Optional<TokenIdentity> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return Optional.empty();
        }
        String encodedPayload = token.substring(0, separator);
        String providedSignature = token.substring(separator + 1);

        // 1) 验签：用常量时间比较，防止时序攻击
        //    expected 是 HMAC 的原始字节；provided 需要先 Base64 解码回原始字节再比。
        //    客户端传来的签名可能根本不是合法 Base64（伪造请求常见），
        //    必须在解码处兜住并当作"校验不通过"，否则会抛 IllegalArgumentException 变成 500，
        //    既暴露了内部实现，又让探测者能区分"格式错"与"签名错"。
        byte[] expected = signatureOf(encodedPayload);
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(providedSignature);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            return Optional.empty();
        }

        // 2) 解析载荷并检查有效期
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 3) {
            return Optional.empty();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (Instant.now().getEpochSecond() >= expiresAt) {
            return Optional.empty();
        }

        // 3) 查会话：Redis 可用时以服务端记录为准，支持登出后立即失效
        String userId = parts[0];
        try {
            Boolean exists = redisTemplate.hasKey(sessionKey(token));
            if (Boolean.FALSE.equals(exists)) {
                return Optional.empty();
            }
        } catch (RuntimeException e) {
            log.warn("会话校验失败，降级为仅验签模式，userId={}, 原因={}", userId, e.getMessage());
        }
        return Optional.of(new TokenIdentity(userId, parts[1], expiresAt));
    }

    /**
     * 吊销一个 Token（退出登录）。Redis 不可用时静默忽略——
     * 此时 Token 退化为无状态行为，不应让退出登录直接报错。
     */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(sessionKey(token));
        } catch (RuntimeException e) {
            log.warn("吊销登录会话失败，token 前缀={}", maskToken(token));
        }
    }

    /**
     * 吊销某个用户的<b>全部</b>登录会话。
     *
     * <p><b>为什么需要</b>：会话 key 是按 Token 哈希存的（{@code auth:token:<sha256>}），
     * 手里只有一个 token 时才删得掉它。改密码这个动作恰恰拿不到用户的其它 token——
     * 于是出现「管理员重置了密码，攻击者手里的旧 Token 还能再用 2 小时」的窗口。
     * 密码变更的意义就是让旧凭据失效，不清理会话等于只做了一半。</p>
     *
     * <p><b>实现代价</b>：只能 SCAN 出所有会话 key 再逐个比对 value 里的 userId。
     * 会话量级是「在线用户数」，与业务数据量无关，这个开销可以接受；
     * 若将来会话规模显著增长，应改为维护 {@code auth:sessions:<userId>} 集合做二级索引。</p>
     *
     * <p>Redis 不可用时静默忽略：此时 Token 本就退化为无状态模式，
     * 不该因为清理会话失败而让改密操作报错。</p>
     *
     * @return 实际删除的会话数；Redis 异常时返回 0
     */
    public long revokeAllForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        long deleted = 0L;
        String pattern = properties.getTokenPrefix() + "*";
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(pattern)
                .count(500)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (userId.equals(redisTemplate.opsForValue().get(key))) {
                    redisTemplate.delete(key);
                    deleted++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("清理用户全部会话失败，userId={}, 原因={}", userId, e.getMessage());
            return deleted;
        }
        if (deleted > 0) {
            log.info("已吊销用户全部登录会话，userId={}, 会话数={}", userId, deleted);
        }
        return deleted;
    }

    /**
     * 为 WebSocket 握手签发一次性临时凭证。
     *
     * <p>浏览器 WebSocket 不能自定义请求头，长期 token 只能放 URL 查询串、会进访问日志。
     * 用 60 秒有效、只能用一次的 ticket 代替，即便泄露也很快失效且不可复用。</p>
     */
    public String issueWsTicket(String userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        try {
            redisTemplate.opsForValue().set(WS_TICKET_PREFIX + ticket, userId, WS_TICKET_TTL);
        } catch (RuntimeException e) {
            log.warn("签发 WebSocket 临时凭证失败，userId={}, 原因={}", userId, e.getMessage());
        }
        return ticket;
    }

    /**
     * 消费一次性临时凭证并返回用户主键。无论成功失败，凭证都立即失效（一次性）。
     * 用 getAndDelete 原子地「读并删」，避免并发下同一 ticket 被用两次。
     */
    public Optional<String> consumeWsTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        try {
            String userId = redisTemplate.opsForValue().getAndDelete(WS_TICKET_PREFIX + ticket);
            return userId == null || userId.isBlank() ? Optional.empty() : Optional.of(userId);
        } catch (RuntimeException e) {
            log.warn("校验 WebSocket 临时凭证失败，原因={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 计算会话在 Redis 中的 key。用 token 的哈希而非原文，避免 key 里出现可读凭证 */
    private String sessionKey(String token) {
        return properties.getTokenPrefix() + sha256Hex(token);
    }

    private byte[] signatureOf(String encodedPayload) {
        return hmac(encodedPayload);
    }

    private byte[] hmac(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getTokenSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 密钥为空或算法不可用属于配置错误，必须让登录失败而不是静默放行
            throw new IllegalStateException("Token 签名计算失败，请检查 app.auth.token-secret 配置", e);
        }
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 日志里只保留首尾各 4 位，避免完整凭证进入日志文件 */
    private static String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Token 中携带并已通过校验的身份信息。
     *
     * @param userId    用户主键
     * @param username  登录账号
     * @param expiresAt 过期时间戳（秒）
     */
    public record TokenIdentity(String userId, String username, long expiresAt) {
    }
}