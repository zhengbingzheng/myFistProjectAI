package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统操作日志的数据访问。
 *
 * <p>只有写入与查询，没有更新和删除——审计记录不允许被修改，
 * 清理策略（按时间归档）留给运维脚本而不是接口。</p>
 */
@Mapper
public interface OperationLogMapper {

    int insert(OperationLog log);

    /** 分页 + 条件查询：关键词（URI/操作人/IP 模糊）、模块、操作类型、结果、时间区间 */
    List<OperationLog> selectByCondition(@Param("keyword") String keyword,
                                         @Param("module") String module,
                                         @Param("operation") String operation,
                                         @Param("result") Integer result,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         @Param("offset") long offset,
                                         @Param("limit") int limit);

    long countByCondition(@Param("keyword") String keyword,
                          @Param("module") String module,
                          @Param("operation") String operation,
                          @Param("result") Integer result,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);
}
