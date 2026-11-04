package com.example.erupt.model.vo;

/**
 * 数据库（Schema）视图对象。
 *
 * <p>仅用于展示，字段全部来源于 {@code information_schema.SCHEMATA} 实时查询，<b>不落任何业务表</b>。</p>
 *
 * @param schemaName     库名
 * @param charset        默认字符集，如 {@code utf8mb4}
 * @param collation      默认排序规则，如 {@code utf8mb4_0900_ai_ci}
 * @param tableCount     该库下的表数量（实时统计）
 * @param totalSizeBytes 库内所有表的数据+索引总字节数（实时聚合，仅 InnoDB 可见）
 * @param systemSchema   是否系统库（mysql/information_schema/performance_schema/sys）
 */
public record DatabaseVO(
        String schemaName,
        String charset,
        String collation,
        Integer tableCount,
        Long totalSizeBytes,
        boolean systemSchema
) {
}
