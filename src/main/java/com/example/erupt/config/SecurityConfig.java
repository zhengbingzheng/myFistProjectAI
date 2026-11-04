package com.example.erupt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证相关的 Bean 装配。
 *
 * <p>本项目未引入 Spring Security，因此这里手动声明 {@link PasswordEncoder}。
 * Spring Boot 在 classpath 检测到 spring-security-crypto 时本来会自动注册一个，
 * 但显式声明能保证"无论自动配置是否生效，密码编码器都存在"——
 * 认证链路一旦因为缺 Bean 起不来，影响的是整个系统能否登录。</p>
 */
@Configuration
public class SecurityConfig {

    /**
     * BCrypt 密码编码器。
     *
     * <p>强度用默认值 10：约 100ms 一次哈希，足以让暴力破解成本高昂，
     * 又不至于让每次登录都有明显延迟。若未来登录 QPS 很高，
     * 应升级为自适应哈希（Spring Security 的 DelegatingPasswordEncoder），
     * 而不是简单调低强度。</p>
     *
     * <p>注意：BCrypt 自带盐（随机盐直接编码进哈希串），
     * 因此相同明文每次生成的哈希都不同，比对时必须用 matches() 而而非字符串相等。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}