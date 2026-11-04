package com.example.erupt.service.impl;

import com.example.erupt.common.BusinessException;
import com.example.erupt.common.LikeEscape;
import com.example.erupt.common.PageResult;
import com.example.erupt.common.ResultCode;
import com.example.erupt.model.dto.DictItemRequest;
import com.example.erupt.model.dto.DictItemUpdateRequest;
import com.example.erupt.model.dto.DictTypeRequest;
import com.example.erupt.model.dto.DictTypeUpdateRequest;
import com.example.erupt.model.vo.DictItemVO;
import com.example.erupt.model.vo.DictTypeVO;
import com.example.erupt.repository.entity.Dict;
import com.example.erupt.repository.entity.DictItem;
import com.example.erupt.repository.mapper.DictMapper;
import com.example.erupt.service.DictService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 数据字典业务实现。
 *
 * <p><b>为什么删除是软删除</b>：字典项被业务数据的历史记录引用（存的是 value 而不是 id），
 * 物理删除后那些历史值就变成了无法解释的字符串。保留记录、只置 {@code del_flag}，
 * 既满足了"列表里不再出现"，又留下了追溯的依据。</p>
 *
 * <p><b>为什么删除类型要连带删项</b>：类型没了，它的项就永远取不到，
 * 留着只会让"按类型查项"的统计数字对不上。两句话写在同一个事务里，要么都成功要么都不动。</p>
 */
@Slf4j
@Service
public class DictServiceImpl implements DictService {

    /** 每页上限：字典是配置数据，量不会大，但要防住一次拉全表 */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final long MAX_OFFSET = 100_000L;

    private final DictMapper dictMapper;

    public DictServiceImpl(DictMapper dictMapper) {
        this.dictMapper = dictMapper;
    }

    /* ==================== 字典类型 ==================== */

    @Override
    public PageResult<DictTypeVO> pageTypes(String keyword, int page, int pageSize) {
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(pageSize);
        String kw = LikeEscape.escape(normalize(keyword));
        int offset = requireOffset(safePage, safeSize);

        long total = dictMapper.countDicts(kw);
        List<DictTypeVO> items = dictMapper.selectDicts(kw, offset, safeSize).stream()
                .map(DictTypeVO::of)
                .toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    @Override
    public DictTypeVO createType(DictTypeRequest request, String operator) {
        Objects.requireNonNull(request, "request 不能为空");
        String dictType = normalize(request.dictType());
        if (dictType == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "字典类型编码不能为空");
        }
        // 编码唯一由数据库的生成列约束兜底，但那会抛数据层异常；
        // 在这里先查一次，才能给前端一个能直接展示的中文提示
        if (dictMapper.selectDictByType(dictType) != null) {
            log.warn("新增字典类型被拒绝，编码已存在：dictType={}", dictType);
            throw new BusinessException(ResultCode.CONFLICT, "字典类型编码已存在：" + dictType);
        }

        Dict dict = new Dict();
        dict.setId(newId());
        dict.setDictType(dictType);
        dict.setDictName(normalize(request.dictName()));
        dict.setStatus(request.status() == null ? 1 : request.status());
        dict.setRemark(normalize(request.remark()));
        dict.setCreateBy(operator);

        int affected = dictMapper.insertDict(dict);
        if (affected < 1) {
            log.warn("新增字典类型失败，dictType={}, affected={}", dictType, affected);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "新增字典类型失败");
        }
        log.info("新增字典类型成功，id={}, dictType={}, operator={}", dict.getId(), dictType, operator);
        return DictTypeVO.of(dictMapper.selectDictById(dict.getId()));
    }

    @Override
    public DictTypeVO updateType(String id, DictTypeUpdateRequest request, String operator) {
        Objects.requireNonNull(request, "request 不能为空");
        Dict dict = requireDict(id);

        Dict patch = new Dict();
        patch.setId(id);
        patch.setDictName(normalize(request.dictName()));
        patch.setStatus(request.status() == null ? dict.getStatus() : request.status());
        patch.setRemark(normalize(request.remark()));
        patch.setUpdateBy(operator);

        int affected = dictMapper.updateDict(patch);
        if (affected < 1) {
            log.warn("更新字典类型失败，id={}, affected={}", id, affected);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新字典类型失败");
        }
        log.info("更新字典类型成功，id={}, operator={}", id, operator);
        return DictTypeVO.of(dictMapper.selectDictById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteType(String id, String operator) {
        Dict dict = requireDict(id);
        String dictType = dict.getDictType();

        int itemCount = dictMapper.countItemsByType(dictType);
        int affected = dictMapper.logicDeleteDict(id, operator);
        if (affected < 1) {
            log.warn("删除字典类型失败，id={}, affected={}", id, affected);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "删除字典类型失败");
        }
        if (itemCount > 0) {
            dictMapper.logicDeleteItemsByType(dictType, operator);
        }
        log.info("删除字典类型成功，id={}, dictType={}, 连带删除字典项 {} 条, operator={}",
                id, dictType, itemCount, operator);
    }

    /* ==================== 字典项 ==================== */

    @Override
    public PageResult<DictItemVO> pageItems(String dictType, int page, int pageSize) {
        String type = normalize(dictType);
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(pageSize);

        // 未选中类型时返回空页而不是报错：页面刚打开、还没点任何一行时就是这个状态
        if (type == null) {
            return PageResult.of(List.of(), 0L, safePage, safeSize);
        }

        int offset = requireOffset(safePage, safeSize);
        long total = dictMapper.countItems(type);
        List<DictItemVO> items = dictMapper.selectItemsByType(type, offset, safeSize).stream()
                .map(DictItemVO::of)
                .toList();
        return PageResult.of(items, total, safePage, safeSize);
    }

    @Override
    public List<DictItemVO> listEnabledItems(String dictType) {
        String type = normalize(dictType);
        if (type == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "字典类型编码不能为空");
        }
        // 编码不存在时返回空列表而不是 404：
        // 下拉框拿到空集合直接渲染为空选项即可，抛错反而会打断页面其它内容的加载
        List<DictItemVO> items = dictMapper.selectEnabledItemsByType(type).stream()
                .map(DictItemVO::of)
                .toList();
        if (items.isEmpty()) {
            log.info("按类型编码查询可用字典项结果为空，dictType={}", type);
        }
        return items;
    }

    @Override
    public DictItemVO createItem(DictItemRequest request, String operator) {
        Objects.requireNonNull(request, "request 不能为空");
        String dictType = normalize(request.dictType());
        if (dictType == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "所属字典类型不能为空");
        }
        if (dictMapper.selectDictByType(dictType) == null) {
            log.warn("新增字典项被拒绝，字典类型不存在：dictType={}", dictType);
            throw new BusinessException(ResultCode.NOT_FOUND, "字典类型不存在：" + dictType);
        }
        String value = normalize(request.value());
        requireValueUnique(dictType, value, null);

        DictItem item = new DictItem();
        item.setId(newId());
        item.setDictType(dictType);
        item.setLabel(normalize(request.label()));
        item.setValue(value);
        item.setSortNum(request.sortNum() == null ? 0 : request.sortNum());
        item.setStatus(request.status() == null ? 1 : request.status());
        item.setListClass(normalize(request.listClass()));
        item.setIsDefault(request.isDefault() == null ? 0 : request.isDefault());
        item.setRemark(normalize(request.remark()));
        item.setCreateBy(operator);

        int affected = dictMapper.insertItem(item);
        if (affected < 1) {
            log.warn("新增字典项失败，dictType={}, value={}, affected={}", dictType, value, affected);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "新增字典项失败");
        }
        log.info("新增字典项成功，id={}, dictType={}, value={}, operator={}",
                item.getId(), dictType, value, operator);
        return DictItemVO.of(dictMapper.selectItemById(item.getId()));
    }

    @Override
    public DictItemVO updateItem(String id, DictItemUpdateRequest request, String operator) {
        Objects.requireNonNull(request, "request 不能为空");
        DictItem item = requireItem(id);

        String value = normalize(request.value());
        // 排除自身，否则"只改排序不动值"会被误判成重复
        requireValueUnique(item.getDictType(), value, id);

        DictItem patch = new DictItem();
        patch.setId(id);
        patch.setLabel(normalize(request.label()));
        patch.setValue(value);
        patch.setSortNum(request.sortNum() == null ? item.getSortNum() : request.sortNum());
        patch.setStatus(request.status() == null ? item.getStatus() : request.status());
        patch.setListClass(normalize(request.listClass()));
        patch.setIsDefault(request.isDefault() == null ? item.getIsDefault() : request.isDefault());
        patch.setRemark(normalize(request.remark()));
        patch.setUpdateBy(operator);

        int affected = dictMapper.updateItem(patch);
        if (affected < 1) {
            log.warn("更新字典项失败，id={}, affected={}", id, affected);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新字典项失败");
        }
        log.info("更新字典项成功，id={}, operator={}", id, operator);
        return DictItemVO.of(dictMapper.selectItemById(id));
    }

    @Override
    public void deleteItem(String id, String operator) {
        requireItem(id);
        int affected = dictMapper.logicDeleteItem(id, operator);
        if (affected < 1) {
            log.warn("删除字典项失败，id={}, affected={}", id, affected);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "删除字典项失败");
        }
        log.info("删除字典项成功，id={}, operator={}", id, operator);
    }

    /* ---------- 内部辅助 ---------- */

    private Dict requireDict(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "字典类型ID不能为空");
        }
        Dict dict = dictMapper.selectDictById(id);
        if (dict == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "字典类型不存在");
        }
        return dict;
    }

    private DictItem requireItem(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "字典项ID不能为空");
        }
        DictItem item = dictMapper.selectItemById(id);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "字典项不存在");
        }
        return item;
    }

    /**
     * 同一字典类型下的存储值不允许重复。
     *
     * <p>值重复意味着按值反查显示文本时只能取到其中一条——下拉框看起来正常，
     * 但列表页会随机显示不同的文字，属于"能跑但结果不确定"的问题，必须在写入时挡掉。</p>
     */
    private void requireValueUnique(String dictType, String value, String excludeId) {
        if (value == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "存储值不能为空");
        }
        if (dictMapper.countItemValue(dictType, value, excludeId) > 0) {
            log.warn("字典项存储值重复，dictType={}, value={}, excludeId={}", dictType, value, excludeId);
            throw new BusinessException(ResultCode.CONFLICT, "该字典类型下已存在相同的存储值：" + value);
        }
    }

    /** 32 位无横线随机串，与 t_user / t_menu / t_attachment 的主键风格一致 */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 去空白；空串统一转成 null，避免"空格"被当成一个有效值存进去 */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    private static int normalizePageSize(int pageSize) {
        if (pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 深分页保护：LIMIT 的大 OFFSET 会扫描并丢弃大量行，直接拒绝超限请求。 */
    private static int requireOffset(int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页偏移量过大，请缩小页码");
        }
        return (int) offset;
    }
}
