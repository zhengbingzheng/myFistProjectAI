package com.example.erupt.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Web 层配置：注册鉴权拦截器，并给 MVC 异步任务一条有界线程池。
 *
 * <p>这里拦截 {@code /api/**} 而不是 {@code /**}，是为了让
 * Swagger / Knife4j 等文档端点以及静态资源不受影响——
 * 文档页通常不需要登录就能查看，强行拦截会让联调变得很麻烦。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final MenuAccessInterceptor menuAccessInterceptor;
    private final OperationLogInterceptor operationLogInterceptor;
    private final AsyncTaskExecutor mvcAsyncExecutor;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        MenuAccessInterceptor menuAccessInterceptor,
                        OperationLogInterceptor operationLogInterceptor,
                        @Qualifier("mvcAsyncExecutor") AsyncTaskExecutor mvcAsyncExecutor) {
        this.authInterceptor = authInterceptor;
        this.menuAccessInterceptor = menuAccessInterceptor;
        this.operationLogInterceptor = operationLogInterceptor;
        this.mvcAsyncExecutor = mvcAsyncExecutor;
    }

    /**
     * 问数把模型等待丢到这条池子上，Tomcat 线程可以先回去。
     * 队列有上限，满了直接拒绝，而不是无界堆积。
     */
    @Bean(name = "mvcAsyncExecutor", destroyMethod = "shutdown")
    public static ThreadPoolTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor);
        configurer.setDefaultTimeout(150_000);
    }

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

        // 操作日志排在最后（order=3）：preHandle 只记开始时间（必然放行），
        // afterCompletion 在整条链路结束后拿最终状态码，位置越靠后覆盖越完整。
        // 注意它位于鉴权之后，鉴权短路的请求（401/403）不会产生日志，取舍见其类注释。
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/**")
                .order(3);
    }
}