package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.Navigation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 导航数据访问接口（数据层），对应库表 {@code erupt-example.t_navigation}。
 *
 * <p>只做持久化语义表达，SQL 写在 {@code resources/mapper/NavigationMapper.xml}，不做业务判断。
 * 库表列 {@code desc} 为 MySQL 保留字，SQL 中一律用反引号包裹。</p>
 */
@Mapper
public interface NavigationMapper {

    /**
     * 新增导航。主键由应用层生成（32 位字符串），不再依赖数据库自增回填，
     * 因此这里不会把 id 写回入参对象——调用方本来就知道它。
     */
    int insert(Navigation navigation);

    /**
     * 按主键查询。
     */
    Navigation selectById(@Param("id") String id);

    /**
     * 动态条件查询，排序分页（名称/描述模糊 + 分类/标签精确）。
     */
    List<Navigation> selectByCondition(@Param("keyword") String keyword,
                                       @Param("cat") String cat,
                                       @Param("tag") String tag,
                                       @Param("offset") long offset,
                                       @Param("limit") int limit);

    /**
     * 动态条件计数。
     */
    long countByCondition(@Param("keyword") String keyword,
                          @Param("cat") String cat,
                          @Param("tag") String tag);

    /**
     * 按主键选择性更新。
     */
    int updateById(Navigation navigation);

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
     * 查询库表中出现过的分类（cat），SQL 层先做一次 DISTINCT 去重，并剔除 NULL 与空串。
     *
     * @return 去重后的分类列表，按字典序升序
     */
    List<String> selectDistinctCats();

    /**
     * 查询库表中所有记录的分类（cat），不做去重，保持主键顺序，剔除 NULL 与空串。
     *
     * @return 全部分类列表（含重复值）
     */
    List<String> selectAllCats();

    /**
     * 按分类集合批量查询导航记录，用于导入前的重名索引构建。
     *
     * <p>只查本次导入涉及的分类，而不是全表扫描：导入行数有限、分类更有限，
     * 一次 IN 查询即可覆盖全部待比对记录，避免数据量增长后导入成本跟着涨。</p>
     *
     * @param cats 分类集合，由服务层保证非空、已去重且不含空串
     * @return 命中分类下的全部导航记录
     */
    List<Navigation> selectByCats(@Param("cats") List<String> cats);
}
