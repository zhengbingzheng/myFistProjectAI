package com.example.erupt.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体会话列表项。只暴露列表页需要的字段；
 * 消息正文属于详情接口（{@code GET /api/agent/conversations/{id}/messages}），列表不带。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentConversationVO {

    private String id;
    /** 标题，取首条消息前 50 字 */
    private String title;
    /** 最近一次使用的模型 */
    private String model;
    /** 最近一次对话时间（列表按它倒序） */
    private LocalDateTime updateTime;
}
