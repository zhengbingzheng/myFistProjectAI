package com.example.erupt.model.dto;

/**
 * 知识列表查询条件（视图层入参模型）。
 *
 * @param keyword  标题或正文模糊匹配（正文为 mediumtext，LIKE 走全表扫描，数据量大时建议改全文检索）
 * @param type     类型精确匹配，对应 {@code knowledge_type}
 * @param page     页码，从 1 开始
 * @param pageSize 每页条数
 */
public record KnowledgeQueryRequest(String keyword, String type, Integer page, Integer pageSize) {

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
