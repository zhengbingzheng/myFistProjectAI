package com.example.erupt.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用附件模块的配置绑定。
 *
 * <p>单独拎出来是为了让 {@link AttachmentProperties} 保持纯净的配置载体，
 * 不与具体业务逻辑耦合。</p>
 */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentConfig {
}
