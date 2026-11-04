package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.model.vo.DatabaseVO;
import com.example.erupt.model.vo.TableDataVO;
import com.example.erupt.model.vo.TableVO;

import java.util.List;

/**
 * 数据库只读浏览服务。
 *
 * <p>整个模块不维护任何业务表，仅借 MySQL 的 {@code information_schema} 系统视图实现只读浏览。
 * 因此本接口没有"新增/修改/删除"——不写库、不改表、不调 DDL。</p>
 */
public interface DatabaseService {

    /**
     * 列出所有数据库（含系统库）。系统库在前端用 {@code systemSchema=true} 标识。
     */
    List<DatabaseVO> listDatabases();

    /**
     * 某库下的数据表列表，支持按表名/注释模糊搜索。
     *
     * @param schema   库名（白名单校验）
     * @param keyword  模糊关键字，可为空
     * @param page     页码（1 起）
     * @param pageSize 每页条数
     */
    PageResult<TableVO> listTables(String schema, String keyword, int page, int pageSize);

    /**
     * 某张表的列元信息（仅供"查看表结构"场景使用，主流程是直接看数据）。
     */
    List<TableDataVO.ColumnMeta> listColumns(String schema, String table);

    /**
     * 查询任意表的数据。
     *
     * <p><b>系统库拒绝</b>：mysql / information_schema / performance_schema / sys 这些库不允许
     * 通过本接口读数据，避免泄露敏感信息（mysql.user 等）。</p>
     */
    TableDataVO queryTableData(String schema, String table, int page, int pageSize);
}
