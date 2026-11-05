package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.AgentConversation;
import com.example.erupt.repository.entity.AgentMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体的数据访问：会话表与消息表集中于此。
 *
 * <p>理由同 {@code DictMapper}：会话与消息天然属于同一个事务边界（一轮对话要写两条消息、
 * 刷一次会话），放一起让边界落在 Service 层。</p>
 */
@Mapper
public interface AgentMapper {

    /* ---------- 会话 ---------- */

    int insertConversation(AgentConversation conversation);

    /** 每轮对话后刷新会话的模型与更新时间（列表按 update_time 排序，最近聊过的排最前） */
    int touchConversation(@Param("id") String id,
                          @Param("model") String model,
                          @Param("updateBy") String updateBy);

    AgentConversation selectConversationById(@Param("id") String id);

    /** 某用户的会话列表，按 update_time 降序 */
    List<AgentConversation> selectConversationsByUser(@Param("userId") String userId,
                                                      @Param("limit") int limit);

    /** 软删除会话：置 del_flag='1'；消息不删，随会话一起不可达 */
    int logicDeleteConversation(@Param("id") String id, @Param("updateBy") String updateBy);

    /* ---------- 消息 ---------- */

    int insertMessage(AgentMessage message);

    /** 会话全部消息，按时间升序——「查看历史」用 */
    List<AgentMessage> selectMessagesAsc(@Param("conversationId") String conversationId);

    /**
     * 会话最近 N 条消息，按时间降序返回——组装多轮上下文用；
     * N 条的原因是控制每次请求的 token 体量，调用方自行反转为正序
     */
    List<AgentMessage> selectRecentMessages(@Param("conversationId") String conversationId,
                                            @Param("limit") int limit);
}
