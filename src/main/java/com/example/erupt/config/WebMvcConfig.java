package com.example.erupt.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册鉴权拦截器。
 *
 * <p>这里拦截 {@code /api/**} 而不是 {@code /**}，是为了让
 * Swagger / Knife4j 等文档端点以及静态资源不受影响——
 * 文档页通常不需要登录就能查看，强行拦截会让联调变得很麻烦。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final MenuAccessInterceptor menuAccessInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .order(1);

        // 菜单鉴权必须排在身份鉴权之后：先确认"你是谁"，再判断"你能不能访问"。
        // 顺序颠倒会让 AuthContext 拿不到用户ID，拦截器失去判定依据。
        registry.addInterceptor(menuAccessInterceptor)
                .addPathPatterns("/api/**")
                .order(2);
    }
}