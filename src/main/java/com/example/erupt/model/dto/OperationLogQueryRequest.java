package com.example.erupt.model.dto;

import java.time.LocalDateTime;

/**
 * 系统日志分页查询条件（视图层入参）。
 *
 * <p>分页默认值在 DTO 内修正而非靠校验注解拦截：页码错误属于"用户没传/传歪"，
 * 直接给默认值比返回 400 更符合列表接口的语义。</p>
 *
 * @param keyword    关键词：URI / 操作人账号 / IP / traceId 模糊匹配
 * @param module     模块精确匹配（URI 第二段，如 auth/users/knowledge）
 * @param operation  操作类型精确匹配：query/create/update/delete/login/logout/upload/download/other
 * @param result     结果过滤：1 成功，0 失败；空 = 全部
 * @param startTime  发生时间起（含）
 * @param endTime    发生时间止（含）
 * @param page       页码，从 1 开始
 * @param pageSize   每页条数
 */
public record OperationLogQueryRequest(String keyword,
                                       String module,
                                       String operation,
                                       Integer result,
                                       LocalDateTime startTime,
                                       LocalDateTime endTime,
                                       Integer page,
                                       Integer pageSize) {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    /** 页码非法或未传时回落到第一页。 */
    public int pageOrDefault() {
        return (page == null || page < 1) ? DEFAULT_PAGE : page;
    }

    /** 每页条数非法时给默认值，超过上限则收敛到上限，避免一次捞过多数据。 */
    public int pageSizeOrDefault() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
