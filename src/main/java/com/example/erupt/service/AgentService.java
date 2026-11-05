package com.example.erupt.service;

import com.example.erupt.model.dto.AgentChatRequest;
import com.example.erupt.model.vo.AgentChatVO;
import com.example.erupt.model.vo.AgentConversationVO;
import com.example.erupt.model.vo.AgentMessageVO;

import java.util.List;

/**
 * 智能体服务：GLM 多轮对话 + 会话管理。
 *
 * <p>上游配置（API Key / 端点 / 默认模型 / 系统提示词）全部来自数据字典
 * {@code agent_config}，每次调用实时读取——改字典即生效，无需重启。</p>
 */
public interface AgentService {

    /** 发送一条消息：自动建会话 / 校验归属，带最近 20 条历史调用上游，落库并返回回复 */
    AgentChatVO chat(String userId, AgentChatRequest request);

    /** 当前用户的会话列表，按最近对话时间倒序 */
    List<AgentConversationVO> myConversations(String userId);

    /** 某会话的全部消息（仅会话归属者可看） */
    List<AgentMessageVO> messages(String userId, String conversationId);

    /** 软删除会话（仅会话归属者可删） */
    void deleteConversation(String userId, String conversationId);
}
