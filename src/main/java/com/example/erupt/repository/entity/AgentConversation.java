package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体-会话实体，对应表 {@code t_agent_conversation}。
 *
 * <p>一行 = 一次多轮对话。会话只对创建者可见（{@code userId} 过滤），
 * 删除走软删除——消息表不设 del_flag，跟随会话生命周期。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentConversation {

    private String id;
    /** 归属用户，业务关联 t_user.id */
    private String userId;
    /** 标题，取首条消息前 50 字 */
    private String title;
    /** 会话使用的模型，如 glm-5.3 / glm-5.3-flash */
    private String model;
    /** 删除标记：0 正常，1 已删除 */
    private String delFlag;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
