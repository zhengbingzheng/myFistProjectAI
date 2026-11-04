package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.vo.DatabaseVO;
import com.example.erupt.model.vo.TableDataVO;
import com.example.erupt.model.vo.TableVO;
import com.example.erupt.repository.mapper.DatabaseMapper;
import com.example.erupt.service.DatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据库只读浏览服务实现。
 *
 * <p><b>SQL 注入防护的唯一关口</b>：库名、表名、列名三个值会进入 SQL 标识符位置
 * （MySQL 不支持预编译），所以本类的所有入口参数都必须先经过 {@link #requireValidName} 校验，
 * 才能进入 mapper 的 {@code ${}} 拼接。SQL 字符串里出现的每一个标识符都要可追溯到一次校验。</p>
 *
 * <p><b>系统库保护</b>：mysql / information_schema / performance_schema / sys 不允许走"读表数据"接口，
 * 这是为了避免 mysql.user、information_schema.processlist 这类敏感数据被任意拖出。
 * "列数据库"和"列表"接口不限制（连上 MySQL 的用户用 Navicat 也能看，开放无附加风险）。</p>
 */
@Slf4j
@Service
public class DatabaseServiceImpl implements DatabaseService {

    /** 库/表/列名的唯一合法形式：字母数字下划线连字符，1-64 字符。
     *  允许连字符是为了兼容 MySQL 默认库名（{@code erupt-example} 等）。
     *  这三类值进入 SQL 时全部用反引号包裹，注入风险由反引号封死，不依赖本正则的严苛度。 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 系统库：禁止走"查表数据"接口，避免敏感数据泄露。 */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    /** 敏感业务表：含密码哈希、私聊内容等，禁止通过"查表数据"接口批量导出。 */
    private static final Set<String> SENSITIVE_TABLES = Set.of(
            "t_user", "t_chat_message", "t_chat_conversation", "t_chat_member", "t_chat_attachment");

    /** 分页参数默认值与上限。 */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_OFFSET = 100_000L;

    /** 深分页保护：LIMIT 的大 OFFSET 会扫描并丢弃大量行，直接拒绝超限请求。 */
    private void requireOffsetWithinLimit(long offset) {
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量过大，请缩小页码");
        }
    }

    private final DatabaseMapper databaseMapper;

    public DatabaseServiceImpl(DatabaseMapper databaseMapper) {
        this.databaseMapper = databaseMapper;
    }

    @Override
    public List<DatabaseVO> listDatabases() {
        List<Map<String, Object>> rows = databaseMapper.selectAllSchemas();
        List<DatabaseVO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String schemaName = stringValue(row, "schemaName");
            result.add(new DatabaseVO(
                    schemaName,
                    stringValue(row, "charset"),
                    stringValue(row, "collation"),
                    intValue(row.get("tableCount")),
                    longValue(row.get("totalSizeBytes")),
                    isSystemSchema(schemaName)));
        }
        return result;
    }

    @Override
    public PageResult<TableVO> listTables(String schema, String keyword, int page, int pageSize) {
        requireValidName(schema);
        PageParams params = normalizePage(page, pageSize);
        // 偏移量校验放在 COUNT 之前：注定 400 的请求没理由先让库跑一次统计
        long offset = (long) (params.page - 1) * params.pageSize;
        requireOffsetWithinLimit(offset);
        String safeKeyword = LikeEscape.escape(keyword == null || keyword.isBlank() ? null : keyword.trim());
        long total = databaseMapper.countTables(schema, safeKeyword);
        if (total == 0) {
            return PageResult.of(List.of(), 0L, params.page, params.pageSize);
        }
        List<Map<String, Object>> rows = databaseMapper.selectTables(schema, safeKeyword, offset, params.pageSize);
        List<TableVO> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(new TableVO(
                    schema,
                    stringValue(row, "TABLE_NAME"),
                    stringValue(row, "TABLE_COMMENT"),
                    longValue(row.get("TABLE_ROWS")),
                    longValue(row.get("DATA_LENGTH")),
                    longValue(row.get("INDEX_LENGTH")),
                    stringValue(row, "ENGINE"),
                    stringValue(row, "TABLE_COLLATION"),
                    timeValue(row.get("CREATE_TIME"))));
        }
        return PageResult.of(items, total, params.page, params.pageSize);
    }

    @Override
    public List<TableDataVO.ColumnMeta> listColumns(String schema, String table) {
        requireValidName(schema);
        requireValidName(table);
        List<Map<String, Object>> rows = databaseMapper.selectColumns(schema, table);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "表不存在：" + schema + "." + table);
        }
        List<TableDataVO.ColumnMeta> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new TableDataVO.ColumnMeta(
                    stringValue(row, "COLUMN_NAME"),
                    stringValue(row, "DATA_TYPE"),
                    stringValue(row, "COLUMN_TYPE"),
                    stringValue(row, "COLUMN_COMMENT"),
                    stringValue(row, "IS_NULLABLE"),
                    stringValue(row, "COLUMN_KEY"),
                    row.get("COLUMN_DEFAULT"),
                    stringValue(row, "EXTRA"),
                    intValue(row.get("ORDINAL_POSITION"))));
        }
        return result;
    }

    @Override
    public TableDataVO queryTableData(String schema, String table, int page, int pageSize) {
        // 系统库从这一道闸门开始就被挡掉：查的是任意表的数据，敏感范围太大
        requireNotSystemSchema(schema);
        requireValidName(table);
        requireNotSensitiveTable(table);
        PageParams params = normalizePage(page, pageSize);

        // 1) 取列名（同时校验列名都在白名单内）。
        //    先做列名校验，再拼 SELECT——避免 SQL 拼出非法字符。
        List<TableDataVO.ColumnMeta> columns = listColumns(schema, table);
        String columnListSql = buildColumnListSql(columns);

        // 2) 偏移量校验放在 COUNT 之前：任意用户表的精确 COUNT(*) 对 InnoDB 大表是全表扫描，
        //    page=999999 这类注定 400 的请求没理由先让库白跑一趟
        long offset = (long) (params.page - 1) * params.pageSize;
        requireOffsetWithinLimit(offset);
        // 3) 行数与数据并行查：total 用于分页，data 用于当前页。
        long total = databaseMapper.countTableData(schema, table);
        List<Map<String, Object>> rawRows = total == 0
                ? List.of()
                : databaseMapper.selectTableData(schema, table, columnListSql, offset, params.pageSize);
        // 每行转 LinkedHashMap 保列顺序；转一次避免 MyBatis 的 UnmodifiableMap 直接外泄给前端序列化层
        List<Map<String, Object>> rows = new ArrayList<>(rawRows.size());
        for (Map<String, Object> raw : rawRows) {
            rows.add(new LinkedHashMap<>(raw));
        }
        log.info("浏览表数据 schema={} table={} total={} page={}/{} 返回 {} 行",
                schema, table, total, params.page, params.pageSize, rows.size());
        return new TableDataVO(columns, rows, total, params.page, params.pageSize);
    }

    /* ---------- 工具方法 ---------- */

    /**
     * 拼接 SELECT 列：每个列名都已经过列元数据查询（来自 information_schema.COLUMNS），
     * 必然是合法标识符；再以反引号包裹以兼容保留字 / 包含数字开头等边界场景。
     */
    private static String buildColumnListSql(List<TableDataVO.ColumnMeta> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('`').append(columns.get(i).name()).append('`');
        }
        return sb.toString();
    }

    /**
     * 名称白名单校验：失败即抛 400。<b>这是 SQL 注入的唯一关口</b>，
     * mapper 里凡是用 {@code ${}} 拼接的标识符都必须可追溯到这里。
     */
    private static void requireValidName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "名称不合法：" + name);
        }
    }

    /**
     * 系统库保护：仅对"读表数据"接口生效。
     */
    private static void requireNotSystemSchema(String schema) {
        requireValidName(schema);
        if (SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "系统库禁止查询表数据：" + schema);
        }
    }

    /**
     * 敏感表保护：t_user（密码哈希）、t_chat_*（私聊内容）等不通过通用表浏览接口导出。
     * 这些数据有各自的正规管理接口，通用接口开放它们等于绕过字段级脱敏。
     */
    private static void requireNotSensitiveTable(String table) {
        if (SENSITIVE_TABLES.contains(table.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "敏感表禁止查询数据：" + table);
        }
    }

    private static boolean isSystemSchema(String schemaName) {
        return schemaName != null && SYSTEM_SCHEMAS.contains(schemaName.toLowerCase(Locale.ROOT));
    }

    /** 修正 page/pageSize：page 至少 1，pageSize 在 [1, MAX_PAGE_SIZE] 内。 */
    private static PageParams normalizePage(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return new PageParams(safePage, safeSize);
    }

    private static String stringValue(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer intValue(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.valueOf(v.toString());
    }

    private static Long longValue(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof BigInteger bi) return bi.longValue();
        return Long.valueOf(v.toString());
    }

    private static LocalDateTime timeValue(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof LocalDateTime ldt) return ldt;
        // 部分驱动返回 String（MySQL 0 时区等场景）
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }

    private record PageParams(int page, int pageSize) {
    }
}
