package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 数据表视图对象（仅展示，来源于 {@code information_schema.TABLES}）。
 *
 * <p>注意：{@code tableRows} 是 MySQL 的统计估算值（InnoDB 采样得到），
 * 与精确的 {@code SELECT COUNT(*)} 可能存在数量级差异，<b>仅用于概览</b>，
 * 真实分页时应当用 {@code SELECT COUNT(*)} 取精确值。</p>
 *
 * @param schemaName   所属库
 * @param tableName    表名
 * @param tableComment 表注释
 * @param tableRows    估算行数
 * @param dataLength   数据大小（字节）
 * @param indexLength  索引大小（字节）
 * @param engine       存储引擎
 * @param collation    字符集排序规则
 * @param createTime   表创建时间
 */
public record TableVO(
        String schemaName,
        String tableName,
        String tableComment,
        Long tableRows,
        Long dataLength,
        Long indexLength,
        String engine,
        String collation,
        LocalDateTime createTime
) {
}
