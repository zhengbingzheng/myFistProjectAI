package com.example.erupt.service;

import com.example.erupt.model.vo.AskDataVO;

/**
 * AI 读库（Text-to-SQL 只读版）：自然语言问数据，AI 生成 SQL，系统校验后只读执行。
 *
 * <p>安全边界（缺一不可）：</p>
 * <ol>
 *   <li>SQL 形态校验：只允许单条 SELECT（WITH 开头的 CTE 也放行），禁写关键词；</li>
 *   <li>强制 LIMIT：没有 LIMIT 自动补 LIMIT 100，防止一次拉垮内存；</li>
 *   <li>连接级只读：setReadOnly(true)，即使校验漏网，MySQL 也拒绝写操作；</li>
 *   <li>结果上限：超过 100 行截断并提示，SQL 与结果都原样展示给用户（可审计）。</li>
 * </ol>
 */
public interface AgentDataService {

    /**
     * 提问并查询。
     *
     * @param userId     当前用户，写入审计
     * @param question   自然语言问题（如「知识库里有多少篇文档」）
     * @param superAdmin 超管可以查按人隔离的业务表；凭证表对谁都不开放
     */
    AskDataVO ask(String userId, String question, boolean superAdmin);

    /**
     * 审计快照分页（仅超管入口）：按时间倒序。
     * 只增不改——审计记录不允许通过任何路径修改或删除。
     */
    java.util.Map<String, Object> auditPage(int page, int pageSize);
}
