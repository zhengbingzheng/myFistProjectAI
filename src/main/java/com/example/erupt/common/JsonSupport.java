package com.example.erupt.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 全局共享的 JSON 序列化器。
 *
 * <p><b>为什么需要这个类</b>：本项目的 Spring 上下文没有把 {@code ObjectMapper} 暴露成 bean，
 * 需要序列化的地方只能自建实例。而 {@code new ObjectMapper()} 是一个"裸"实例——
 * 它<b>不注册任何扩展模块</b>，遇到 {@code LocalDateTime} 会直接抛
 * {@code InvalidDefinitionException}。</p>
 *
 * <p>这个坑真实发生过：WebSocket 未读推送就是在这里静默失败的。
 * 异常被 catch 住只留一条 warn 日志，外表现象是"消息永远推不到对端"，
 * 而连接、心跳、HTTP 接口全都正常——极难从现象反推。</p>
 *
 * <p><b>关闭 {@code WRITE_DATES_AS_TIMESTAMPS}</b>：让时间以 ISO-8601 字符串输出，
 * 与 Spring MVC 全局 Jackson 的行为保持一致。否则同一个字段在 HTTP 接口里是
 * {@code "2026-10-22T15:21:59"}，在 WebSocket 推送里却成了
 * {@code [2026,10,22,15,21,59]}，前端的 {@code replace('T', ' ')} 会直接失效。</p>
 */
public final class JsonSupport {

    /** 线程安全，可作为全局单例复用 */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonSupport() {
        // 工具类不允许实例化
    }
}
