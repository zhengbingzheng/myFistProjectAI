package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.model.dto.DictItemRequest;
import com.example.erupt.model.dto.DictItemUpdateRequest;
import com.example.erupt.model.dto.DictTypeRequest;
import com.example.erupt.model.dto.DictTypeUpdateRequest;
import com.example.erupt.model.vo.DictItemVO;
import com.example.erupt.model.vo.DictTypeVO;

import java.util.List;

/**
 * 数据字典业务接口：字典类型与字典项两套 CRUD。
 *
 * <p>两套操作刻意分开而不是合成一个"通用字典接口"：类型的生命周期（删除会连带项）
 * 与项完全不同，混在一起只会让每个方法都多一层类型判断。</p>
 */
public interface DictService {

    /** 字典类型分页列表；keyword 同时匹配编码与名称 */
    PageResult<DictTypeVO> pageTypes(String keyword, int page, int pageSize);

    DictTypeVO createType(DictTypeRequest request, String operator);

    DictTypeVO updateType(String id, DictTypeUpdateRequest request, String operator);

    /** 删除类型：连带软删除其下全部字典项 */
    void deleteType(String id, String operator);

    /** 某类型下的字典项分页列表（管理页面用，含已禁用的项） */
    PageResult<DictItemVO> pageItems(String dictType, int page, int pageSize);

    /**
     * 某类型下全部启用的字典项，不分页、按排序号升序。
     * 供下拉框 / 搜索栏一次取全量：已删除与已禁用的项不会出现。
     */
    List<DictItemVO> listEnabledItems(String dictType);

    DictItemVO createItem(DictItemRequest request, String operator);

    DictItemVO updateItem(String id, DictItemUpdateRequest request, String operator);

    void deleteItem(String id, String operator);
}
