package com.example.erupt.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc / OpenAPI 接口文档配置。
 *
 * <p>启动后可访问：</p>
 * <ul>
 *     <li>{@code /swagger-ui.html} —— 可视化接口文档与调试页面</li>
 *     <li>{@code /v3/api-docs} —— OpenAPI 规范 JSON，可交给前端生成 TS 类型</li>
 * </ul>
 *
 * <p>接口说明通过 {@code @Operation} / {@code @Schema} 注解补充；不加注解也能生成，
 * 只是显示字段名与类名。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eruptExampleOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("erupt-example 接口文档")
                        .description("Spring Boot 4 + MyBatis + MySQL 分层示例工程：用户 / 知识 / 导航三个模块")
                        .version("0.0.1")
                        .license(new License().name("仅限内部使用")));
    }
}
