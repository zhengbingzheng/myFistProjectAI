package com.example.erupt.cache;

import com.example.erupt.common.PageResult;
import com.example.erupt.model.vo.NavigationVO;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.function.Supplier;

/**
 * 导航模块的缓存门面：把该模块的 key 构造、过期策略集中到一处，业务层不需要感知 Redis。
 *
 * <p>为什么单独拆这一层，而不是让 ServiceImpl 直接依赖 {@link RedisCacheService}：
 * <ul>
 *   <li>ServiceImpl 里夹杂一堆 TypeReference / TTL 选择的细节会很混乱</li>
 *   <li>同一模块的过期策略应当收敛在一处，避免同一个业务的 key 在不同方法里 TTL 不一致</li>
 *   <li>给测试提供了单一的注入点：mock 掉本类即可让业务层在无 Redis 环境下跑通</li>
 * </ul>
 *
 * <p>本类只做"加速"，不参与任何业务判断，也不会抛出任何与 Redis 相关的异常
 * —— 那些已经在 {@link RedisCacheService} 里被降级处理掉了。</p>
 */
@Service
public class NavigationCache {

    private final RedisCacheService cache;
    private final CacheKeys keys;
    private final CacheProperties properties;

    public NavigationCache(RedisCacheService cache, CacheKeys keys, CacheProperties properties) {
        this.cache = cache;
        this.keys = keys;
        this.properties = properties;
    }

    /**
     * 单条导航详情。读多写少，TTL 取较长的一档。
     *
     * @param loader 回源逻辑。注意它可能抛 {@code BusinessException(导航不存在)}，
     *               该异常会向上抛出而不被缓存 —— 我们不希望"查不到"这个结果被记住
     */
    public NavigationVO detail(Long id, Supplier<NavigationVO> loader) {
        return cache.getOrLoad(keys.navigationDetail(id),
                new TypeReference<NavigationVO>() {
                },
                properties.navigation().detailTtlOr(),
                loader);
    }

    /**
     * 分页列表。
     *
     * <p>查询条件的组合非常多，缓存命中率天然低于详情，因此 TTL 较短。
     * 这里传进来的条件必须是<b>已经规范化之后</b>的值（trim / SQL 转义后），
     * 否则同一个语义的查询会因为多余空格被当成两个不同的 key。</p>
     */
    public PageResult<NavigationVO> page(String keyword, String cat, String tag,
                                         int page, int pageSize,
                                         Supplier<PageResult<NavigationVO>> loader) {
        return cache.getOrLoad(keys.navigationList(keyword, cat, tag, page, pageSize),
                new TypeReference<PageResult<NavigationVO>>() {
                },
                properties.navigation().listTtlOr(),
                loader);
    }

    /** 去重分类列表。几乎不变且每个列表页都要用，TTL 最长 */
    public List<String> cats(Supplier<List<String>> loader) {
        return cache.getOrLoad(keys.navigationCats(),
                new TypeReference<List<String>>() {
                },
                properties.navigation().catTtlOr(),
                loader);
    }

    /** 全量分类列表（含重复） */
    public List<String> allCats(Supplier<List<String>> loader) {
        return cache.getOrLoad(keys.navigationAllCats(),
                new TypeReference<List<String>>() {
                },
                properties.navigation().catTtlOr(),
                loader);
    }

    /**
     * 失效本模块的全部缓存。
     *
     * <p>整段失效比逐个删除单个 key 更稳妥：导航的写操作（尤其是批量删除、改分类）
     * 往往同时波及"详情 / 列表 / 分类清单"多处缓存，逐个推算 key 很容易漏。
     * 导航数据量小、重建成本低，整段前缀作废的代价可以接受。</p>
     */
    public void evictAll() {
        cache.deleteByPrefix(keys.navigationPrefix());
    }
}
