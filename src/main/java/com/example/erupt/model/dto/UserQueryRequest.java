package com.example.erupt.model.dto;

/**
 * 用户列表查询条件（视图层入参模型）。
 *
 * @param keyword  模糊匹配账号/昵称
 * @param status   状态：1 启用，0 禁用
 * @param page     页码，从 1 开始
 * @param pageSize 每页条数
 */
public record UserQueryRequest(String keyword, Integer status, Integer page, Integer pageSize) {

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
