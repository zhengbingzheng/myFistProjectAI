package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识实体（数据层），与库表 {@code erupt-example.t_knowledge} 一一对应。
 *
 * <p>{@code createUserId} / {@code updateUserId} 为审计字段：新增时由服务层从
 * {@code AuthContext} 取当前登录用户 id 写入 {@code createUserId}，
 * 更新时写入 {@code updateUserId}，与导航模块保持一致。</p>
 *
 * <p>getter/setter 等样板代码由 Lombok 在编译期生成。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Knowledge {

    /** 主键，32 位字符串（应用层生成的 UUID 去横线），不使用数据库自增 */
    private String id;
    /** 标题（knowledge_name） */
    private String knowledgeName;
    /** 正文内容（knowledge_content，UEditor 富文本） */
    private String knowledgeContent;
    /** 类型（knowledge_type） */
    private String knowledgeType;
    /**
     * 创建人 ID（create_user_id）。
     *
     * <p>类型是 String 而非 Long：库表该列已是 varchar(64)，存的是 {@code t_user.id}
     * （随机串主键），用 Long 既装不下也沒法与 t_user 关联。</p>
     */
    private String createUserId;
    /** 更新人 ID（update_user_id），同为 {@code t_user.id} */
    private String updateUserId;
    /** 创建时间（create_time） */
    private LocalDateTime createTime;
    /** 更新时间（update_time） */
    private LocalDateTime updateTime;
}
