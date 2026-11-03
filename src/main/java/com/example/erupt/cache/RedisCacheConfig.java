package com.example.erupt.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 缓存的基础设施装配。
 *
 * <p>【为什么不用 Spring Boot 自动配置的那个 ObjectMapper】
 * 缓存是写进 Redis 的持久数据，序列化契约必须稳定；而 MVC 用的 ObjectMapper 会
 * 为了接口兼容性被反复调整（日期格式、字段过滤、null 处理等）。两者共用一个实例时，
 * 某次 Web 层的调整就足以让 Redis 里的历史数据全部反序列化失败。</p>
 *
 * <p>【为什么这里 import 的是 tools.jackson 而不是 com.fasterxml】
 * Spring Boot 4.1.1 管理的是 Jackson 3，包名从 {@code com.fasterxml.jackson} 改成了
 * {@code tools.jackson}，且 API 有破坏性调整：
 * <ul>
 *   <li>{@code ObjectMapper} 改为不可变，配置项只能通过 {@code JsonMapper.builder()} 传入</li>
 *   <li>数据绑定方法移到了 {@code ObjectReader} / {@code ObjectWriter}，
 *       不再有 {@code mapper.readValue(...)} 这样的直接调用</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS} 已移除，默认输出 ISO-8601 字符串</li>
 * </ul>
 * 当前 classpath 上 Jackson 2 与 3 并存（Jackson 2 由其它依赖引入），
 * 因此这里的 import 必须写全不可逆意识的包名，不能凭 Jackson 2 的习惯直接改个 version 了事。</p>
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class RedisCacheConfig {

    /**
     * 缓存专用 ObjectMapper。
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} 必须关掉：VO 新增字段后，Redis 里按旧结构写的
     * 缓存没有该字段，此时应当容忍并按未命中回源重写，而不是抛异常。
     * 否则每次发版都会伴随一波缓存读取报错。</p>
     */
    @Bean("cacheObjectMapper")
    public ObjectMapper cacheObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /*
     * 这里没有自定义 RedisTemplate：Spring Boot 会根据 spring.data.redis.* 自动装配
     * RedisTemplate<Object,Object> 与 StringRedisTemplate。本模块统一用 StringRedisTemplate
     * （键值都是字符串，对象序列化由上面的 ObjectMapper 负责），避免 JDK 序列化带来的
     * "\xac\xed\x00\x05t\x00" 乱码前缀与跨语言不可读问题。
     */
}
