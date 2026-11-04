package com.example.erupt.service.impl;

import com.example.erupt.common.AuthContext;
import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.Result;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.converter.NavigationConverter;
import com.example.erupt.model.dto.BatchDeleteRequest;
import com.example.erupt.model.dto.NavigationCreateRequest;
import com.example.erupt.model.dto.NavigationImportItemRequest;
import com.example.erupt.model.dto.NavigationImportRequest;
import com.example.erupt.model.dto.NavigationQueryRequest;
import com.example.erupt.model.dto.NavigationUpdateRequest;
import com.example.erupt.model.vo.NavigationImportErrorVO;
import com.example.erupt.model.vo.NavigationImportResultVO;
import com.example.erupt.model.vo.NavigationVO;
import com.example.erupt.repository.entity.Navigation;
import com.example.erupt.repository.mapper.NavigationMapper;
import com.example.erupt.service.NavigationService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 导航服务实现：业务规则与事务边界都在这一层，Controller 与数据层都不承担业务逻辑。
 *
 * <p><b>为什么直接查库、不再走缓存</b>：导航数据量小、变更频繁，而引入缓存带来的
 * 「详情 / 列表 / 分类清单」多处失效链路（事件 + 事务提交后监听 + 前缀批量删除）远比一次查询昂贵。
 * 去掉缓存后，写操作不再需要发出变更事件，读到的始终是当前事务已提交的真实状态，
 * 也就不会再出现「数据已经改了，列表还是旧内容」这类难以解释的现象。</p>
 */
@Slf4j
@Service
public class NavigationServiceImpl implements NavigationService {

    private static final NavigationConverter CONVERTER = Mappers.getMapper(NavigationConverter.class);

    /** 分页偏移量上限，超过后直接拒绝，避免 LIMIT 的大 OFFSET 扫描丢弃大量行。 */
    private static final long MAX_OFFSET = 100_000L;

    /** 单次导出的条数上限：超出后要求先用筛选条件缩小范围，避免一次性查出超大结果集。 */
    private static final long MAX_EXPORT_SIZE = 5_000L;

    /** 允许的跳转协议：只放 http/https，挡掉 javascript:、data: 这类可直接执行脚本的伪协议。 */
    private static final Pattern SAFE_URL = Pattern.compile("^https?://.+$", Pattern.CASE_INSENSITIVE);

    private final NavigationMapper navigationMapper;

    /**
     * 逐行校验器：导入不能用 Controller 的 {@code @Valid}（那会整批拒绝、拿不到行号），
     * 这里在服务层对每一行单独触发，才能把「第几行、什么问题」回给用户。
     */
    private final Validator validator;

    public NavigationServiceImpl(NavigationMapper navigationMapper, Validator validator) {
        this.navigationMapper = navigationMapper;
        this.validator = validator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> create(NavigationCreateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        LocalDateTime now = LocalDateTime.now();
        Navigation navigation = new Navigation();
        navigation.setId(newId());
        // 统一 trim 后再入库：不 trim 的话 "运维 " 与 "运维" 会成为两个分类，
        // 而导入路径本来就 trim，同一份数据走两个入口会得到不同结果。
        navigation.setNavigationName(normalize(request.name()));
        navigation.setNavigationUrl(requireSafeUrl(request.url()));
        navigation.setNavigationImg(normalize(request.img()));
        navigation.setCat(normalize(request.cat()));
        navigation.setDescription(normalize(request.description()));
        navigation.setTag(normalize(request.tag()));
        navigation.setCreateTime(now);
        navigation.setUpdateTime(now);
        navigation.setCreateUserId(AuthContext.currentUserId());
        int affected = navigationMapper.insert(navigation);
        if (affected >= 1) {
            log.info("新增导航成功，id={}, cat={}, name={}, affected={}", navigation.getId(),
                    navigation.getCat(), navigation.getNavigationName(), affected);
            return Result.success(null, "新增成功");
        }
        log.warn("新增导航失败，name={}, affected={}", navigation.getNavigationName(), affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "新增失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> update(String id, NavigationUpdateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        Navigation existing = requireNavigation(id);
        // 语义：null 或空白字符串 = 不更新该字段；有值才更新。
        // 注意「传空串清空字段」目前<b>不支持</b>：normalize 把空白串折成 null，
        // 而 Mapper 的 updateById 用 <if test="x != null"> 决定是否进 SET 子句。
        // 要支持清空需要额外引入「显式置空」的标记，属于接口语义变更，不在本次优化范围。
        // url 是必填语义的字段，传空串会被 requireSafeUrl 直接拒绝，不允许「清空链接」。
        if (request.name() != null) {
            existing.setNavigationName(normalize(request.name()));
        }
        if (request.url() != null) {
            // 与 create / import 共用同一套协议校验：否则改一次链接就能绕过新增时的限制，
            // 存进 javascript: 伪协议，前端渲染成 <a href> 后点击即执行脚本。
            existing.setNavigationUrl(requireSafeUrl(request.url()));
        }
        if (request.img() != null) {
            existing.setNavigationImg(normalize(request.img()));
        }
        if (request.cat() != null) {
            existing.setCat(normalize(request.cat()));
        }
        if (request.description() != null) {
            existing.setDescription(normalize(request.description()));
        }
        if (request.tag() != null) {
            existing.setTag(normalize(request.tag()));
        }
        existing.setUpdateTime(LocalDateTime.now());
        // 更新人 id 与 createUserId 对称：只写 id、不写用户名，
        // 避免把「展示用的账号名」当成审计字段存进库。
        existing.setUpdateUserId(AuthContext.currentUserId());
        int affected = navigationMapper.updateById(existing);
        if (affected >= 1) {
            log.info("更新导航成功，id={}, cat={}, affected={}", id, existing.getCat(), affected);
            return Result.success(null, "更新成功");
        }
        log.warn("更新导航失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "更新失败");
    }

    @Override
    @Transactional(readOnly = true)
    public NavigationVO getById(String id) {
        // 直接查库：导航数据量小，一次主键查询的成本远低于维护缓存一致性的成本
        return toVO(requireNavigation(id));
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

        // 偏移量校验必须放在 count 之前：count 本身就是一次全表条件扫描，
        // 先查再拒绝等于让一个注定被拒的请求白跑一次昂贵的统计。
        long offset = (long) (page - 1) * pageSize;
        requireOffsetWithinLimit(offset);

        long total = navigationMapper.countByCondition(keyword, cat, tag);
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), 0L, page, pageSize);
        }
        List<NavigationVO> items = navigationMapper.selectByCondition(keyword, cat, tag, offset, pageSize)
                .stream()
                .map(this::toVO)
                .toList();
        return PageResult.of(items, total, page, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(String id) {
        requireNavigation(id);
        int affected = navigationMapper.deleteById(id);
        if (affected >= 1) {
            log.info("删除导航成功，id={}, affected={}", id, affected);
            return Result.success(null, "删除成功");
        }
        log.warn("删除导航失败，id={}, affected={}", id, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "删除失败");
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listCats() {
        return queryDistinctCatsFromDb();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listAllCats() {
        return queryAllCatsFromDb();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NavigationImportResultVO importItems(NavigationImportRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "导入数据不能为空");
        }
        List<NavigationImportItemRequest> items = request.items();
        if (items.size() > NavigationImportRequest.MAX_IMPORT_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单次导入不能超过 " + NavigationImportRequest.MAX_IMPORT_SIZE + " 条");
        }
        boolean overwrite = NavigationImportRequest.MODE_OVERWRITE.equals(request.modeOrDefault());

        // 重名索引只覆盖本次涉及的分类，不扫全表
        Map<String, Navigation> existingIndex = loadExistingIndex(items);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<NavigationImportErrorVO> errors = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            // 行号与 Excel 对齐：表头占第 1 行，数据从第 2 行开始
            int row = i + 2;
            NavigationImportItemRequest item = items.get(i);
            if (item == null) {
                failed++;
                errors.add(new NavigationImportErrorVO(row, "", "该行没有数据"));
                continue;
            }

            String violation = collectViolations(item);
            if (violation != null) {
                failed++;
                errors.add(new NavigationImportErrorVO(row, safe(item.name()), violation));
                continue;
            }

            String name = safe(item.name());
            String cat = safe(item.cat());
            String url = safe(item.url());
            // 与 create / update 共用同一套协议校验，避免三个入口各写一份、日后只改一处
            if (!SAFE_URL.matcher(url).matches()) {
                failed++;
                errors.add(new NavigationImportErrorVO(row, name, "链接地址需以 http:// 或 https:// 开头"));
                continue;
            }

            Navigation existing = existingIndex.get(indexKey(cat, name));
            if (existing != null && !overwrite) {
                skipped++;
                continue;
            }
            if (existing != null) {
                if (applyUpdate(existing, name, url, cat, item)) {
                    updated++;
                } else {
                    failed++;
                    errors.add(new NavigationImportErrorVO(row, name, "更新失败，请重试"));
                }
                continue;
            }

            Navigation navigation = new Navigation();
            navigation.setId(newId());
            navigation.setNavigationName(name);
            navigation.setNavigationUrl(url);
            navigation.setNavigationImg(normalize(item.img()));
            navigation.setCat(cat);
            navigation.setDescription(normalize(item.description()));
            navigation.setTag(normalize(item.tag()));
            LocalDateTime now = LocalDateTime.now();
            navigation.setCreateTime(now);
            navigation.setUpdateTime(now);
            // 创建人取当前登录用户：导入是"谁导的算谁的"，
            // 否则这批记录在列表里无法追溯到来源，审计也就断了。
            navigation.setCreateUserId(AuthContext.currentUserId());
            if (navigationMapper.insert(navigation) >= 1) {
                created++;
                // 同批内出现重复行时，后一行应命中重名逻辑而不是再插一条
                existingIndex.put(indexKey(cat, name), navigation);
            } else {
                failed++;
                errors.add(new NavigationImportErrorVO(row, name, "新增失败，请重试"));
            }
        }

        log.info("导入导航完成，total={}, created={}, updated={}, skipped={}, failed={}, mode={}",
                items.size(), created, updated, skipped, failed, request.modeOrDefault());
        return new NavigationImportResultVO(items.size(), created, updated, skipped, failed, errors);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NavigationVO> exportList(NavigationQueryRequest query) {
        Objects.requireNonNull(query, "query 不能为空");
        String keyword = LikeEscape.escape(normalize(query.keyword()));
        String cat = normalize(query.cat());
        String tag = normalize(query.tag());

        long total = navigationMapper.countByCondition(keyword, cat, tag);
        if (total == 0) {
            return Collections.emptyList();
        }
        if (total > MAX_EXPORT_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "导出数据量超过上限（" + MAX_EXPORT_SIZE + " 条），请先缩小筛选范围");
        }
        List<NavigationVO> items = navigationMapper.selectByCondition(keyword, cat, tag, 0L, (int) total)
                .stream()
                .map(this::toVO)
                .toList();
        log.info("导出导航成功，命中数量={}", items.size());
        return items;
    }

    /**
     * 构建「分类 + 名称」重名索引。
     *
     * <p>库表对 (cat, name) 没有唯一索引，重名判定只能靠查询比对；
     * 导入是批量场景，逐行查库会退化成 N 次查询，这里按涉及分类一次取回再内存建索引。</p>
     */
    private Map<String, Navigation> loadExistingIndex(List<NavigationImportItemRequest> items) {
        List<String> cats = items.stream()
                .filter(Objects::nonNull)
                .map(NavigationImportItemRequest::cat)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(cat -> !cat.isEmpty())
                .distinct()
                .toList();
        if (cats.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Navigation> index = new HashMap<>();
        for (Navigation navigation : navigationMapper.selectByCats(cats)) {
            index.put(indexKey(navigation.getCat(), navigation.getNavigationName()), navigation);
        }
        return index;
    }

    /** 重名键：分类 + 名称。用不可见分隔符拼接，避免 "A"+"BC" 与 "AB"+"C" 撞键。 */
    private String indexKey(String cat, String name) {
        return safe(cat) + '\u0000' + safe(name);
    }

    /** 覆盖更新：字段语义与单条更新接口保持一致（入参为空的字段不覆盖已有值）。 */
    private boolean applyUpdate(Navigation existing, String name, String url, String cat,
                                NavigationImportItemRequest item) {
        existing.setNavigationName(name);
        existing.setNavigationUrl(url);
        existing.setNavigationImg(normalize(item.img()));
        existing.setCat(cat);
        existing.setDescription(normalize(item.description()));
        existing.setTag(normalize(item.tag()));
        existing.setUpdateTime(LocalDateTime.now());
        // 与单条 update 一致：覆盖更新同样要记下是谁改的，否则导入覆盖后审计链断在这里
        existing.setUpdateUserId(AuthContext.currentUserId());
        return navigationMapper.updateById(existing) >= 1;
    }

    /** 逐行校验：把该行的所有约束违规合并成一条中文提示，避免用户一行一行改完再报下一个错。 */
    private String collectViolations(NavigationImportItemRequest item) {
        Set<ConstraintViolation<NavigationImportItemRequest>> violations = validator.validate(item);
        if (violations.isEmpty()) {
            return null;
        }
        return violations.stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("；"));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
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
        log.info("查询导航分类成功，去重后的分类数量={}", distinctCats.size());
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
    public Result<Void> deleteBatch(List<String> ids) {
        List<String> distinctIds = requireValidIds(ids);
        long existing = navigationMapper.countByIds(distinctIds);
        if (existing != distinctIds.size()) {
            // 存在不存在的 id：整批拒绝，避免静默的部分删除
            log.warn("批量删除导航被拒绝，部分 id 不存在，ids={}, 实际存在={}", distinctIds, existing);
            throw new BusinessException(ResultCode.NOT_FOUND, "待删除的记录中包含不存在的数据，未执行删除");
        }
        int affected = navigationMapper.deleteByIds(distinctIds);
        if (affected >= 1) {
            log.info("批量删除导航成功，ids={}, affected={}", distinctIds, affected);
            return Result.success(null, "批量删除成功，共删除 " + affected + " 条");
        }
        log.warn("批量删除导航失败，ids={}, affected={}", distinctIds, affected);
        return Result.failure(ResultCode.INTERNAL_ERROR, "批量删除失败");
    }

    /** 批量删除入参校验：非空、数量上限、剔除 null 并去重。 */
    private List<String> requireValidIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待删除的 id 列表不能为空");
        }
        if (ids.size() > BatchDeleteRequest.MAX_BATCH_DELETE_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单次批量删除不能超过 " + BatchDeleteRequest.MAX_BATCH_DELETE_SIZE + " 条");
        }
        List<String> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "待删除的 id 列表不能为空");
        }
        return distinctIds;
    }

    private Navigation requireNavigation(String id) {
        Objects.requireNonNull(id, "id 不能为空");
        Navigation navigation = navigationMapper.selectById(id);
        if (navigation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "导航不存在：" + id);
        }
        return navigation;
    }

    /**
     * 生成主键：32 位字符串（UUID 去横线）。
     * 主键由应用层生成而非数据库自增，是为了让写入前就能拿到 id（无需回读自增），
     * 也避免将来数据合并/迁移时主键冲突，与本项目其它表的主键策略保持一致。
     */
    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 校验并归一化跳转地址：只放行 http/https。
     *
     * <p><b>为什么必须在服务层统一校验</b>：导入路径早就用 {@link #SAFE_URL} 挡掉了
     * {@code javascript:}、{@code data:} 这类伪协议，但单条的 create / update 一直没校验。
     * 结果同一条数据走「新增」能存进 {@code javascript:alert(1)}，走「导入」却被拒——
     * 校验口径分裂，等于给 update 留了一个绕过口子：先存一个合法链接，再改一次即可。
     * 前端把这个值渲染成 {@code <a href>} 后，用户点击就在本站域下执行了脚本。</p>
     *
     * @return 去除首尾空白后的地址
     * @throws BusinessException 协议不在白名单内时抛 {@code BAD_REQUEST}
     */
    private String requireSafeUrl(String url) {
        String normalized = normalize(url);
        if (normalized == null || !SAFE_URL.matcher(normalized).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "跳转地址需以 http:// 或 https:// 开头");
        }
        return normalized;
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
