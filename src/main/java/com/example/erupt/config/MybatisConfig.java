package com.example.erupt.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis / 数据层装配配置。
 *
 * <p>Mapper 扫描范围限定在 {@code com.example.erupt.repository.mapper}，避免扫描到其他包。</p>
 *
 * <p>事务管理无需 {@code @EnableTransactionManagement}：
 * mybatis-spring-boot-starter 引入的 Spring Boot 自动配置（TransactionAutoConfiguration）
 * 已经开启了注解事务，这里的 @Transactional（服务层事务边界）由它装配。</p>
 */
@Configuration
@MapperScan(basePackages = "com.example.erupt.repository.mapper")
public class MybatisConfig {
}
