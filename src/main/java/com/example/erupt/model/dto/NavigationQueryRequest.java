package com.example.erupt.model.dto;

/**
 * 导航列表查询条件（视图层入参模型）。
 *
 * @param keyword  名称或描述模糊匹配
 * @param cat      分类精确匹配
 * @param tag      标签精确匹配
 * @param page     页码，从 1 开始
 * @param pageSize 每页条数
 */
public record NavigationQueryRequest(String keyword, String cat, String tag, Integer page, Integer pageSize) {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    public int pageOrDefault() {
        return (page == null || page < 1) ? DEFAULT_PAGE : page;
    }

    public int pageSizeOrDefault() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
