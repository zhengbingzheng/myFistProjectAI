package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.Knowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识数据访问接口（数据层），对应库表 {@code erupt-example.t_knowledge}。
 *
 * <p>只做持久化语义表达，SQL 写在 {@code resources/mapper/KnowledgeMapper.xml}，不做业务判断。</p>
 */
@Mapper
public interface KnowledgeMapper {

    /**
     * 新增知识。主键由应用层生成（32 位字符串），不再依赖数据库自增回填，
     * 因此这里不会把 id 写回入参对象——调用方本来就知道它。
     */
    int insert(Knowledge knowledge);

    /**
     * 按主键查询。
     */
    Knowledge selectById(@Param("id") String id);

    /**
     * 按类型 + 标题查询，用于业务层唯一性校验（库表无唯一索引）。
     */
    Knowledge selectByTypeAndName(@Param("type") String type, @Param("name") String name);

    /**
     * 动态条件查询，排序分页。列表不返回正文大字段，正文通过详情接口获取。
     */
    List<Knowledge> selectByCondition(@Param("keyword") String keyword,
                                      @Param("type") String type,
                                      @Param("offset") long offset,
                                      @Param("limit") int limit);

    /**
     * 动态条件计数。
     */
    long countByCondition(@Param("keyword") String keyword, @Param("type") String type);

    /**
     * 全文检索条件下的动态查询，依赖库表全文索引 {@code ft_knowledge_search}；
     * keyword 由服务层清洗 BOOLEAN MODE 操作符并保证长度满足 ngram_token_size。
     */
    List<Knowledge> selectByConditionFullText(@Param("keyword") String keyword,
                                              @Param("type") String type,
                                              @Param("offset") long offset,
                                              @Param("limit") int limit);

    /**
     * 全文检索条件下的动态计数。
     */
    long countByConditionFullText(@Param("keyword") String keyword, @Param("type") String type);

    /**
     * 按主键选择性更新。
     */
    int updateById(Knowledge knowledge);

    /**
     * 按主键删除。
     */
    int deleteById(@Param("id") String id);

    /**
     * 按主键批量删除。ids 由服务层保证非空且已去重，避免生成 {@code IN ()} 这类非法 SQL。
     *
     * @return 删除的行数
     */
    int deleteByIds(@Param("ids") List<String> ids);

    /**
     * 统计指定主键中实际存在的记录数，用于批量删除前的存在性校验。
     */
    long countByIds(@Param("ids") List<String> ids);

    /**
     * 查询库表中出现过的类型（knowledge_type），SQL 层先做一次 DISTINCT 去重，并剔除 NULL 与空串。
     *
     * @return 去重后的类型列表，按字典序升序
     */
    List<String> selectDistinctTypes();
}
