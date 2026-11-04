package com.example.erupt.cache;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 缓存 key 的集中定义。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>所有 key 形如 {@code erupt-example:cache:<模块>:<维度>:<参数>}，全局前缀来自配置</li>
 *   <li>同一模块下所有 key 共用一段前缀，写操作靠这段前缀批量清除（见各 {@code *Prefix()} 方法）</li>
 *   <li>包含用户输入的动态片段（如分页查询条件）一律先做摘要，避免超长 key 和特殊字符</li>
 * </ul>
 */
@Component
public class CacheKeys {

    private final String prefix;

    public CacheKeys(CacheProperties properties) {
        this.prefix = properties.keyPrefixOr();
    }

    /* ==================== 通用 ==================== */

    /** 拼接全局前缀。业务不要自己拼字符串，统一走这里 */
    public String build(String relativeKey) {
        return prefix + relativeKey;
    }

    /* ==================== 导航模块 ==================== */

    /** 导航模块所有 key 的公共前缀：新增/修改/删除导航时按它整段清除 */
    public String navigationPrefix() {
        return build("navigation:");
    }

    /** 单条导航详情 */
    public String navigationDetail(Long id) {
        return build("navigation:detail:" + id);
    }

    /** 去重分类列表（{@code selectDistinctCats}） */
    public String navigationCats() {
        return build("navigation:cats");
    }

    /** 全量分类列表（{@code selectAllCats}） */
    public String navigationAllCats() {
        return build("navigation:all-cats");
    }

    /**
     * 分页列表。查询条件组合无限多，不能直接拼进 key，要摘要成定长字符串，
     * 否则一条超长 keyword 就能让 key 失控，且空格/引号等字符在排查时极难辨认。
     */
    public String navigationList(String keyword, String cat, String tag, int page, int pageSize) {
        String raw = String.join("|",
                String.valueOf(keyword), String.valueOf(cat), String.valueOf(tag),
                String.valueOf(page), String.valueOf(pageSize));
        return build("navigation:list:" + sha256(raw));
    }

    /* ==================== 私有工具 ==================== */

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            // 取前 16 字节（32 个十六进制字符）：足够避免碰撞，且 key 不至于太长
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明 JVM 有问题，属不可恢复错误
            throw new IllegalStateException("JDK 未提供 SHA-256 算法", e);
        }
    }
}
