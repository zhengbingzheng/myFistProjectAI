package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 读库审计快照，对应表 {@code t_agent_data_audit}。
 *
 * <p>一行 = 一次 ask-data 调用（成功或失败）。只增不改：
 * 没有 update_time、没有 del_flag，也没有任何删除接口——与 t_operation_log
 * 同一原则，能被 API 删掉的审计就不是审计。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentDataAudit {

    private String id;
    /** 提问人，t_user.id */
    private String userId;
    /** 用户的自然语言问题（原文快照） */
    private String question;
    /** AI 生成并实际执行的 SQL；生成阶段失败（没生成出来）则为空 */
    private String sqlText;
    /** 返回行数；失败则为空 */
    private Integer rowCount;
    /** SQL 生成耗时（含上游往返），失败可空 */
    private Long elapsedGenMs;
    /** SQL 执行耗时，失败可空 */
    private Long elapsedQueryMs;
    /** 0 失败 / 1 成功 */
    private Integer success;
    /** 失败原因（校验拦截 / 执行报错），成功为空 */
    private String errorMsg;
    private LocalDateTime createTime;
}
