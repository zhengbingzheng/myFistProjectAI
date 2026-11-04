package com.example.erupt.repository.mapper;

import com.example.erupt.repository.entity.Dict;
import com.example.erupt.repository.entity.DictItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据字典的数据访问：类型表与项表集中于此。
 *
 * <p>理由同 {@code MenuMapper}：删除一个类型需要连带处理它的全部字典项，
 * 两张表的写操作天然属于同一个事务边界，放一起能让这个边界落在 Service 层而不是散在两处。</p>
 */
@Mapper
public interface DictMapper {

    /* ---------- 字典类型 ---------- */

    int insertDict(Dict dict);

    int updateDict(Dict dict);

    /** 软删除类型：置 del_flag='1'，不物理删除 */
    int logicDeleteDict(@Param("id") String id, @Param("updateBy") String updateBy);

    Dict selectDictById(@Param("id") String id);

    /** 按编码查类型：新增时查重、新增项时校验类型存在 */
    Dict selectDictByType(@Param("dictType") String dictType);

    /** 分页列表；keyword 已由服务层做 LIKE 转义，此处直接拼接 */
    List<Dict> selectDicts(@Param("keyword") String keyword,
                           @Param("offset") int offset,
                           @Param("limit") int limit);

    long countDicts(@Param("keyword") String keyword);

    /* ---------- 字典项 ---------- */

    int insertItem(DictItem item);

    int updateItem(DictItem item);

    /** 软删除单个字典项 */
    int logicDeleteItem(@Param("id") String id, @Param("updateBy") String updateBy);

    DictItem selectItemById(@Param("id") String id);

    /** 某类型下的字典项，按排序号升序（分页，管理页面用：含已禁用的项） */
    List<DictItem> selectItemsByType(@Param("dictType") String dictType,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    /**
     * 某类型下全部可用字典项：未删除 且 启用，按排序号升序，不分页。
     * 供下拉框 / 搜索栏一次取全量，避免用分页接口传一个很大的 pageSize 去"猜"全量。
     */
    List<DictItem> selectEnabledItemsByType(@Param("dictType") String dictType);

    long countItems(@Param("dictType") String dictType);

    /**
     * 同类型下同一 value 是否已存在。
     * excludeId 用于编辑场景排除自身，否则"只改排序不改值"会被判成重复。
     */
    int countItemValue(@Param("dictType") String dictType,
                       @Param("value") String value,
                       @Param("excludeId") String excludeId);

    /** 某类型下未删除的项数：删除类型前用于提示，也用于连带软删除的判定 */
    int countItemsByType(@Param("dictType") String dictType);

    /** 连带软删除某类型的全部字典项 */
    int logicDeleteItemsByType(@Param("dictType") String dictType, @Param("updateBy") String updateBy);
}
