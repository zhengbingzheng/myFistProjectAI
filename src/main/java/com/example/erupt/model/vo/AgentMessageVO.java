package com.example.erupt.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体消息。
 *
 * <p>{@code reasoning}（思考过程）与 token 用量只对 assistant 消息有值；
 * 正文是 Markdown，由前端渲染（消毒后），后端不做 HTML 转换——
 * 存原文，渲染形态交给展示层，导出 / 换前端时不受影响。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessageVO {

    private String id;
    /** 角色：user / assistant */
    private String role;
    /** 消息正文（Markdown） */
    private String content;
    /** 随消息发送的附件（仅 user 消息可能有） */
    private java.util.List<AgentAttachmentVO> attachments;
    /** 思考过程（GLM-5.3 reasoning_content），可空 */
    private String reasoning;
    /** 生成模型，仅 assistant 消息有值 */
    private String model;
    /** 提示词 tokens，仅 assistant 消息有值 */
    private Integer promptTokens;
    /** 补全 tokens，仅 assistant 消息有值 */
    private Integer completionTokens;
    /** 上游调用耗时（毫秒），仅 assistant 消息有值 */
    private Integer costMs;
    private LocalDateTime createTime;
}
