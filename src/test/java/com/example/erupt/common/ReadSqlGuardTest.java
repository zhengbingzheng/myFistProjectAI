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
}
