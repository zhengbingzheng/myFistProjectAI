package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.JsonSupport;
import com.example.erupt.common.ReadSqlGuard;
import com.example.erupt.common.ResultCode;
import com.example.erupt.common.UpstreamEndpointPolicy;
import com.example.erupt.model.vo.AskDataVO;
import com.example.erupt.model.vo.DictItemVO;
import com.example.erupt.repository.entity.AgentDataAudit;
import com.example.erupt.repository.mapper.AgentDataAuditMapper;
import com.example.erupt.service.AgentDataService;
import com.example.erupt.service.DictService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * AI 读库实现：schema 概览喂给 GLM → 生成 SQL → 三道防线校验 → 只读连接执行。
 *
 * <p>schema 概览缓存 5 分钟：表结构在运行期几乎不变，每次提问都全量扫
 * information_schema 纯属浪费；改表后最多 5 分钟自动生效，够用。</p>
 */
@Service
public class AgentDataServiceImpl implements AgentDataService {

    private static final Logger log = LoggerFactory.getLogger(AgentDataServiceImpl.class);

    private static final String DICT_TYPE = "agent_config";
    private static final String DEFAULT_CHAT_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER;

    /** schema 缓存时长：改表后最多 5 分钟生效 */
    private static final long SCHEMA_CACHE_MS = 5 * 60_000L;

    private final DictService dictService;
    private final DataSource dataSource;
    private final RestClient restClient;
    private final AgentDataAuditMapper agentDataAuditMapper;
    private final UpstreamEndpointPolicy endpointPolicy;

    /** schema 概览缓存。过期后用锁单飞，避免并发提问同时扫 information_schema。 */
    private final Object schemaLock = new Object();
    private volatile SchemaSnapshot schemaSnapshot;

    public AgentDataServiceImpl(DictService dictService, DataSource dataSource,
                                AgentDataAuditMapper agentDataAuditMapper,
                                UpstreamEndpointPolicy endpointPolicy) {
        this.dictService = dictService;
        this.dataSource = dataSource;
        this.agentDataAuditMapper = agentDataAuditMapper;
        this.endpointPolicy = endpointPolicy;
        // 连接 5s / 读 120s：SQL 生成是普通补全，不需要流式那么长的等待
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public AskDataVO ask(String userId, String question, boolean superAdmin) {
        String trimmed = question == null ? "" : question.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }
        if (trimmed.length() > 500) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题太长了（最多 500 字），请拆开问");
        }

        Map<String, String> cfg = agentConfig();
        String apiKey = cfg.getOrDefault("API Key", "").trim();
        if (apiKey.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "尚未配置 API Key：请到「系统管理 › 数据字典」编辑 agent_config 下的「API Key」");
        }
        String chatUrl = cfg.getOrDefault("chat_url", DEFAULT_CHAT_URL).trim();
        String model = cfg.getOrDefault("default_model", "glm-5.3").trim();

        long genStart = System.currentTimeMillis();
        String sql = null;
        long genMs = 0;
        try {
            sql = validateAndNormalize(generateSql(apiKey, chatUrl, model, trimmed, superAdmin), superAdmin);
            genMs = System.currentTimeMillis() - genStart;

            long queryStart = System.currentTimeMillis();
            QueryResult result = executeReadOnly(sql);
            long queryMs = System.currentTimeMillis() - queryStart;

            log.info("AI 读库：user={} question={} sql={} rows={} cost={}ms",
                    userId, trimmed, sql, result.rows().size(), genMs + queryMs);
            audit(userId, trimmed, sql, result.rows().size(), genMs, queryMs, true, null);
            return new AskDataVO(trimmed, result.sql(), model, result.columns(),
                    result.rows(), result.rows().size(), genMs, queryMs, result.note());
        } catch (BusinessException e) {
            // 失败也留审计：谁在什么时候问了什么、被哪道防线拦下——
            // 没有失败记录，「被拦截」和「没问过」就无法区分
            audit(userId, trimmed, sql, null, genMs, null, false, e.getMessage());
            throw e;
        }
    }

    /**
     * 落审计快照。审计失败只记 warn 不影响主流程——审计的价值在长期积累，
     * 偶尔丢一条不应导致用户的查询跟着失败。
     */
    private void audit(String userId, String question, String sql, Integer rowCount,
                       Long genMs, Long queryMs, boolean success, String errorMsg) {
        try {
            String trimmedError = errorMsg == null ? null
                    : errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg;
            agentDataAuditMapper.insert(new AgentDataAudit(
                    UUID.randomUUID().toString().replace("-", ""), userId, question,
                    sql, rowCount, genMs, queryMs, success ? 1 : 0, trimmedError, null));
        } catch (Exception e) {
            log.warn("AI 读库审计落库失败（不影响主流程）: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> auditPage(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long offset = (long) (safePage - 1) * safeSize;
        List<AgentDataAudit> items = agentDataAuditMapper.selectPage(null,
                safeSize, offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset);
        long total = agentDataAuditMapper.countPage(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    /* ---------- SQL 生成 ---------- */

    private String generateSql(String apiKey, String chatUrl, String model, String question,
                               boolean superAdmin) {
        String prompt = """
                你是 MySQL 查询生成器。根据下面的表结构，把用户的问题翻译成一条 MySQL SELECT 查询。

                规则：
                1. 只输出 SQL 本身，不要任何解释、不要 markdown 代码块标记；
                2. 只允许 SELECT；计数用 COUNT(*)，模糊匹配用 LIKE '%%关键词%%'；
                3. 只使用表结构里存在的表和列；「有效数据」通常用 del_flag='0' 过滤（如果该表有此列）；
                4. 时间条件用 DATE() / NOW() 处理；默认排序按 create_time DESC 取最近。
                5. 只能查询以t_开头的数据库表；

                表结构：
                %s

                用户问题：%s""".formatted(schemaOverview(superAdmin), question);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content",
                        "你是严谨的 MySQL 专家，只输出一条 SELECT 语句本身，不输出任何其他文字。"),
                Map.of("role", "user", "content", prompt)));
        body.put("stream", false);
        // temperature 0：SQL 生成要确定性，不要创造性
        body.put("temperature", 0);

        String raw;
        try {
            raw = restClient.post()
                    .uri(endpointPolicy.require(chatUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("AI 读库调用上游失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "调用智能体平台失败：" + rootMessage(e) + "，请稍后重试");
        }
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体平台返回了空响应，请稍后重试");
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root.path("error").isObject()) {
                String message = root.path("error").path("message").asText("上游返回错误");
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "智能体平台错误：" + message);
            }
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "AI 没能生成查询语句，请换个问法");
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 读库解析上游响应失败: {}", raw);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "AI 响应无法解析，请重试");
        }
    }

    /**
     * 形态、表白名单和行数上限都在 {@link ReadSqlGuard}。
     * 连接只读只兜写操作，挡不住把密码哈希 SELECT 出来，所以不能只靠它。
     *
     * <p>正则校验之后还要用 {@code EXPLAIN} 复核一次实际访问的表：CTE 同名遮蔽、
     * 括号表引用这类写法能让文本提取器漏看真实表，而 MySQL 自己的解析结果不会。</p>
     */
    private String validateAndNormalize(String raw, boolean superAdmin) {
        Set<String> knownTables = snapshot().tables();
        String sql = ReadSqlGuard.normalize(raw, knownTables, superAdmin);
        ReadSqlGuard.verifyAccessedTables(explainAccessedTables(sql), knownTables, superAdmin);
        return sql;
    }

    /**
     * 用 {@code EXPLAIN FORMAT=JSON} 取"这条 SQL 实际会访问的表"。
     *
     * <p>EXPLAIN 只做解析与优化，不返回业务数据；查询非法或引用了当前账号无权访问的对象时
     * 会直接报错，这里按拒绝处理（fail-closed）。输出里的 {@code <derived2>} 等伪表名
     * 由 {@link ReadSqlGuard#verifyAccessedTables} 忽略。</p>
     */
    private Set<String> explainAccessedTables(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            conn.setReadOnly(true);
            try (ResultSet rs = stmt.executeQuery("EXPLAIN FORMAT=JSON " + sql)) {
                if (!rs.next()) {
                    return Set.of();
                }
                String json = rs.getString(1);
                if (json == null || json.isBlank()) {
                    return Set.of();
                }
                Set<String> tables = new HashSet<>();
                collectExplainedTables(MAPPER.readTree(json), tables);
                return tables;
            }
        } catch (SQLException | java.io.IOException e) {
            log.warn("AI 读库 EXPLAIN 复核失败（按拒绝处理）: {} sql={}", e.getMessage(), sql);
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询无法通过安全校验，请换个问法");
        }
    }

    /** 递归收集 EXPLAIN JSON 里的 table_name（结构随执行计划嵌套/数组，不能只取一层）。 */
    private static void collectExplainedTables(JsonNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode tableName = node.get("table_name");
            if (tableName != null && tableName.isTextual()) {
                tables.add(tableName.asText());
            }
            node.fields().forEachRemaining(entry -> collectExplainedTables(entry.getValue(), tables));
        } else if (node.isArray()) {
            node.forEach(child -> collectExplainedTables(child, tables));
        }
    }

    /* ---------- 只读执行 ---------- */

    private record QueryResult(String sql, List<String> columns, List<List<String>> rows, String note) {
    }

    /**
     * 第三道防线：连接设为只读后执行。
     * 即使前两道校验被绕过（例如未来出现某种新注入形态），MySQL 也会拒绝写操作。
     */
    private QueryResult executeReadOnly(String sql) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(ReadSqlGuard.MAX_ROWS);
                stmt.setQueryTimeout(15);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<String> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        // 用列标签而不是列名：别名（COUNT(*) AS cnt）才有可读性
                        columns.add(meta.getColumnLabel(i));
                    }
                    List<List<String>> rows = new ArrayList<>();
                    while (rs.next()) {
                        List<String> row = new ArrayList<>(columnCount);
                        for (int i = 1; i <= columnCount; i++) {
                            row.add(cellToString(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                    String note = rows.size() >= ReadSqlGuard.MAX_ROWS
                            ? "结果已达上限 " + ReadSqlGuard.MAX_ROWS + " 行（系统自动限制），请缩小查询范围" : null;
                    return new QueryResult(sql, columns, rows, note);
                }
            }
        } catch (SQLException e) {
            log.warn("AI 读库执行失败: {} sql={}", e.getMessage(), sql);
            // 数据库原文会带出表结构，甚至把语句回显给调用方。原因只留在日志里。
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询执行失败，请换个问法");
        }
    }

    /** 单元格值转字符串：null 显示为空，时间统一格式，其余 toString */
    private static String cellToString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        if (value instanceof java.time.LocalDate d) {
            return d.toString();
        }
        return String.valueOf(value);
    }

    /* ---------- schema 概览与工具 ---------- */

    private record SchemaSnapshot(Set<String> tables, String adminText, String sharedText, long at) {
    }

    /**
     * 按角色给出表结构提示。凭证表两边都不喂；按人隔离的表只喂给超管。
     * 缓存的是列清单，提示文本按角色现拼，避免把别人的甘特表结构发给普通用户。
     */
    private String schemaOverview(boolean superAdmin) {
        SchemaSnapshot current = snapshot();
        return superAdmin ? current.adminText() : current.sharedText();
    }

    private SchemaSnapshot snapshot() {
        SchemaSnapshot current = schemaSnapshot;
        if (fresh(current)) {
            return current;
        }
        synchronized (schemaLock) {
            current = schemaSnapshot;
            if (fresh(current)) {
                return current;
            }
            schemaSnapshot = loadSnapshot();
            return schemaSnapshot;
        }
    }

    private static boolean fresh(SchemaSnapshot current) {
        return current != null && System.currentTimeMillis() - current.at < SCHEMA_CACHE_MS;
    }

    private SchemaSnapshot loadSnapshot() {
        String sql = """
                SELECT table_name, column_name, column_type, column_comment
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                      SELECT table_name FROM information_schema.tables
                      WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE')
                ORDER BY table_name, ordinal_position""";
        StringBuilder admin = new StringBuilder();
        StringBuilder shared = new StringBuilder();
        Set<String> tables = new HashSet<>();
        String currentTable = null;
        boolean adminOpen = false;
        boolean sharedOpen = false;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String table = rs.getString(1);
                tables.add(table.toLowerCase(Locale.ROOT));
                boolean hideFromAdmin = ReadSqlGuard.hiddenFromPrompt(table, true);
                boolean hideFromShared = ReadSqlGuard.hiddenFromPrompt(table, false);
                if (!table.equals(currentTable)) {
                    if (adminOpen) {
                        admin.append(")\n");
                        adminOpen = false;
                    }
                    if (sharedOpen) {
                        shared.append(")\n");
                        sharedOpen = false;
                    }
                    currentTable = table;
                }
                if (!hideFromAdmin) {
                    appendColumn(admin, table, rs, adminOpen);
                    adminOpen = true;
                }
                if (!hideFromShared) {
                    appendColumn(shared, table, rs, sharedOpen);
                    sharedOpen = true;
                }
            }
            if (adminOpen) {
                admin.append(")\n");
            }
            if (sharedOpen) {
                shared.append(")\n");
            }
        } catch (SQLException e) {
            log.warn("读取表结构失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "读取表结构失败");
        }
        return new SchemaSnapshot(tables, admin.toString(), shared.toString(), System.currentTimeMillis());
    }

    private static void appendColumn(StringBuilder sb, String table, ResultSet rs, boolean continued)
            throws SQLException {
        if (!continued) {
            sb.append(table).append('(');
        } else {
            sb.append(", ");
        }
        sb.append(rs.getString(2)).append(' ').append(rs.getString(3));
        String comment = rs.getString(4);
        if (comment != null && !comment.isBlank()) {
            sb.append("/*").append(comment.replace("*/", "")).append("*/");
        }
    }

    private Map<String, String> agentConfig() {
        Map<String, String> cfg = new HashMap<>();
        for (DictItemVO item : dictService.listEnabledItems(DICT_TYPE)) {
            cfg.put(item.label(), item.value());
        }
        return cfg;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
