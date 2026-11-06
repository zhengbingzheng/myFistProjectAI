package com.example.erupt.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 全局搜索的数据访问：跨模块联合查询。
 *
 * <p>每个模块一个方法、各自 LIMIT，服务层并行调用后合并——
 * 不写 UNION 大 SQL：五个模块的排序语义（按各自 update_time）在 UNION 里没法各自生效。</p>
 *
 * <p>归属规则与各模块列表页一致：知识库 / 导航 / 附件是共享数据不过滤归属；
 * 甘特计划与任务按创建人隔离（ownerId 为 null = 超管不限），任务的归属跟随其所属计划。</p>
 */
@Mapper
public interface SearchMapper {

    /** SQL 返回的原始行：module 常量 + 标题 + 正文前 200 字（服务层再截取关键词上下文） */
    List<Map<String, Object>> searchKnowledge(@Param("keyword") String keyword, @Param("limit") int limit);

    /** 关键词达到全文索引最小词长时走 MATCH，避免对正文做前导通配 LIKE。 */
    List<Map<String, Object>> searchKnowledgeFullText(@Param("keyword") String keyword, @Param("limit") int limit);

    List<Map<String, Object>> searchNavigations(@Param("keyword") String keyword, @Param("limit") int limit);

    List<Map<String, Object>> searchAttachments(@Param("keyword") String keyword, @Param("limit") int limit);

    List<Map<String, Object>> searchPlans(@Param("keyword") String keyword,
                                          @Param("ownerId") String ownerId,
                                          @Param("limit") int limit);

    List<Map<String, Object>> searchTasks(@Param("keyword") String keyword,
                                          @Param("ownerId") String ownerId,
                                          @Param("limit") int limit);
}
