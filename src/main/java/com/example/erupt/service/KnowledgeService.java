package com.example.erupt.service;

import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.model.dto.KnowledgeCreateRequest;
import com.example.erupt.model.dto.KnowledgeQueryRequest;
import com.example.erupt.model.dto.KnowledgeUpdateRequest;
import com.example.erupt.model.vo.KnowledgeVO;

import java.util.List;

/**
 * 知识服务（服务层）：用例编排与事务边界，依赖数据层的 {@code KnowledgeMapper}。
 */
public interface KnowledgeService {

    /**
     * 新增知识，同一类型下标题重复时抛 {@link com.example.erupt.common.BusinessException}；
     * 根据插入 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> create(KnowledgeCreateRequest request);

    /**
     * 按主键选择性更新知识。记录不存在时抛 {@link com.example.erupt.common.BusinessException}；
     * 根据更新 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> update(Long id, KnowledgeUpdateRequest request);

    /**
     * 查询知识详情（含正文），不存在时抛 {@link com.example.erupt.common.BusinessException}。
     */
    KnowledgeVO getById(Long id);

    /**
     * 分页/条件查询知识列表（不含正文）。
     */
    PageResult<KnowledgeVO> page(KnowledgeQueryRequest query);

    /**
     * 删除知识。记录不存在时抛 {@link com.example.erupt.common.BusinessException}；
     * 根据删除 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> delete(Long id);

    /**
     * 批量删除知识。ids 为空或数量超限时抛 {@code BAD_REQUEST}；
     * ids 中存在库表没有的记录时抛 {@code NOT_FOUND} 并整批拒绝，不会产生"部分删除"；
     * 根据删除 SQL 影响行数返回成功或失败标识。
     */
    Result<Void> deleteBatch(List<Long> ids);

    /**
     * 查询全部知识类型（knowledge_type），结果去重、剔除空值并按字典序升序排列。
     *
     * @return 去重后的类型列表，无数据时返回空列表
     */
    List<String> listTypes();
}
