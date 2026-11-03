package com.example.erupt.service.impl;

import com.example.erupt.cache.NavigationCache;
import com.example.erupt.cache.event.NavigationChangedEvent;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.converter.NavigationConverter;
import com.example.erupt.model.dto.BatchDeleteRequest;
import com.example.erupt.model.dto.NavigationCreateRequest;
import com.example.erupt.model.dto.NavigationQueryRequest;
import com.example.erupt.model.dto.NavigationUpdateRequest;
import com.example.erupt.model.vo.NavigationVO;
import com.example.erupt.repository.entity.Navigation;
import com.example.erupt.repository.mapper.NavigationMapper;
import com.example.erupt.service.NavigationService;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 导航服务实现：业务规则与事务边界都在这一层，Controller 与数据层都不承担业务逻辑。
 */
@Slf4j
@Service
public class NavigationServiceImpl implements NavigationService {

    private static final NavigationConverter CONVERTER = Mappers.getMapper(NavigationConverter.class);

    /** 分页偏移量上限，超过后直接拒绝，避免 LIMIT 的大 OFFSET 扫描丢弃大量行。 */
    private static final long MAX_OFFSET = 100_000L;

    private final NavigationMapper navigationMapper;
    private final NavigationCache navigationCache;
    /**
     * 用于发出 {@link NavigationChangedEvent}。
     * 缓存清除不在这里直接调用 —— 事件监听器会把它推迟到事务提交之后执行，
     * 避免"未提交的旧数据被并发请求写回缓存"，详见 NavigationCacheEvictor 的类注释。
     */
    private final ApplicationEventPublisher eventPublisher;

    public NavigationServiceImpl(NavigationMapper navigationMapper,
                                 NavigationCache navigationCache,
                                 ApplicationEventPublisher eventPublisher) {
        this.navigationMapper = navigationMapper;
        this.navigationCache = navigationCache;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> create(NavigationCreateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        LocalDateTime now = LocalDateTime.now();
        Navigation navigation = new Navigation();
        navigation.setNavigationName(request.name());
        navigation.setNavigationUrl(request.url());
        navigation.setNavigationImg(request.img());
        navigation.setCat(request.cat());
        navigation.setDescription(request.description());
        navigation.setTag(request.tag());
        navigation.setCreateTime(now);
        navigation.setUpdateTime(now);
        int affected = navigationMapper.insert(navigation);
        if (affected >= 1) {
            log.info("新增导航成功，id={}, cat={}, name={}, affected={}", navigation.getId(),
                    navigation.getCat(), navigation.getNavigationName(), affected);
            // 只在真正写成功之后发出；失败时不发，避免一次失败的请求把全模块缓存冲掉
            eventPublisher.publishEvent(new NavigationChangedEvent("create"));
            return Result.success(null, "新增成功");
        }
        log.warn("新增导航失败，name={}, affected={}", navigation.getNavigationName(), affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "新增失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> update(Long id, NavigationUpdateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        Navigation existing = requireNavigation(id);
        if (request.name() != null) {
            existing.setNavigationName(request.name());
        }
        if (request.url() != null) {
            existing.setNavigationUrl(request.url());
        }
        if (request.img() != null) {
            existing.setNavigationImg(request.img());
        }
        if (request.cat() != null) {
            existing.setCat(request.cat());
        }
        if (request.description() != null) {
            existing.setDescription(request.description());
        }
        if (request.tag() != null) {
            existing.setTag(request.tag());
        }
        existing.setUpdateTime(LocalDateTime.now());
        int affected = navigationMapper.updateById(existing);
        if (affected >= 1) {
            log.info("更新导航成功，id={}, cat={}, affected={}", id, existing.getCat(), affected);
            eventPublisher.publishEvent(new NavigationChangedEvent("update"));
            return Result.success(null, "更新成功");
        }
        log.warn("更新导航失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "更新失败");
    }

    @Override
    @Transactional(readOnly = true)
    public NavigationVO getById(Long id) {
        // 缓存读不到时会执行这段 lambda 回源；TTL 策略由 NavigationCache 统一控制（默认 10 分钟）。
        // 注意 loader 内部可能抛 BusinessException(导航不存在)，该异常会直接向上抛出而不会被缓存
        // —— 把"查不到"记进缓存是错误的，那会让新插入的记录在 TTL 内一律读不到
        return navigationCache.detail(id, () -> toVO(requireNavigation(id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<NavigationVO> page(NavigationQueryRequest query) {
        Objects.requireNonNull(query, "query 不能为空");
        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        String keyword = LikeEscape.escape(normalize(query.keyword()));
        String cat = normalize(query.cat());
        String tag = normalize(query.tag());

        // key 必须用规范化之后的条件来拼：用户多打一个空格就会产生一个全新的缓存 key，
        // 既浪费内存又会让人误以为缓存不生效
        return navigationCache.page(keyword, cat, tag, page, pageSize,
                () -> queryPageFromDb(keyword, cat, tag, page, pageSize));
    }

    /** 分页的真实查库逻辑。仅在缓存未命中或被降级时才走到这里 */
    private PageResult<NavigationVO> queryPageFromDb(String keyword, String cat, String tag,
                                                     int page, int pageSize) {
        long total = navigationMapper.countByCondition(keyword, cat, tag);
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0L, page, pageSize);
        }
        long offset = (long) (page - 1) * pageSize;
        requireOffsetWithinLimit(offset);
        List<NavigationVO> items = navigationMapper.selectByCondition(keyword, cat, tag, offset, pageSize)
                .stream()
                .map(this::toVO)
                .toList();
        return PageResult.of(items, total, page, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(Long id) {
        requireNavigation(id);
        int affected = navigationMapper.deleteById(id);
        if (affected >= 1) {
            log.info("删除导航成功，id={}, affected={}", id, affected);
            eventPublisher.publishEvent(new NavigationChangedEvent("delete"));
            return Result.success(null, "删除成功");
        }
        log.warn("删除导航失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "删除失败");
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listCats() {
        return navigationCache.cats(this::queryDistinctCatsFromDb);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listAllCats() {
        return navigationCache.allCats(this::queryAllCatsFromDb);
    }

    private List<String> queryDistinctCatsFromDb() {
        List<String> cats = navigationMapper.selectDistinctCats();
        if (cats == null || cats.isEmpty()) {
            log.info("查询导航分类结果为空");
            return Collections.emptyList();
        }
        // SQL 已做 DISTINCT，此处针对 trim 后可能产生的重复值再做一次去重，并保持查询顺序
        List<String> distinctCats = cats.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(cat -> !cat.isEmpty())
                .distinct()
                .toList();
        log.info("查询导航分类成功，去重后数量={}", distinctCats.size());
        return distinctCats;
    }

    private List<String> queryAllCatsFromDb() {
        List<String> cats = navigationMapper.selectAllCats();
        if (cats == null || cats.isEmpty()) {
            log.info("查询导航分类（全量）结果为空");
            return Collections.emptyList();
        }
        List<String> allCats = cats.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(cat -> !cat.isEmpty())
                .toList();
        log.info("查询导航分类（全量）成功，数量={}", allCats.size());
        return allCats;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteBatch(List<Long> ids) {
        List<Long> distinctIds = requireValidIds(ids);
        long existing = navigationMapper.countByIds(distinctIds);
        if (existing != distinctIds.size()) {
            // 存在不存在的 id：整批拒绝，避免静默的部分删除
            log.warn("批量删除导航被拒绝，部分 id 不存在，ids={}, 实际存在={}", distinctIds, existing);
            throw new BusinessException(ResultCode.NOT_FOUND, "待删除的记录中包含不存在的数据，未执行删除");
        }
        int affected = navigationMapper.deleteByIds(distinctIds);
        if (affected >= 1) {
            log.info("批量删除导航成功，ids={}, affected={}", distinctIds, affected);
            eventPublisher.publishEvent(new NavigationChangedEvent("deleteBatch:" + affected));
            return Result.success(null, "批量删除成功，共删除 " + affected + " 条");
        }
        log.warn("批量删除导航失败，ids={}, affected={}", distinctIds, affected);
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

    private Navigation requireNavigation(Long id) {
        Objects.requireNonNull(id, "id 不能为空");
        Navigation navigation = navigationMapper.selectById(id);
        if (navigation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "导航不存在：" + id);
        }
        return navigation;
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
     * 实体 → VO：具体映射由 MapStruct 在编译期生成（见 {@code NavigationConverter}），
     * 实体新增字段时会自动纳入映射，不会出现漏字段。
     */
    private NavigationVO toVO(Navigation navigation) {
        return CONVERTER.toVO(navigation);
    }
}
