package com.example.erupt.repository.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体-消息实体，对应表 {@code t_agent_message}。
 *
 * <p>一行 = 一条 user / assistant 消息。多轮对话的上下文就是从这里按时间取最近 N 条
 * 原样回传给模型——GLM 的 API 本身无状态，历史必须客户端自己带。</p>
 *
 * <p>{@code reasoning} 是 GLM-5.3 的思考过程（{@code reasoning_content}）：思考始终开启、
 * 关不掉，与其丢掉不如存下来给前端折叠展示。token 用量与耗时只对 assistant 消息有意义。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    private String id;
    /** 所属会话，业务关联 t_agent_conversation.id */
    private String conversationId;
    /** 角色：user / assistant */
    private String role;
    /** 消息正文（Markdown）；附件正文不落这里，避免回显与上下文重复膨胀 */
    private String content;
    /** 附件元信息 JSON 数组（id / name / contentType / size / parsed），文件本体在 t_attachment */
    private String attachments;
    /** 思考过程，仅 assistant 消息可能有 */
    private String reasoning;
    /** 生成该消息的模型，仅 assistant 消息有值 */
    private String model;
    /** 提示词 tokens，仅 assistant 消息有值 */
    private Integer promptTokens;
    /** 补全 tokens，仅 assistant 消息有值 */
    private Integer completionTokens;
    /** 上游调用耗时（毫秒），仅 assistant 消息有值 */
    private Integer costMs;
    private LocalDateTime createTime;
}
