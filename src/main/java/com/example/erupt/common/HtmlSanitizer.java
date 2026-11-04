package com.example.erupt.common;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 富文本（HTML）净化工具：按白名单重建 DOM，只保留安全的标签与属性。
 *
 * <p><b>为什么必须在服务端做</b>：知识的正文由运营人员在 UEditor 里录入，
 * 原样存库、原样回显。只要不做过滤，任何一个能调新增/更新接口的人都能写入
 * {@code <script>} 或 {@code <img onerror=...>}，而它在所有查看这条知识的人
 * 浏览器里执行——存储型 XSS，影响面是全部使用者，不是录入者自己。</p>
 *
 * <p><b>为什么用 jsoup 而不是正则</b>：正则做 HTML 净化几乎必然漏。
 * 属性可以用单引号/无引号、标签名可以大小写混写、{@code javascript:} 可以写成
 * {@code java\tscript:} 或用字符实体编码，{@code onerror} 之类的事件属性无穷无尽。
 * jsoup 的做法是「解析成 DOM → 只重建白名单里的节点 → 其余全部丢弃」，
 * 白名单之外的写法根本没有机会被原样输出。</p>
 *
 * <p><b>为什么保留 {@code style}</b>：现有知识正文里有相当一部分依赖内联样式排版
 * （实测 13 条中 6 条含 {@code style=}）。一刀切去掉会让这些内容显示错乱。
 * 代价是 CSS 本身也是一种注入面，因此额外清洗 {@code url()} / {@code expression()}
 * 这两种能把外部资源或脚本表达式带进 CSS 的写法。</p>
 *
 * <p><b>不使用这个类的场景</b>：纯文本字段（标题、类型、标签）不需要净化，
 * 它们本来就该按文本处理，前端输出时转义即可。</p>
 */
public final class HtmlSanitizer {

    /**
     * {@code style} 属性中需要整段移除的危险片段。
     *
     * <p>{@code url(} 可以引用任意外部资源（包括 {@code javascript:} 伪协议，
     * 老版本 IE 会执行）；{@code expression(} 是 IE 的 CSS 脚本表达式；
     * {@code behavior:} / {@code -moz-binding} 可加载外部脚本。</p>
     */
    private static final Pattern UNSAFE_CSS =
            Pattern.compile("url\\s*\\(|expression\\s*\\(|behavior\\s*:|-moz-binding",
                    Pattern.CASE_INSENSITIVE);

    /** 允许的协议。相对地址（以 / 或 # 开头）单独放行，见 {@link #cleanUrl}。 */
    private static final Pattern SAFE_PROTOCOL =
            Pattern.compile("^(https?://|mailto:|tel:)", Pattern.CASE_INSENSITIVE);

    private static final Safelist SAFELIST = buildSafelist();

    private HtmlSanitizer() {
    }

    /**
     * 净化 HTML 片段。
     *
     * @param html 原始 HTML，可为 null
     * @return 净化后的 HTML；入参为 null 或空白时原样返回（null 仍返回 null，
     *         交由调用方决定「空正文」是否符合业务规则）
     */
    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        // Safelist.relaxed() 已包含结构化标签与 table，再补上编辑器常用的几个；
        // 注意：Safelist 的 addAttributes 只增加「允许」，不会放宽协议校验，
        // 真正的协议过滤在 cleanUrl 里对每个 href/src 逐个执行。
        Document dirty = Jsoup.parseBodyFragment(html);
        // baseUri 传空串：不解析相对地址，避免依赖运行时环境
        String cleaned = Jsoup.clean(dirty.body().html(), "", SAFELIST);

        // 第二遍：逐个清洗 URL 与 style —— Safelist 无法按属性值做校验
        Document doc = Jsoup.parseBodyFragment(cleaned);
        doc.outputSettings().prettyPrint(false);
        for (Element element : doc.body().getAllElements()) {
            cleanUrlAttribute(element, "href");
            cleanUrlAttribute(element, "src");
            cleanStyleAttribute(element);
        }
        return doc.body().html();
    }

    /**
     * 校验并清洗 URL 类属性：协议不在白名单内的一律移除该属性。
     *
     * <p>移除属性而不是整段删掉元素：链接无效时文字仍有意义，
     * 图片地址非法时留着一个空 {@code <img>} 也不会有副作用。</p>
     */
    private static void cleanUrlAttribute(Element element, String attribute) {
        if (!element.hasAttr(attribute)) {
            return;
        }
        String value = element.attr(attribute).trim();
        if (!isSafeUrl(value)) {
            element.removeAttr(attribute);
            return;
        }
        element.attr(attribute, value);
    }

    /**
     * URL 是否安全：空值、相对路径、锚点、白名单协议放行。
     *
     * <p>先把控制字符与空白去掉再判断，防止 {@code java\tscript:alert(1)}
     * 这类用不可见字符拆开协议名的写法绕过前缀匹配。</p>
     */
    static boolean isSafeUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String normalized = url.replaceAll("[\\u0000-\\u0020\\u007f]", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
            return false;
        }
        if (normalized.startsWith("/") || normalized.startsWith("#")) {
            return true;
        }
        return SAFE_PROTOCOL.matcher(normalized).find();
    }

    /** {@code style} 中出现危险 CSS 片段时整段移除该属性（逐条裁剪的成本高于收益）。 */
    private static void cleanStyleAttribute(Element element) {
        if (!element.hasAttr("style")) {
            return;
        }
        String style = element.attr("style");
        if (UNSAFE_CSS.matcher(style).find()) {
            element.removeAttr("style");
        }
    }

    private static Safelist buildSafelist() {
        return Safelist.relaxed()
                // relaxed() 默认允许的协议对现代浏览器仍安全；这里再放开 tel/mailto 供联系方式使用
                .addProtocols("a", "href", "http", "https", "mailto", "tel")
                .addProtocols("img", "src", "http", "https")
                // 编辑器产出但 relaxed() 未包含、或需要额外属性才能正常显示的标签
                .addTags("hr", "s", "strike", "sub", "sup", "del", "ins", "figure", "figcaption")
                .addAttributes(":all", "style", "class")
                .addAttributes("img", "width", "height", "alt", "title")
                .addAttributes("a", "target", "rel")
                .addAttributes("td", "colspan", "rowspan", "width")
                .addAttributes("th", "colspan", "rowspan", "width");
    }
}
