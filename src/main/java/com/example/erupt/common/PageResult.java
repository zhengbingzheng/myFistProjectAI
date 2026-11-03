package com.example.erupt.common;

import java.util.List;

/**
 * 分页结果封装。
 *
 * @param items    当前页数据
 * @param total    总记录数
 * @param page     当前页码（从 1 开始）
 * @param pageSize 每页条数
 */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int pageSize) {
        return new PageResult<>(items, total, page, pageSize);
    }
}
