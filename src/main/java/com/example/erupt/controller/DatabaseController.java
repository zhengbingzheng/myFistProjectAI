package com.example.erupt.controller;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.vo.DatabaseVO;
import com.example.erupt.model.vo.TableDataVO;
import com.example.erupt.model.vo.TableVO;
import com.example.erupt.service.DatabaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据库只读浏览接口（视图层）。
 *
 * <p>全部为 GET，不写任何数据、不建任何表——纯粹借 {@code information_schema} 系统视图
 * 实现"看看本机有哪些库 / 哪些表 / 表里有什么数据"。</p>
 */
@RestController
@RequestMapping("/api/databases")
public class DatabaseController {

    private final DatabaseService databaseService;

    public DatabaseController(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /** 列出本机所有数据库（含系统库，系统库由 {@code systemSchema} 字段标识）。 */
    @GetMapping
    public Result<List<DatabaseVO>> list() {
        return Result.success(databaseService.listDatabases());
    }

    /**
     * 某库下的表列表，支持表名/注释模糊搜索。
     *
     * @param schema   库名（白名单校验）
     * @param keyword  模糊关键字，可选
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数，1-100
     */
    @GetMapping("/{schema}/tables")
    public Result<PageResult<TableVO>> listTables(@PathVariable String schema,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer pageSize) {
        int p = page == null ? 1 : page;
        int ps = pageSize == null ? 10 : pageSize;
        return Result.success(databaseService.listTables(schema, keyword, p, ps));
    }

    /** 某张表的列元信息（表结构）。 */
    @GetMapping("/{schema}/tables/{table}/columns")
    public Result<List<TableDataVO.ColumnMeta>> columns(@PathVariable String schema,
                                                       @PathVariable String table) {
        return Result.success(databaseService.listColumns(schema, table));
    }

    /**
     * 任意表的数据分页查询。<b>系统库（mysql/information_schema/performance_schema/sys）被拒绝</b>。
     *
     * @param schema   库名
     * @param table    表名
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数，1-100
     */
    @GetMapping("/{schema}/tables/{table}/data")
    public Result<TableDataVO> data(@PathVariable String schema,
                                    @PathVariable String table,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer pageSize) {
        int p = page == null ? 1 : page;
        int ps = pageSize == null ? 10 : pageSize;
        return Result.success(databaseService.queryTableData(schema, table, p, ps));
    }
}
