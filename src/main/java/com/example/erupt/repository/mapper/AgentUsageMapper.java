package com.example.erupt.repository.mapper;

import com.example.erupt.model.vo.AgentUsageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体用量统计的数据访问：全部是对 t_agent_message 的聚合查询。
 *
 * <p>消息没有归属列，每条查询都 JOIN 会话表按 create_by 过滤——
 * 与「会话只对创建者可见」的归属规则一致；ownerId 为 null（超管）时不过滤。</p>
 */
@Mapper
public interface AgentUsageMapper {

    /** 汇总：会话数 / 回复数 / token / 累计耗时 */
    AgentUsageVO.Summary selectSummary(@Param("ownerId") String ownerId);

    /** 按模型分布，总 token 降序 */
    List<AgentUsageVO.ModelRow> selectByModel(@Param("ownerId") String ownerId);

    /** 近 30 天逐日趋势（无消耗的天不返回行，前端补零即可） */
    List<AgentUsageVO.DayRow> selectByDay(@Param("ownerId") String ownerId, @Param("days") int days);

    /** 按用户排行（总 token 降序），仅超管视角调用 */
    List<AgentUsageVO.UserRow> selectByUser(@Param("ownerId") String ownerId);
}
