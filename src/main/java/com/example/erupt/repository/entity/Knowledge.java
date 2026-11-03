package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识实体（数据层），与库表 {@code erupt-example.t_knowledge} 一一对应。
 *
 * <p>{@code createUserId} / {@code updateUserId} 为审计字段，当前不在应用层填充，
 * 待接入登录态后由拦截器或切面统一赋值。</p>
 *
 * <p>getter/setter 等样板代码由 Lombok 在编译期生成。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Knowledge {

    /** 主键，自增 */
    private Long id;
    /** 标题（knowledge_name） */
    private String knowledgeName;
    /** 正文内容（knowledge_content，UEditor 富文本） */
    private String knowledgeContent;
    /** 类型（knowledge_type） */
    private String knowledgeType;
    /** 创建人 ID（create_user_id） */
    private Long createUserId;
    /** 更新人 ID（update_user_id） */
    private Long updateUserId;
    /** 创建时间（create_time） */
    private LocalDateTime createTime;
    /** 更新时间（update_time） */
    private LocalDateTime updateTime;
}
