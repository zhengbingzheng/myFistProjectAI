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

    /**
     * 含密码、密钥、私聊、AI 私聊原文、审计原文。任何角色都不许通过问数读取。
     *
     * <p>与 {@code DatabaseServiceImpl.SENSITIVE_TABLES} 保持同一口径：数据库浏览拒绝
     * 批量导出的表，问数也不能成为旁路。此前 {@code t_agent_message} /
     * {@code t_agent_conversation} 只放在 PRIVATE_TABLES，超管可以用问数把全站 AI 私聊
     * 读出来，而同样的表在库表浏览里是被拒绝的。</p>
     */
    public static final Set<String> CREDENTIAL_TABLES = Set.of(
            "t_user",
            "t_dict_item",
            "t_chat_message",
            "t_chat_conversation",
            "t_chat_member",
            "t_chat_attachment",
            "t_agent_message",
            "t_agent_conversation",
            "t_operation_log",
            "t_login_log",
            "t_agent_data_audit");

    /** 按创建人隔离的业务表。超管可以查，普通用户不行。 */
    public static final Set<String> PRIVATE_TABLES = Set.of(
            "t_gantt_plan",
            "t_gantt_task",
            "t_gantt_dependency",
            "t_notification",
            "t_user_role");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|REPLACE|RENAME|"
                    + "CALL|LOCK|UNLOCK|SET|INTO|OUTFILE|DUMPFILE|LOAD_FILE|HANDLER|"
                    + "SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK|UPDATEXML|EXTRACTVALUE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "(?i)\\b(information_schema|performance_schema|mysql|sys)\\s*\\.");

    /**
     * 加锁读：{@code FOR UPDATE} 虽已被 FORBIDDEN 里的 UPDATE 命中，
     * 但 {@code FOR SHARE} / {@code LOCK IN SHARE MODE} 不在那份清单里。
     * 问数只需要读数据，不需要持有行锁；允许加锁读等于给了一条制造锁等待的入口。
     */
    private static final Pattern LOCKING_READ = Pattern.compile(
            "(?i)\\bfor\\s+(update|share)\\b|\\block\\s+in\\s+share\\s+mode\\b");

    /**
     * 只匹配<b>末尾</b>的顶层 LIMIT。
     *
     * <p>以前用的是「任意位置 LIMIT」并整段删除再补 {@code LIMIT 100}，导致两个真实缺陷：
     * ① 字符串字面量里的 {@code 'limit 3'} 被吞成 {@code ''}；
     * ② 子查询里的 {@code LIMIT 1} 被删掉，语义被悄悄改写。
     * 锚定 {@code $} 后，字符串（以引号结尾）与子查询（以 {@code )} 结尾）都不会命中。</p>
     */
    private static final Pattern TRAILING_LIMIT = Pattern.compile(
            "(?is)\\s+limit\\s+\\d+(?:\\s*,\\s*\\d+|\\s+offset\\s+\\d+)?\\s*$");

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
        // 去掉反引号后再做「文本层面」的识别。原实现把反引号当成普通字符，
        // `mysql`.`user` 这类写法会让系统库正则匹配不上（反引号夹在名字与点之间）。
        // 标识符本身是否合法由后面的白名单兜底，去掉反引号只影响识别，不影响执行文本。
        String masked = maskStringLiterals(sql).replace("`", "");
        Matcher forbidden = FORBIDDEN.matcher(masked);
        if (forbidden.find()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "查询包含禁用语法（" + forbidden.group(1).toUpperCase(Locale.ROOT) + "）");
        }
        if (SYSTEM_SCHEMA.matcher(masked).find()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能查询系统库");
        }
        if (LOCKING_READ.matcher(masked).find()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不允许使用加锁读（FOR UPDATE / FOR SHARE）");
        }
        Set<String> known = lowerCase(knownTables);
        Set<String> allowed = allowedTables(knownTables, superAdmin);
        Set<String> ctes = cteNames(masked);
        Set<String> referenced = extractTables(masked);
        if (referencesFrom(masked) && referenced.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法识别查询涉及的表");
        }
        for (String table : referenced) {
            if (ctes.contains(table)) {
                // 关键修复：CTE 名与真实表同名时，MySQL 在 CTE 定义体内部会解析到物理表，
                // 而旧实现无条件豁免 CTE 名，于是
                //   WITH t_user AS (SELECT * FROM t_user) SELECT * FROM t_user
                // 让两处 t_user 都不参与黑名单校验，凭证表被整表读出。
                // 与真实表同名的 CTE 没有正当用途，直接拒绝。
                if (known.contains(table)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "不允许用与真实表同名的 CTE 遮蔽表名");
                }
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
        String withoutLimit = TRAILING_LIMIT.matcher(sql).replaceAll("").trim();
        return withoutLimit + " LIMIT " + MAX_ROWS;
    }

    /**
     * 用 MySQL 自己解析出来的表清单做最终裁决（{@code EXPLAIN FORMAT=JSON} 的结果）。
     *
     * <p>文本正则只能看到"长得像表名"的片段：CTE 遮蔽、括号表引用、反引号包 schema
     * 这些写法都能让提取器漏掉真实访问的表。EXPLAIN 不执行语句，但会完成解析与名字解析，
     * 因此它给出的是"这条 SQL 到底会读哪些表"的权威答案。两道校验都过才执行。</p>
     *
     * @param accessedTables EXPLAIN 输出里的 table_name 集合（不带库名）
     */
    public static void verifyAccessedTables(Set<String> accessedTables, Set<String> knownTables,
                                            boolean superAdmin) {
        if (accessedTables == null || accessedTables.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法识别查询涉及的表");
        }
        Set<String> known = lowerCase(knownTables);
        for (String raw : accessedTables) {
            String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            if (name.isBlank()) {
                continue;
            }
            // EXPLAIN 对派生表/UNION 会给出 <derived2>、<union1,2> 这类伪表名，不是物理表
            if (name.startsWith("<")) {
                continue;
            }
            if (name.contains(".")) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不能查询其他数据库的表");
            }
            if (CREDENTIAL_TABLES.contains(name)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "该表包含凭证或私密数据，不能通过问数查询");
            }
            if (!superAdmin && PRIVATE_TABLES.contains(name)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "只能查询公共数据");
            }
            if (!known.contains(name)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "查询引用了未授权的表");
            }
        }
    }

    private static Set<String> lowerCase(Set<String> tables) {
        Set<String> result = new HashSet<>();
        if (tables == null) {
            return result;
        }
        for (String table : tables) {
            if (table != null) {
                result.add(table.toLowerCase(Locale.ROOT));
            }
        }
        return result;
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
                // 两种写法必须区分开：
                //   1) 派生表 FROM (SELECT ...)：子查询里的 FROM 会被本循环继续扫描到，直接跳过没问题；
                //   2) 括号表引用 FROM (t_user)：MySQL 同样接受，旧实现把它整段跳过，
                //      于是括号里的真实表既不进白名单也不进黑名单（已实测可读 t_user）。
                // 只有确认括号里是查询才能跳过，否则一律拒绝。
                if (!startsWithQuery(rest)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "不允许用括号包裹表名，请直接写 FROM 表名");
                }
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
            if (containsParenthesizedTableRef(segment)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "不允许用括号包裹表名，请直接写 FROM 表名");
            }
            Matcher more = Pattern.compile(",\\s*`?([A-Za-z0-9_]+)`?").matcher(segment);
            while (more.find()) {
                tables.add(more.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    /**
     * 表引用列表里是否出现「括号包裹的表名」，例如 {@code FROM t_knowledge, (t_user)}。
     *
     * <p>这种写法 MySQL 接受，但括号里的表不在 FROM/JOIN 关键字后面，
     * 简单提取器看不到它。先把自己人（派生表子查询）整体替换成占位符，
     * 再检查剩余的表位置是否还有裸括号——这样 {@code FROM (SELECT ...)} 不会被误伤。</p>
     */
    private static boolean containsParenthesizedTableRef(String segment) {
        String masked = maskSubqueries(segment);
        return Pattern.compile("(?:^|,)\\s*\\(\\s*`?[A-Za-z0-9_]").matcher(masked).find();
    }

    /** 把「以 SELECT/WITH 开头的括号块」替换成空格占位符，保留其余字符。 */
    private static String maskSubqueries(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '(' && startsWithQuery(text.substring(i))) {
                int end = matchingParen(text, i);
                out.append("( )");
                i = end < 0 ? text.length() : end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int matchingParen(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 跳过连续左括号与空白后，是否以 SELECT / WITH 开头（即括号里是子查询）。 */
    private static boolean startsWithQuery(String rest) {
        int i = 0;
        while (i < rest.length() && (rest.charAt(i) == '(' || Character.isWhitespace(rest.charAt(i)))) {
            i++;
        }
        return rest.regionMatches(true, i, "SELECT", 0, 6)
                || rest.regionMatches(true, i, "WITH", 0, 4);
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
