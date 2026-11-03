package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.converter.KnowledgeConverter;
import com.example.erupt.model.dto.BatchDeleteRequest;
import com.example.erupt.model.dto.KnowledgeCreateRequest;
import com.example.erupt.model.dto.KnowledgeQueryRequest;
import com.example.erupt.model.dto.KnowledgeUpdateRequest;
import com.example.erupt.model.vo.KnowledgeVO;
import com.example.erupt.repository.entity.Knowledge;
import com.example.erupt.repository.mapper.KnowledgeMapper;
import com.example.erupt.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 知识服务实现：业务规则与事务边界都在这一层，Controller 与 数据层都不承担业务逻辑。
 */
@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final KnowledgeConverter CONVERTER = Mappers.getMapper(KnowledgeConverter.class);

    /** 分页偏移量上限，超过后直接拒绝，避免 LIMIT 的大 OFFSET 扫描丢弃大量行。 */
    private static final long MAX_OFFSET = 100_000L;

    /** MySQL ngram_token_size=2，短于该长度的关键词命中不了全文索引，需降级为 LIKE。 */
    private static final int NGRAM_MIN_TOKEN = 2;

    /** BOOLEAN MODE 的操作符字符，出现在关键词中会改变检索语义。 */
    private static final Pattern FULLTEXT_OPERATORS = Pattern.compile("[+\\-*\"<>()~@]");

    private final KnowledgeMapper knowledgeMapper;

    /**
     * 是否启用全文检索（要求库表存在 {@code ft_knowledge_search} 索引，见 db/schema.sql）。
     * 关闭时全部走 LIKE；开启后不满足全文条件的关键词仍会自动降级为 LIKE。
     */
    @Value("${app.knowledge.fulltext-enabled:false}")
    private boolean fulltextEnabled;

    public KnowledgeServiceImpl(KnowledgeMapper knowledgeMapper) {
        this.knowledgeMapper = knowledgeMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> create(KnowledgeCreateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (knowledgeMapper.selectByTypeAndName(request.type(), request.name()) != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "同类型下已存在同名知识：" + request.type() + "/" + request.name());
        }
        LocalDateTime now = LocalDateTime.now();
        Knowledge knowledge = new Knowledge();
        knowledge.setKnowledgeName(request.name());
        knowledge.setKnowledgeContent(request.content());
        knowledge.setKnowledgeType(request.type());
        knowledge.setCreateTime(now);
        knowledge.setUpdateTime(now);
        int affected = knowledgeMapper.insert(knowledge);
        if (affected >= 1) {
            log.info("新增知识成功，id={}, type={}, name={}, affected={}", knowledge.getId(),
                    knowledge.getKnowledgeType(), knowledge.getKnowledgeName(), affected);
            return Result.success(null, "新增成功");
        }
        log.warn("新增知识失败，name={}, affected={}", knowledge.getKnowledgeName(), affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "新增失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> update(Long id, KnowledgeUpdateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        Knowledge existing = requireKnowledge(id);
        String newType = request.type() != null ? request.type() : existing.getKnowledgeType();
        String newName = request.name() != null ? request.name() : existing.getKnowledgeName();
        Knowledge duplicated = knowledgeMapper.selectByTypeAndName(newType, newName);
        if (duplicated != null && !Objects.equals(duplicated.getId(), id)) {
            throw new BusinessException(ResultCode.CONFLICT, "同类型下已存在同名知识：" + newType + "/" + newName);
        }
        if (request.name() != null) {
            existing.setKnowledgeName(request.name());
        }
        if (request.content() != null) {
            existing.setKnowledgeContent(request.content());
        }
        if (request.type() != null) {
            existing.setKnowledgeType(request.type());
        }
        existing.setUpdateTime(LocalDateTime.now());
        int affected = knowledgeMapper.updateById(existing);
        if (affected >= 1) {
            log.info("更新知识成功，id={}, type={}, affected={}", id, existing.getKnowledgeType(), affected);
            return Result.success(null, "更新成功");
        }
        log.warn("更新知识失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "更新失败");
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeVO getById(Long id) {
        return toVO(requireKnowledge(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<KnowledgeVO> page(KnowledgeQueryRequest query) {
        Objects.requireNonNull(query, "query 不能为空");
        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        String rawKeyword = normalize(query.keyword());
        String type = normalize(query.type());

        // 全文索引命中不了的场景（单字、纯操作符关键词）自动降级为 LIKE，保证检索结果不退化
        String fullTextKeyword = fulltextEnabled ? toFullTextKeyword(rawKeyword) : null;
        boolean useFullText = fullTextKeyword != null && fullTextKeyword.length() >= NGRAM_MIN_TOKEN;
        String likeKeyword = useFullText ? null : LikeEscape.escape(rawKeyword);

        long total = useFullText
                ? knowledgeMapper.countByConditionFullText(fullTextKeyword, type)
                : knowledgeMapper.countByCondition(likeKeyword, type);
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0L, page, pageSize);
        }
        long offset = (long) (page - 1) * pageSize;
        requireOffsetWithinLimit(offset);
        List<Knowledge> records = useFullText
                ? knowledgeMapper.selectByConditionFullText(fullTextKeyword, type, offset, pageSize)
                : knowledgeMapper.selectByCondition(likeKeyword, type, offset, pageSize);
        List<KnowledgeVO> items = records.stream()
                .map(this::toVO)
                .toList();
        return PageResult.of(items, total, page, pageSize);
    }

    /**
     * 清洗 BOOLEAN MODE 的操作符字符（+ - * " < > ( ) ~ @），否则 "C++"、"@Erupt" 这类输入
     * 会被解析成"必须包含/排除/通配"等语义，导致结果异常甚至查询报错。
     *
     * @return 清洗后的关键词；若清洗后为空（关键词全由操作符组成）返回 null
     */
    private String toFullTextKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String cleaned = FULLTEXT_OPERATORS.matcher(keyword).replaceAll(" ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(Long id) {
        requireKnowledge(id);
        int affected = knowledgeMapper.deleteById(id);
        if (affected >= 1) {
            log.info("删除知识成功，id={}, affected={}", id, affected);
            return Result.success(null, "删除成功");
        }
        log.warn("删除知识失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "删除失败");
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listTypes() {
        List<String> types = knowledgeMapper.selectDistinctTypes();
        if (types == null || types.isEmpty()) {
            log.info("查询知识类型结果为空");
            return Collections.emptyList();
        }
        // SQL 已做 DISTINCT，此处针对 trim 后可能产生的重复值再做一次去重，并保持查询顺序
        List<String> distinctTypes = types.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .distinct()
                .toList();
        log.info("查询知识类型成功，去重后数量={}", distinctTypes.size());
        return distinctTypes;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteBatch(List<Long> ids) {
        List<Long> distinctIds = requireValidIds(ids);
        long existing = knowledgeMapper.countByIds(distinctIds);
        if (existing != distinctIds.size()) {
            // 存在不存在的 id：整批拒绝，避免静默的部分删除
            log.warn("批量删除知识被拒绝，部分 id 不存在，ids={}, 实际存在={}", distinctIds, existing);
            throw new BusinessException(ResultCode.NOT_FOUND, "待删除的记录中包含不存在的数据，未执行删除");
        }
        int affected = knowledgeMapper.deleteByIds(distinctIds);
        if (affected >= 1) {
            log.info("批量删除知识成功，ids={}, affected={}", distinctIds, affected);
            return Result.success(null, "批量删除成功，共删除 " + affected + " 条");
        }
        log.warn("批量删除知识失败，ids={}, affected={}", distinctIds, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "批量删除失败");
    }

    /** 批量删除入参校验：非空、数量上限、剔除 null 并去重。 */
    private List<Long> requireValidIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待删除的 id 列表不能为空");
        }
        if (ids.size() > BatchDeleteRequest.MAX_BATCH_DELETE_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单次批量删除不能超过 " + BatchDeleteRequest.MAX_BATCH_DELETE_SIZE + " 条");
        }
        List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待删除的 id 列表不能为空");
        }
        return distinctIds;
    }

    private Knowledge requireKnowledge(Long id) {
        Objects.requireNonNull(id, "id 不能为空");
        Knowledge knowledge = knowledgeMapper.selectById(id);
        if (knowledge == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识不存在：" + id);
        }
        return knowledge;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 深分页保护：LIMIT 的大 OFFSET 会扫描并丢弃大量行，直接拒绝超限请求。 */
    private void requireOffsetWithinLimit(long offset) {
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量过大，请缩小页码或使用更精确的查询条件");
        }
    }

    /**
     * 实体 → VO：具体映射由 MapStruct 在编译期生成（见 {@code KnowledgeConverter}），
     * 实体新增字段时会自动纳入映射，不会出现漏字段。
     */
    private KnowledgeVO toVO(Knowledge knowledge) {
        return CONVERTER.toVO(knowledge);
    }
}
