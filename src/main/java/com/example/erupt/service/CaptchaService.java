package com.example.erupt.service;

import com.example.erupt.config.AuthProperties;
import com.example.erupt.model.vo.CaptchaVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 图形验证码生成与校验。
 *
 * <p>用 JDK 自带的 AWT 生成 PNG，无需引入图形库依赖。生成过程不需要显示器，
 * 因此在无图形环境的服务器上同样可用（headless 模式下 Graphics2D 正常工作）。</p>
 *
 * <p><b>安全性说明（务必了解取舍）</b>：前端拿到的图片 Base64 里不含答案，
 * 答案只存在 Redis，因此单纯的 OCR 识别仍可能绕过它。验证码的定位是
 * <b>抬高批量自动化的成本</b>，而非不可逾越的屏障——真正的防线是
 * {@link LoginAttemptService} 的失败次数限制。二者叠加才有意义：
 * 验证码让攻击慢下来，失败次数限制让攻击最终停下来。</p>
 *
 * <p>验证码为一次性：校验后立即删除，无论成功失败。防止同一个 key 被反复重放。</p>
 */
@Slf4j
@Service
public class CaptchaService {

    /** 去掉了 0/O、1/I/l 等易混淆字符，降低用户输入错误的概率 */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_LENGTH = 4;

    /** SecureRandom 而非 Random：验证码是安全相关场景，需抗预测 */
    private final SecureRandom random = new SecureRandom();

    private final AuthProperties properties;
    private final StringRedisTemplate redisTemplate;

    public CaptchaService(AuthProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成一张验证码。
     *
     * @return 包含图片 Base64 与答案索引 key 的对象
     */
    public CaptchaVO generate() {
        String code = randomCode();
        String key = UUID.randomUUID().toString().replace("-", "");

        try {
            String imageBase64 = render(code);
            // 写入缓存失败不阻断流程：此时取不到验证码的用户刷新一次即可
            redisTemplate.opsForValue().set(properties.getCaptchaPrefix() + key,
                    code.toUpperCase(), properties.getCaptchaTtl());
            return new CaptchaVO(key, imageBase64);
        } catch (java.io.IOException e) {
            // 渲染失败几乎不可能发生（无图形环境/字体问题），但不能让登录因此不可用
            log.warn("验证码图片渲染失败，key={}, 原因={}", key, e.getMessage());
            return new CaptchaVO(key, null);
        } catch (RuntimeException e) {
            log.warn("验证码写入 Redis 失败，key={}, 原因={}", key, e.getMessage());
            return new CaptchaVO(key, null);
        }
    }

    /**
     * 校验验证码。无论结果如何都会消费掉该验证码，防止重放。
     *
     * @param key  前端回传的答案索引
     * @param code 用户输入
     * @return 通过返回 true；Redis 不可用时降级放行并记录告警
     */
    public boolean verify(String key, String code) {
        if (key == null || key.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        String cacheKey = properties.getCaptchaPrefix() + key;
        try {
            String expected = redisTemplate.opsForValue().get(cacheKey);
            // 一次性消费：先删再比对，避免并发下同一个验证码被用两次
            redisTemplate.delete(cacheKey);
            if (expected == null) {
                log.warn("验证码不存在或已过期，key={}", key);
                return false;
            }
            boolean matched = expected.equalsIgnoreCase(code.trim());
            if (!matched) {
                log.warn("验证码不匹配，key={}", key);
            }
            return matched;
        } catch (RuntimeException e) {
            // Redis 故障时放行：验证码只是辅助防线，不能让它成为登录的单点故障
            log.warn("验证码校验失败，降级放行，key={}, 原因={}", key, e.getMessage());
            return true;
        }
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * 绘制验证码图片并编码为 Base64 的 data URI。
     * 前端可直接把 {@code image} 赋给 {@code <img src>}。
     */
    private String render(String code) throws java.io.IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 浅色背景
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线：增加机器识别难度
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 5; i++) {
                g.setColor(randomColor(180, 230));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            // 噪点
            for (int i = 0; i < 30; i++) {
                g.setColor(randomColor(200, 245));
                g.fillOval(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
            }

            // 字符：每个字符随机字号、颜色与垂直偏移
            int step = WIDTH / (CODE_LENGTH + 1);
            for (int i = 0; i < code.length(); i++) {
                g.setFont(new Font("SansSerif", Font.BOLD, 26 + random.nextInt(6)));
                g.setColor(randomColor(20, 130));
                int x = step * (i + 1) - random.nextInt(6);
                int y = HEIGHT / 2 + 8 - random.nextInt(6);
                g.drawString(String.valueOf(code.charAt(i)), x, y);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private Color randomColor(int min, int max) {
        int range = max - min;
        return new Color(min + random.nextInt(range), min + random.nextInt(range), min + random.nextInt(range));
    }
}