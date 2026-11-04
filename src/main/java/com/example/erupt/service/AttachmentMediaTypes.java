package com.example.erupt.service;

import java.util.Locale;
import java.util.Set;

/**
 * 附件媒体类型的安全裁决，下载与预览两条链路共用的"同一口径"。
 *
 * <p>contentType 是上传时客户端自报的，不可信：可脚本化的文本类
 * （html/json/xml/js 及其变体）在预览和下载时都必须降级为纯文本输出，
 * 浏览器才不会把响应体当文档解析执行其中的脚本。</p>
 *
 * <p>裁决清单此前分散在控制器与服务实现各一份，变体一旦遗漏（如
 * {@code application/ecmascript}、{@code application/problem+json}）两边不会同步，
 * 集中到这里之后只有一个真相源。</p>
 */
public final class AttachmentMediaTypes {

    /** 需要降级为纯文本的类型前缀，包含 {@code text/html} */
    private static final Set<String> SCRIPTABLE_TEXT_PREFIXES =
            Set.of("text/", "application/json", "application/xml",
                    "application/javascript", "application/ecmascript",
                    "application/x-javascript", "application/x-ecmascript",
                    "application/xhtml+xml");

    /** 结构化后缀变体（如 application/problem+json）同样按可脚本化处理 */
    private static final Set<String> SCRIPTABLE_SUFFIXES = Set.of("+json", "+xml");

    private AttachmentMediaTypes() {
    }

    /** 是否属于可脚本化的文本类型（命中即降级 text/plain 输出） */
    public static boolean isScriptableText(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        for (String prefix : SCRIPTABLE_TEXT_PREFIXES) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        for (String suffix : SCRIPTABLE_SUFFIXES) {
            if (type.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
