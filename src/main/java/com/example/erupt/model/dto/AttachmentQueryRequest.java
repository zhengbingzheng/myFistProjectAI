package com.example.erupt.model.dto;

import java.time.LocalDateTime;

/**
 * 附件分页查询条件（视图层入参）。正常列表与回收站列表共用同一套条件，
 * 由服务层决定查 {@code del_flag = '0'} 还是 {@code del_flag = '1'}。
 *
 * <p>分页默认值在 DTO 内修正而非靠校验注解拦截：页码错误属于"用户没传/传歪"，
 * 直接给默认值比返回 400 更符合列表接口的语义。</p>
 *
 * @param originalName 文件名模糊匹配（需由服务层做 LIKE 转义）
 * @param fileType     分类精确匹配，取值 image/document/video/audio/archive/other
 * @param startTime    上传时间起（含），对应 create_time
 * @param endTime      上传时间止（含），对应 create_time
 * @param page         页码，从 1 开始
 * @param pageSize     每页条数
 */
public record AttachmentQueryRequest(String originalName,
                                     String fileType,
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
