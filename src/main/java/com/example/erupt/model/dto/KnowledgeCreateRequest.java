package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增知识请求参数（视图层入参模型）。
 *
 * @param name    标题，对应 {@code knowledge_name}
 * @param content 正文，对应 {@code knowledge_content}（UEditor 富文本）
 * @param type    类型，对应 {@code knowledge_type}
 */
public record KnowledgeCreateRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 255, message = "标题不能超过 255 个字符")
        String name,

        @NotBlank(message = "正文不能为空")
        // knowledge_content 是 mediumtext（16MB），不加约束时单条请求可合法携带数十 MB 正文，
        // 是内存与存储层面的低成本滥用面；1MB 对富文本已非常宽裕
        @Size(max = 1_000_000, message = "正文不能超过 100 万个字符")
        String content,

        @NotBlank(message = "类型不能为空")
        @Size(max = 255, message = "类型不能超过 255 个字符")
        String type
) {
}
