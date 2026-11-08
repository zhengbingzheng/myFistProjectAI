package com.example.erupt.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadSqlGuardTest {

    private static final Set<String> KNOWN = Set.of(
            "t_knowledge", "t_navigation", "t_gantt_plan", "t_user", "t_dict_item", "t_chat_message");

    @Test
    @DisplayName("单表 SELECT 会补上上限")
    void appendsLimit() {
        String sql = ReadSqlGuard.normalize("SELECT * FROM t_knowledge", KNOWN, false);
        assertThat(sql).endsWith("LIMIT " + ReadSqlGuard.MAX_ROWS);
        assertThat(sql.toLowerCase()).contains("from t_knowledge");
    }

    @Test
    @DisplayName("字符串里的表名不算引用")
    void ignoresTableNameInsideString() {
        String sql = ReadSqlGuard.normalize(
                "SELECT 't_user' AS label FROM t_knowledge", KNOWN, false);
        assertThat(sql.toLowerCase()).contains("from t_knowledge");
    }

    @Test
    @DisplayName("密码表、字典项、私聊即使超管也不许查")
    void credentialTablesRejectedForAdmin() {
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT password FROM t_user", KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT value FROM t_dict_item WHERE label = 'API Key'", KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT content FROM t_chat_message", KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("注释拆开的表名仍然拦住")
    void commentSplitDoesNotBypass() {
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT * FROM t_us/**/er", KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("非超管不能查甘特，超管可以")
    void privateTablesDependOnRole() {
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT * FROM t_gantt_plan", KNOWN, false))
                .isInstanceOf(BusinessException.class);
        String sql = ReadSqlGuard.normalize("SELECT * FROM t_gantt_plan", KNOWN, true);
        assertThat(sql.toLowerCase()).contains("t_gantt_plan");
    }

    @Test
    @DisplayName("多语句、写操作、SLEEP、DUMPFILE、系统库一律拒绝")
    void dangerousShapesRejected() {
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT 1; DELETE FROM t_knowledge", KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize("DELETE FROM t_knowledge", KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT SLEEP(10)", KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge INTO DUMPFILE '/tmp/x'", KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT user FROM mysql.user", KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("逗号连接和子查询里的敏感表也要识别")
    void joinsAndSubqueriesAreScanned() {
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge, t_user", KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge WHERE id IN (SELECT id FROM t_user)", KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("模型写的超大 LIMIT 会被收成上限")
    void oversizedLimitIsCapped() {
        String sql = ReadSqlGuard.normalize("SELECT id FROM t_knowledge LIMIT 1000000", KNOWN, false);
        assertThat(sql).doesNotContain("1000000");
        assertThat(sql).endsWith("LIMIT " + ReadSqlGuard.MAX_ROWS);
    }

    @Test
    @DisplayName("不在当前库里的表直接拒绝")
    void unknownTableRejected() {
        assertThatThrownBy(() -> ReadSqlGuard.normalize("SELECT * FROM t_not_exists", KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("CTE 与真实表同名遮蔽_拒绝")
    void cteShadowingKnownTableRejected() {
        // MySQL 在 CTE 定义体内部把同名引用解析到物理表，旧实现无条件豁免 CTE 名，
        // 这条语句可以整表读出 t_user
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "WITH t_user AS (SELECT * FROM t_user) SELECT * FROM t_user", KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("括号包裹的表引用_拒绝")
    void parenthesizedTableReferenceRejected() {
        // FROM (t_user) 在 MySQL 合法，但旧提取器看到 "(" 就整段跳过，
        // 括号里的表既不过黑名单也不进白名单
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT password FROM t_knowledge, (t_user)", KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("派生表（括号里是子查询）仍然允许")
    void derivedTableStillAllowed() {
        String sql = ReadSqlGuard.normalize(
                "SELECT * FROM (SELECT id FROM t_knowledge) x", KNOWN, false);
        assertThat(sql.toLowerCase()).contains("t_knowledge");
        assertThat(sql).endsWith("LIMIT " + ReadSqlGuard.MAX_ROWS);
    }

    @Test
    @DisplayName("字符串与子查询里的 LIMIT 不被改写")
    void limitInsideStringAndSubqueryPreserved() {
        String withString = ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge WHERE title = 'limit 3'", KNOWN, false);
        assertThat(withString).contains("'limit 3'");
        assertThat(withString).endsWith("LIMIT " + ReadSqlGuard.MAX_ROWS);

        String withSubquery = ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge WHERE id IN (SELECT id FROM t_knowledge LIMIT 1)", KNOWN, false);
        assertThat(withSubquery).contains("LIMIT 1)");
    }

    @Test
    @DisplayName("EXPLAIN 复核：凭证表、跨库、未知表、空结果一律拒绝")
    void verifiedAccessedTablesRejected() {
        assertThatThrownBy(() -> ReadSqlGuard.verifyAccessedTables(Set.of("t_user"), KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.verifyAccessedTables(Set.of("mysql.user"), KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.verifyAccessedTables(Set.of("t_unknown"), KNOWN, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.verifyAccessedTables(Set.of(), KNOWN, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("EXPLAIN 复核：公共表与派生表伪表名放行")
    void verifiedAccessedTablesAllowsSharedAndDerived() {
        ReadSqlGuard.verifyAccessedTables(Set.of("t_knowledge", "<derived2>"), KNOWN, false);
    }

    @Test
    @DisplayName("EXPLAIN 复核：私有表非超管拒绝、超管放行")
    void verifiedAccessedTablesRespectsDataScope() {
        assertThatThrownBy(() -> ReadSqlGuard.verifyAccessedTables(Set.of("t_gantt_plan"), KNOWN, false))
                .isInstanceOf(BusinessException.class);
        ReadSqlGuard.verifyAccessedTables(Set.of("t_gantt_plan"), KNOWN, true);
    }

    @Test
    @DisplayName("加锁读一律拒绝_FOR SHARE 与 LOCK IN SHARE MODE 不在写关键词清单里")
    void lockingReadsRejected() {
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge FOR SHARE", KNOWN, false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge LOCK IN SHARE MODE", KNOWN, false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReadSqlGuard.normalize(
                "SELECT * FROM t_knowledge FOR UPDATE", KNOWN, false))
                .isInstanceOf(BusinessException.class);
        ReadSqlGuard.normalize("SELECT * FROM t_knowledge", KNOWN, false);
    }
}
