package com.example.erupt.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新知识请求参数（视图层入参模型），字段均为可选，非空才更新。
 *
 * @param name    标题，对应 {@code knowledge_name}
 * @param content 正文，对应 {@code knowledge_content}
 * @param type    类型，对应 {@code knowledge_type}
 */
public record KnowledgeUpdateRequest(
        @Size(max = 255, message = "标题不能超过 255 个字符")
        String name,

        String content,

        @Size(max = 255, message = "类型不能超过 255 个字符")
        String type
) {
}
