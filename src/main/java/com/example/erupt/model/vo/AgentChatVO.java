package com.example.erupt.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一轮对话的返回。
 *
 * <p>同时带 <b>user 消息</b>与助手回复：前端插入的乐观消息需要被服务端版本替换——
 * 附件是否被解析（{@code parsed}）、消息 id、落库时间只有服务端知道。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatVO {

    /** 会话 ID（新建会话时是新生成的） */
    private String conversationId;
    /** 会话标题（新建会话时取首条消息前 50 字） */
    private String title;
    /** 本轮用户消息（含附件元信息与服务端的解析判定） */
    private AgentMessageVO userMessage;
    /** 助手回复 */
    private AgentMessageVO reply;
}
