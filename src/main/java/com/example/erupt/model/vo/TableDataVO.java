package com.example.erupt.model.vo;

import java.util.List;
import java.util.Map;

/**
 * 任意表的查询结果。
 *
 * <p>由于列是动态的（用户在前端选什么表，列就跟着变），后端把"列描述"和"行数据"分开传输：</p>
 * <ul>
 *     <li>{@link #columns}：按 {@code ORDINAL_POSITION} 排序，描述每一列的元信息；</li>
 *     <li>{@link #rows}：每行是一个 map，键是 {@link ColumnMeta#name}，值是数据库原始值
 *         （未做类型强转，避免 BLOB/JSON/Date 等丢失精度；由前端按列类型自行处理）。</li>
 * </ul>
 *
 * <p><b>大小防护</b>：调用方必须在 service 层限定 {@code pageSize}（默认 10，上限 100），
 * 避免一次性拉回大表造成 OOM。</p>
 */
public record TableDataVO(
        List<ColumnMeta> columns,
        List<Map<String, Object>> rows,
        long total,
        int page,
        int pageSize
) {

    /**
     * 列元信息，来源于 {@code information_schema.COLUMNS}。
     *
     * <p>名称/类型为只读字符串，便于前端表格直接渲染；默认值、是否可空等做最低限度保留，</p>
     */
    public record ColumnMeta(
            String name,
            String type,
            String columnType,
            String comment,
            String nullable,
            String columnKey,
            Object defaultValue,
            String extra,
            int ordinalPosition
    ) {
    }
}
