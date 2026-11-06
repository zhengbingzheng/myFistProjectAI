package com.example.erupt.common;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问数 SQL 的唯一放行关口。
 *
 * <p>模型输出不可信。这里先剥掉注释和字符串，再要求「单条 SELECT + 表在允许集合内 +
 * 没有写文件/延时函数」。执行的是清洗后的语句，不是模型原文，避免注释把第二条语句藏起来。
 * 凭证表对超管同样关闭：问数不能变成数据库浏览里敏感表拦截的旁路。</p>
 */
public final class ReadSqlGuard {

    public static final int MAX_ROWS = 100;

    /** 含密码、密钥、私聊、审计原文。任何角色都不许通过问数读取。 */
    public static final Set<String> CREDENTIAL_TABLES = Set.of(
            "t_user",
            "t_dict_item",
            "t_chat_message",
            "t_chat_conversation",
            "t_chat_member",
            "t_chat_attachment",
            "t_operation_log",
            "t_login_log",
            "t_agent_data_audit");

    /** 按创建人隔离的业务表。超管可以查，普通用户不行。 */
    public static final Set<String> PRIVATE_TABLES = Set.of(
            "t_gantt_plan",
            "t_gantt_task",
            "t_gantt_dependency",
            "t_notification",
            "t_agent_conversation",
            "t_agent_message",
            "t_user_role");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|REPLACE|RENAME|"
                    + "CALL|LOCK|UNLOCK|SET|INTO|OUTFILE|DUMPFILE|LOAD_FILE|HANDLER|"
                    + "SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK|UPDATEXML|EXTRACTVALUE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "(?i)\\b(information_schema|performance_schema|mysql|sys)\\s*\\.");

    private static final Pattern LIMIT_CLAUSE = Pattern.compile(
            "(?i)\\blimit\\s+\\d+(\\s*,\\s*\\d+|\\s+offset\\s+\\d+)?");

    private static final Pattern FROM_OR_JOIN = Pattern.compile("(?i)\\b(from|join)\\b");

    private ReadSqlGuard() {
    }

    /**
     * @param knownTables 当前库真实存在的表名（小写）。不在这个集合里的表一律拒绝。
     * @param superAdmin  为 true 时允许 {@link #PRIVATE_TABLES}，凭证表仍然拒绝
     */
    public static String normalize(String raw, Set<String> knownTables, boolean superAdmin) {
        String sql = stripComments(unwrapFence(raw == null ? "" : raw)).trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只允许一条查询语句");
        }
        if (!sql.regionMatches(true, 0, "SELECT", 0, 6)
                && !sql.regionMatches(true, 0, "WITH", 0, 4)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只允许 SELECT 查询");
        }
        String masked = maskStringLiterals(sql);
        Matcher forbidden = FORBIDDEN.matcher(masked);
        if (forbidden.find()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "查询包含禁用语法（" + forbidden.group(1).toUpperCase(Locale.ROOT) + "）");
        }
        if (SYSTEM_SCHEMA.matcher(masked).find()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能查询系统库");
        }
        Set<String> allowed = allowedTables(knownTables, superAdmin);
        Set<String> ctes = cteNames(masked);
        Set<String> referenced = extractTables(masked);
        if (referencesFrom(masked) && referenced.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法识别查询涉及的表");
        }
        for (String table : referenced) {
            if (ctes.contains(table)) {
                continue;
            }
            if (CREDENTIAL_TABLES.contains(table)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "该表包含凭证或私密数据，不能通过问数查询");
            }
            if (!superAdmin && PRIVATE_TABLES.contains(table)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "只能查询公共数据");
            }
            if (!allowed.contains(table)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "查询引用了未授权的表");
            }
        }
        String withoutLimit = LIMIT_CLAUSE.matcher(sql).replaceAll("").trim();
        return withoutLimit + " LIMIT " + MAX_ROWS;
    }

    public static boolean hiddenFromPrompt(String table, boolean superAdmin) {
        if (table == null) {
            return true;
        }
        String name = table.toLowerCase(Locale.ROOT);
        if (CREDENTIAL_TABLES.contains(name)) {
            return true;
        }
        return !superAdmin && PRIVATE_TABLES.contains(name);
    }

    private static Set<String> allowedTables(Set<String> knownTables, boolean superAdmin) {
        Set<String> allowed = new HashSet<>();
        if (knownTables == null) {
            return allowed;
        }
        for (String table : knownTables) {
            if (table == null) {
                continue;
            }
            String name = table.toLowerCase(Locale.ROOT);
            if (CREDENTIAL_TABLES.contains(name)) {
                continue;
            }
            if (!superAdmin && PRIVATE_TABLES.contains(name)) {
                continue;
            }
            allowed.add(name);
        }
        return allowed;
    }

    private static final Pattern CTE_NAME = Pattern.compile(
            "(?i)(?:\\bwith(?:\\s+recursive)?\\b|,)\\s+`?([A-Za-z0-9_]+)`?\\s+as\\s*\\(");

    /** WITH 子句引入的名字不是物理表，不能拿去对允许名单做失败关闭。 */
    private static Set<String> cteNames(String masked) {
        Set<String> names = new HashSet<>();
        Matcher matcher = CTE_NAME.matcher(masked);
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static boolean referencesFrom(String masked) {
        return FROM_OR_JOIN.matcher(masked).find();
    }

    private static Set<String> extractTables(String masked) {
        Set<String> tables = new HashSet<>();
        Matcher matcher = FROM_OR_JOIN.matcher(masked);
        while (matcher.find()) {
            String rest = masked.substring(matcher.end()).stripLeading();
            if (rest.startsWith("(")) {
                continue;
            }
            Matcher schema = SYSTEM_SCHEMA.matcher(rest);
            if (schema.lookingAt()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不能查询系统库");
            }
            Matcher name = Pattern.compile("^`?([A-Za-z0-9_]+)`?").matcher(rest);
            if (!name.find()) {
                continue;
            }
            tables.add(name.group(1).toLowerCase(Locale.ROOT));
            String segment = rest.split("(?i)\\b(where|group|order|limit|having|union|join|left|right|inner|outer|cross|on)\\b", 2)[0];
            Matcher more = Pattern.compile(",\\s*`?([A-Za-z0-9_]+)`?").matcher(segment);
            while (more.find()) {
                tables.add(more.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private static String unwrapFence(String raw) {
        String sql = raw.trim();
        if (!sql.startsWith("```")) {
            return sql;
        }
        int firstNewline = sql.indexOf('\n');
        int lastFence = sql.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return sql.substring(firstNewline + 1, lastFence).trim();
        }
        return sql.replace("`", "").trim();
    }

    /** MySQL 的 {@code --} 注释必须紧跟空白；这里同样只认这种写法，避免把减号运算误删。 */
    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = copyQuoted(sql, i, out, '\'');
                continue;
            }
            if (c == '"') {
                i = copyQuoted(sql, i, out, '"');
                continue;
            }
            if (c == '`') {
                i = copyQuoted(sql, i, out, '`');
                continue;
            }
            if (c == '#') {
                i = skipLine(sql, i);
                out.append(' ');
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-'
                    && (i + 2 >= sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) {
                i = skipLine(sql, i);
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String maskStringLiterals(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                out.append(c).append(c);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int copyQuoted(String sql, int start, StringBuilder out, char quote) {
        out.append(quote);
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            out.append(c);
            if (c == '\\' && i + 1 < sql.length()) {
                out.append(sql.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    out.append(quote);
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < sql.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipLine(String sql, int start) {
        int newline = sql.indexOf('\n', start);
        return newline < 0 ? sql.length() : newline + 1;
    }
}
