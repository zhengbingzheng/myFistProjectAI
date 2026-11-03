package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.vo.DatabaseVO;
import com.example.erupt.model.vo.TableDataVO;
import com.example.erupt.model.vo.TableVO;
import com.example.erupt.repository.mapper.DatabaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据库只读浏览服务的单测。
 *
 * <p>这里只验证「校验/转换/分支」这些可被独立测试的逻辑。真实 SQL 行为（information_schema 跨库查询）
 * 需要起一个测试数据库才能覆盖，本项目没有 H2/SQLite 测试库，跳过。</p>
 */
@ExtendWith(MockitoExtension.class)
class DatabaseServiceImplTest {

    @Mock
    private DatabaseMapper databaseMapper;

    private DatabaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DatabaseServiceImpl(databaseMapper);
    }

    /* ==================== listDatabases ==================== */

    @Test
    @DisplayName("listDatabases_系统库被标记")
    void listDatabases_systemSchemasMarked() {
        // mock 的顺序就是 service 输出的顺序；这里按 SQL 的 ORDER BY s.SCHEMA_NAME 排好，
        // 验证 service 不会打乱顺序，也不会把"系统库"误标。
        // totalSizeBytes 全部给非 null 值：真实 SQL 用 COALESCE(..., 0)，永远不会是 null。
        when(databaseMapper.selectAllSchemas()).thenReturn(List.of(
                schemaRow("erupt-example", "utf8mb4", "utf8mb4_0900_ai_ci", 50, BigInteger.valueOf(2_000_000L)),
                schemaRow("information_schema", "utf8", "utf8_general_ci", 0, BigInteger.ZERO),
                schemaRow("mysql", "utf8mb4", "utf8mb4_0900_ai_ci", 31, BigInteger.valueOf(1_500_000L))));

        List<DatabaseVO> result = service.listDatabases();

        assertThat(result).hasSize(3);
        assertThat(result).extracting(DatabaseVO::schemaName).containsExactly(
                "erupt-example", "information_schema", "mysql");
        // 显式 stream filter：filteredOn 在 record 的 boolean 组件上行为不稳，stream 更直观
        List<String> systemNames = result.stream()
                .filter(DatabaseVO::systemSchema)
                .map(DatabaseVO::schemaName)
                .toList();
        assertThat(systemNames).containsExactlyInAnyOrder("information_schema", "mysql");
        // 验证 COALESCE 行为：无表时 totalSizeBytes = 0 而不是 null
        Long size = result.stream().filter(v -> v.schemaName().equals("information_schema"))
                .findFirst().orElseThrow().totalSizeBytes();
        assertThat(size).isEqualTo(0L);
    }

    @Test
    @DisplayName("listDatabases_空库列表返回空集合")
    void listDatabases_empty() {
        when(databaseMapper.selectAllSchemas()).thenReturn(List.of());
        assertThat(service.listDatabases()).isEmpty();
    }

    /* ==================== listTables ==================== */

    @Test
    @DisplayName("listTables_schema名非法_抛BAD_REQUEST")
    void listTables_invalidSchemaName_rejected() {
        BusinessException ex = catchThrowableOfType(
                () -> service.listTables("db;DROP--", null, 1, 10), BusinessException.class);
        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
        verify(databaseMapper, never()).selectTables(anyString(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("listTables_total为0时直接返回空页不发列表SQL")
    void listTables_zeroTotal_shortCircuit() {
        when(databaseMapper.countTables(eq("erupt-example"), any())).thenReturn(0L);

        PageResult<TableVO> page = service.listTables("erupt-example", null, 1, 10);

        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
        // 短路：count=0 时不发 selectTables
        verify(databaseMapper, never()).selectTables(anyString(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("listTables_正常返回_行转VO")
    void listTables_happyPath() {
        when(databaseMapper.countTables(eq("erupt-example"), any())).thenReturn(2L);
        when(databaseMapper.selectTables(eq("erupt-example"), any(), eq(0L), eq(10)))
                .thenReturn(List.of(
                        tableRow("t_user", "用户表", BigInteger.valueOf(100), BigInteger.valueOf(16384), BigInteger.valueOf(8192), "InnoDB", "utf8mb4", LocalDateTime.of(2026, 10, 18, 21, 12, 17)),
                        tableRow("t_role", "角色", null, BigInteger.valueOf(8192), BigInteger.ZERO, "InnoDB", "utf8mb4", LocalDateTime.of(2026, 8, 14, 23, 48, 42))));

        PageResult<TableVO> page = service.listTables("erupt-example", null, 1, 10);

        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.items()).hasSize(2);
        TableVO first = page.items().get(0);
        assertThat(first.schemaName()).isEqualTo("erupt-example");
        assertThat(first.tableName()).isEqualTo("t_user");
        assertThat(first.tableRows()).isEqualTo(100L);
        assertThat(first.dataLength()).isEqualTo(16384L);
        assertThat(first.indexLength()).isEqualTo(8192L);
        assertThat(first.engine()).isEqualTo("InnoDB");
        // null 的行数应该被容忍（不影响后续）
        assertThat(page.items().get(1).tableRows()).isNull();
    }

    @Test
    @DisplayName("listTables_pageSize被上限裁剪_避免大页拖库")
    void listTables_pageSizeClamped() {
        when(databaseMapper.countTables(eq("erupt-example"), any())).thenReturn(1L);
        when(databaseMapper.selectTables(eq("erupt-example"), any(), eq(0L), eq(100)))
                .thenReturn(List.of(tableRow("t_a", null, null, null, null, "InnoDB", "utf8mb4", null)));

        // 9999 应被裁剪到 100
        PageResult<TableVO> page = service.listTables("erupt-example", null, 1, 9999);
        assertThat(page.pageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("listTables_偏移量超限_抛BAD_REQUEST")
    void listTables_overflowOffset() {
        when(databaseMapper.countTables(eq("erupt-example"), any())).thenReturn(1L);
        // page=200000, pageSize=1 → offset=199999 > MAX_OFFSET(100000)
        BusinessException ex = catchThrowableOfType(
                () -> service.listTables("erupt-example", null, 200_000, 1), BusinessException.class);
        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
    }

    /* ==================== listColumns ==================== */

    @Test
    @DisplayName("listColumns_表名不合法_抛BAD_REQUEST")
    void listColumns_invalidTableName_rejected() {
        BusinessException ex = catchThrowableOfType(
                () -> service.listColumns("erupt-example", "t_user;--"), BusinessException.class);
        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
        verify(databaseMapper, never()).selectColumns(anyString(), anyString());
    }

    @Test
    @DisplayName("listColumns_表不存在_抛NOT_FOUND")
    void listColumns_tableNotFound() {
        when(databaseMapper.selectColumns("erupt-example", "t_ghost")).thenReturn(List.of());

        BusinessException ex = catchThrowableOfType(
                () -> service.listColumns("erupt-example", "t_ghost"), BusinessException.class);
        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("listColumns_正常返回_列元数据")
    void listColumns_happyPath() {
        when(databaseMapper.selectColumns("erupt-example", "t_user")).thenReturn(List.of(
                columnRow("id", "BIGINT", "bigint(20)", "NO", "PRI", null, "auto_increment", "主键", 1),
                columnRow("username", "VARCHAR", "varchar(32)", "NO", "UNI", null, "", "账号", 2)));

        List<TableDataVO.ColumnMeta> cols = service.listColumns("erupt-example", "t_user");

        assertThat(cols).hasSize(2);
        assertThat(cols.get(0).name()).isEqualTo("id");
        assertThat(cols.get(0).columnKey()).isEqualTo("PRI");
        assertThat(cols.get(1).name()).isEqualTo("username");
        assertThat(cols.get(1).columnType()).isEqualTo("varchar(32)");
    }

    /* ==================== queryTableData ==================== */

    @Test
    @DisplayName("queryTableData_系统库直接被拒绝_不发mapper调用")
    void queryTableData_systemSchemaRejected() {
        BusinessException ex = catchThrowableOfType(
                () -> service.queryTableData("mysql", "user", 1, 10), BusinessException.class);
        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
        assertThat(ex.getMessage()).contains("mysql");
        verify(databaseMapper, never()).selectColumns(anyString(), anyString());
        verify(databaseMapper, never()).selectTableData(anyString(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("queryTableData_information_schema也被拒绝")
    void queryTableData_informationSchemaRejected() {
        for (String sys : List.of("information_schema", "performance_schema", "sys")) {
            BusinessException ex = catchThrowableOfType(
                    () -> service.queryTableData(sys, "any", 1, 10), BusinessException.class);
            assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
            assertThat(ex.getMessage()).contains(sys);
        }
    }

    @Test
    @DisplayName("queryTableData_表名不合法_抛BAD_REQUEST")
    void queryTableData_invalidTableName_rejected() {
        BusinessException ex = catchThrowableOfType(
                () -> service.queryTableData("erupt-example", "t_user`;--", 1, 10), BusinessException.class);
        assertThat(ex.resultCode()).isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("queryTableData_表不存在_抛NOT_FOUND")
    void queryTableData_tableNotFound() {
        when(databaseMapper.selectColumns("erupt-example", "t_ghost")).thenReturn(List.of());

        BusinessException ex = catchThrowableOfType(
                () -> service.queryTableData("erupt-example", "t_ghost", 1, 10), BusinessException.class);
        assertThat(ex.resultCode()).isEqualTo(ResultCode.NOT_FOUND);
        // 没拿到列就不应去查数据
        verify(databaseMapper, never()).countTableData(anyString(), anyString());
    }

    @Test
    @DisplayName("queryTableData_total为0时只查列不发数据SQL")
    void queryTableData_zeroTotal_shortCircuit() {
        when(databaseMapper.selectColumns("erupt-example", "t_user")).thenReturn(List.of(
                columnRow("id", "BIGINT", "bigint", "NO", "PRI", null, "", "id", 1)));
        when(databaseMapper.countTableData("erupt-example", "t_user")).thenReturn(0L);

        TableDataVO result = service.queryTableData("erupt-example", "t_user", 1, 10);

        assertThat(result.total()).isZero();
        assertThat(result.rows()).isEmpty();
        assertThat(result.columns()).hasSize(1);
        // 短路：total=0 时不应发 selectTableData
        verify(databaseMapper, never()).selectTableData(anyString(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("queryTableData_正常返回_列顺序与值都对得上")
    void queryTableData_happyPath() {
        when(databaseMapper.selectColumns("erupt-example", "t_user")).thenReturn(List.of(
                columnRow("id", "BIGINT", "bigint", "NO", "PRI", null, "", "id", 1),
                columnRow("username", "VARCHAR", "varchar(32)", "NO", "", null, "", "账号", 2)));
        when(databaseMapper.countTableData("erupt-example", "t_user")).thenReturn(2L);
        when(databaseMapper.selectTableData(eq("erupt-example"), eq("t_user"), anyString(), eq(0L), eq(10)))
                .thenReturn(List.of(
                        Map.of("id", 1, "username", "admin"),
                        Map.of("id", 2, "username", "tester")));

        TableDataVO result = service.queryTableData("erupt-example", "t_user", 1, 10);

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.columns()).hasSize(2);
        assertThat(result.columns().get(0).name()).isEqualTo("id");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsEntry("username", "admin");
        // 关键：拼到 SQL 的列列表是带反引号的合法字符串
        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(databaseMapper).selectTableData(eq("erupt-example"), eq("t_user"),
                sqlCaptor.capture(), eq(0L), eq(10));
        assertThat(sqlCaptor.getValue()).isEqualTo("`id`, `username`");
    }

    /* ==================== 工具 ==================== */

    private static Map<String, Object> schemaRow(String name, String charset, String collation,
                                                 int tableCount, BigInteger totalSize) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schemaName", name);
        m.put("charset", charset);
        m.put("collation", collation);
        m.put("tableCount", tableCount);
        m.put("totalSizeBytes", totalSize);
        return m;
    }

    private static Map<String, Object> tableRow(String name, String comment, BigInteger rows,
                                                BigInteger data, BigInteger index,
                                                String engine, String collation, LocalDateTime createTime) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("TABLE_NAME", name);
        m.put("TABLE_COMMENT", comment);
        m.put("TABLE_ROWS", rows);
        m.put("DATA_LENGTH", data);
        m.put("INDEX_LENGTH", index);
        m.put("ENGINE", engine);
        m.put("TABLE_COLLATION", collation);
        m.put("CREATE_TIME", createTime == null ? null : Timestamp.valueOf(createTime));
        return m;
    }

    private static Map<String, Object> columnRow(String name, String dataType, String columnType,
                                                String isNullable, String key, Object defaultValue,
                                                String extra, String comment, int ordinal) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("COLUMN_NAME", name);
        m.put("DATA_TYPE", dataType);
        m.put("COLUMN_TYPE", columnType);
        m.put("IS_NULLABLE", isNullable);
        m.put("COLUMN_KEY", key);
        m.put("COLUMN_DEFAULT", defaultValue);
        m.put("EXTRA", extra);
        m.put("COLUMN_COMMENT", comment);
        m.put("ORDINAL_POSITION", ordinal);
        return m;
    }
}
