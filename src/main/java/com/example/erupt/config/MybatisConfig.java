package com.example.erupt.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MyBatis / 数据层装配配置。
 *
 * <p>Mapper 扫描范围限定在 {@code com.example.erupt.repository.mapper}，避免扫描到其他包。</p>
 */
@Configuration
@EnableTransactionManagement
@MapperScan(basePackages = "com.example.erupt.repository.mapper")
public class MybatisConfig {
}
