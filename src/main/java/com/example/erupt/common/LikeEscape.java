package com.example.erupt.common;

/**
 * LIKE 模糊查询通配符转义工具。
 *
 * <p>MySQL 的 LIKE 中 {@code %} 与 {@code _} 是通配符，若直接把用户输入拼进 LIKE，
 * 搜索 {@code %} 会退化成全表匹配（语义错误 + 全表扫描）。统一按 ESCAPE 字符转义后，
 * 再配合 SQL 中的 {@code ESCAPE '/'} 使用。</p>
 */
public final class LikeEscape {

    /** 与 Mapper XML 中 {@code ESCAPE '/'} 保持一致。 */
    public static final String ESCAPE = "/";

    private LikeEscape() {
    }

    /**
     * 转义 LIKE 中的通配符，转义符自身也需转义。
     *
     * @param value 原始查询关键字
     * @return 转义后的关键字，null 原样返回
     */
    public static String escape(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("/", "//")
                .replace("%", "/%")
                .replace("_", "/_");
    }
}
