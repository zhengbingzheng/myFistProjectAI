package com.example.erupt.model.vo;

import java.time.LocalDateTime;

/**
 * 知识视图对象（视图层出参模型），不直接暴露数据层实体的列名结构。
 *
 * <p>列表接口不返回 {@code content}（取值为 null），正文通过详情接口获取。</p>
 */
public record KnowledgeVO(Long id, String name, String content, String type, Long createUserId, Long updateUserId,
                          LocalDateTime createTime, LocalDateTime updateTime) {
}
