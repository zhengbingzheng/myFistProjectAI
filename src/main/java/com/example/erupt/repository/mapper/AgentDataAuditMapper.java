package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.AgentDataAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 读库审计快照的数据访问：只增不改（没有 update / delete 方法——审计不允许被修改）。
 */
@Mapper
public interface AgentDataAuditMapper {

    int insert(AgentDataAudit audit);

    /** 审计分页（超管视角），按时间倒序；可按提问人过滤 */
    List<AgentDataAudit> selectPage(@Param("userId") String userId,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    long countPage(@Param("userId") String userId);
}
