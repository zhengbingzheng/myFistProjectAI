package com.example.erupt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能体对话请求。
 *
 * <p>{@code conversationId} 不传表示新建会话（标题取本条消息前 50 字）；
 * {@code model} 不传时取字典 agent_config/default_model；
 * {@code content} 是用户本轮输入，历史上下文由后端从消息表自取，前端不用（也不该）回传。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatRequest {

    /** 会话 ID；空 = 新建会话 */
    @Size(max = 64, message = "会话 ID 非法")
    private String conversationId;

    /** 模型标识：glm-5.3 / glm-5.3-flash；空 = 字典默认值 */
    @Size(max = 40, message = "模型标识非法")
    private String model;

    /** 用户本轮输入；允许只发附件，所以这里不再强制非空（由服务层校验「文本或附件至少一项」） */
    @Size(max = 8000, message = "单条消息最长 8000 字符")
    private String content;

    /**
     * 已上传附件的 ID 列表（先走既有 /api/attachments/upload 上传，再把 id 带进来）。
     * 文件名 / 类型 / 大小都由服务端按 id 查库，不信任前端传的元信息。
     */
    @Size(max = 10, message = "单条消息最多 10 个附件")
    private java.util.List<String> attachmentIds;
}
