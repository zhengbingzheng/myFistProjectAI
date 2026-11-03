package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.NavigationCreateRequest;
import com.example.erupt.model.dto.NavigationQueryRequest;
import com.example.erupt.model.dto.NavigationUpdateRequest;
import com.example.erupt.model.vo.NavigationVO;

import java.util.List;

/**
 * 导航服务接口（服务层）：定义导航的 CRUD 与分类查询能力。
 */
public interface NavigationService {

    /**
     * 新增导航，库表无唯一索引，不做重名校验；
     * 根据插入 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> create(NavigationCreateRequest request);

    /**
     * 按主键更新导航，入参为空的字段不更新。记录不存在时抛出 {@code NOT_FOUND}；
     * 根据更新 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> update(Long id, NavigationUpdateRequest request);

    /**
     * 按主键查询详情，不存在时抛出 {@code NOT_FOUND}。
     */
    NavigationVO getById(Long id);

    /**
     * 分页条件查询。
     */
    PageResult<NavigationVO> page(NavigationQueryRequest query);

    /**
     * 按主键删除。记录不存在时抛出 {@code NOT_FOUND}；
     * 根据删除 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> delete(Long id);

    /**
     * 批量删除导航。ids 为空或数量超限时抛 {@code BAD_REQUEST}；
     * ids 中存在库表没有的记录时抛 {@code NOT_FOUND} 并整批拒绝，不会产生"部分删除"；
     * 根据删除 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> deleteBatch(List<Long> ids);

    /**
     * 查询全部导航分类（cat），结果去重、剔除空值并按字典序升序排列。
     *
     * @return 去重后的分类列表，无数据时返回空列表
     */
    List<String> listCats();

    /**
     * 查询全部导航分类（cat），不去重，保持记录顺序。
     *
     * @return 全部分类列表（含重复值），无数据时返回空列表
     */
    List<String> listAllCats();
}
