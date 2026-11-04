package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统操作日志实体，对应表 {@code t_operation_log}。
 *
 * <p>由 {@code OperationLogInterceptor} 在请求结束后异步写入，只增不改：
 * 没有 update/del_flag 一类的字段，查询接口也是只读的。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperationLog {

    /** 主键，字符串（64 位），应用层生成 UUID */
    private String id;
    /** 操作模块：URI 第二段（auth/users/knowledge/logs…） */
    private String module;
    /** 操作类型：query/create/update/delete/login/logout/upload/download/other */
    private String operation;
    /** HTTP 方法 */
    private String requestMethod;
    /** 请求路径（不含 query） */
    private String requestUri;
    /** 查询参数原文；不含请求体（体里可能有密码等敏感字段） */
    private String queryString;
    /** 操作人用户ID；匿名/登录失败时为空 */
    private String operatorId;
    /** 操作人账号 */
    private String operatorName;
    /** 客户端 IP（优先 X-Forwarded-For 首段） */
    private String operatorIp;
    /** 结果：1 成功，0 失败（HTTP 状态码 >= 400） */
    private Integer result;
    /** HTTP 状态码 */
    private Integer statusCode;
    /** 处理耗时（毫秒） */
    private Long costMs;
    /** 链路追踪 ID，与日志文件的 %X{traceId} 对应 */
    private String traceId;
    private LocalDateTime createTime;
}
