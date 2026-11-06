package com.example.erupt.model.vo;

import java.util.List;

/**
 * AI 读库（Text-to-SQL）的一次问答结果。
 *
 * <p>SQL 必须原样返回给用户：这是可审计性的底线——用户要能看到「AI 用什么语句
 * 查出了这个结果」，看不懂可以自己判断可信度；只给结果不给语句等于黑箱。</p>
 *
 * @param note 附加说明（如「已自动追加 LIMIT 100」），可能为空
 */
public record AskDataVO(
        String question,
        String sql,
        String model,
        List<String> columns,
        List<List<String>> rows,
        int rowCount,
        long elapsedGenMs,
        long elapsedQueryMs,
        String note) {
}
