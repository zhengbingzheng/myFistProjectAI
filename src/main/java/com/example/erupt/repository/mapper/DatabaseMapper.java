package com.example.erupt.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 数据库只读浏览的数据访问接口。
 *
 * <p>本接口的所有方法都查询 MySQL 系统库 {@code information_schema}，<b>不对任何业务表做写入</b>，
 * 也不维护自己的实体表。整个模块是"借系统视图实现只读浏览"，不引入新的持久化结构。</p>
 *
 * <p><b>SQL 拼接的安全边界</b>：{@code schema}、{@code table}、列名这三个值会出现在 SQL 标识符位置
 * （MySQL 不支持对库/表/列名做预编译），所以一律用 {@code ${}} 字符串拼接。
 * 拼接前由 {@link com.example.erupt.service.impl.DatabaseServiceImpl} 完成白名单字符校验，
 * 在本接口内部不重复校验。</p>
 */
@Mapper
public interface DatabaseMapper {

    /**
     * 列出所有数据库（含系统库），按名称升序。
     *
     * <p>用 {@code LEFT JOIN} 同时拿到库级字符集与表数量/大小聚合，避免两次查询。</p>
     */
    List<Map<String, Object>> selectAllSchemas();

    /**
     * 某库下的表列表，支持按表名/注释模糊搜索 + 分页。
     */
    List<Map<String, Object>> selectTables(@Param("schema") String schema,
                                           @Param("keyword") String keyword,
                                           @Param("offset") long offset,
                                           @Param("limit") int limit);

    /** 某库下满足关键字条件的表总数。 */
    long countTables(@Param("schema") String schema, @Param("keyword") String keyword);

    /**
     * 某张表的列元信息，按 {@code ORDINAL_POSITION} 升序。
     */
    List<Map<String, Object>> selectColumns(@Param("schema") String schema,
                                            @Param("table") String table);

    /**
     * 某张表的精确行数（用于分页 total）。对 InnoDB 大表较慢，按需调用。
     */
    long countTableData(@Param("schema") String schema, @Param("table") String table);

    /**
     * 任意表的数据分页查询。{@code columnListSql} 由 service 层拼接好（形如 {@code `id`, `name`}），
     * 这里只作为 SQL 片段插入。
     */
    List<Map<String, Object>> selectTableData(@Param("schema") String schema,
                                              @Param("table") String table,
                                              @Param("columnListSql") String columnListSql,
                                              @Param("orderBySql") String orderBySql,
                                              @Param("offset") long offset,
                                              @Param("limit") int limit);
}
