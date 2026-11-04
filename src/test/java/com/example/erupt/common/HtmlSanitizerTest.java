package com.example.erupt.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 富文本净化测试。
 *
 * <p>用例按「真实攻击载荷」组织，而不是按方法分支组织：
 * 每一条都是存储型 XSS 的常见写法，净化后必须既去掉可执行内容、又保住正常排版。</p>
 */
class HtmlSanitizerTest {

    @Test
    @DisplayName("script 标签被整段移除")
    void scriptTagRemoved() {
        String cleaned = HtmlSanitizer.clean("<p>正文</p><script>alert(1)</script>");

        assertThat(cleaned).contains("<p>正文</p>");
        assertThat(cleaned).doesNotContain("script");
        assertThat(cleaned).doesNotContain("alert");
    }

    @Test
    @DisplayName("onerror 等事件属性被移除，但图片本身保留")
    void eventHandlerAttributeRemoved() {
        String cleaned = HtmlSanitizer.clean("<img src=\"https://a.com/x.png\" onerror=\"alert(1)\">");

        assertThat(cleaned).contains("https://a.com/x.png");
        assertThat(cleaned).doesNotContain("onerror");
        assertThat(cleaned).doesNotContain("alert");
    }

    @Test
    @DisplayName("onclick 等事件属性被移除")
    void onclickRemoved() {
        assertThat(HtmlSanitizer.clean("<a href=\"https://a.com\" onclick=\"alert(1)\">点我</a>"))
                .doesNotContain("onclick")
                .contains("点我");
    }

    @Test
    @DisplayName("javascript: 伪协议链接被去掉 href，文字保留")
    void javascriptHrefRemoved() {
        String cleaned = HtmlSanitizer.clean("<a href=\"javascript:alert(1)\">点我</a>");

        assertThat(cleaned).doesNotContain("javascript");
        assertThat(cleaned).contains("点我");
    }

    @Test
    @DisplayName("用制表符拆开协议名也不能绕过")
    void tabSeparatedProtocolRejected() {
        // 浏览器解析 href 时会忽略这些不可见字符，因此判断前必须同样忽略
        String cleaned = HtmlSanitizer.clean("<a href=\"java\tscript:alert(1)\">点我</a>");

        assertThat(cleaned.toLowerCase()).doesNotContain("script:");
    }

    @Test
    @DisplayName("大小写混写与前后空白不能绕过")
    void mixedCaseProtocolRejected() {
        assertThat(HtmlSanitizer.isSafeUrl("  JaVaScRiPt:alert(1)  ")).isFalse();
        assertThat(HtmlSanitizer.isSafeUrl("vbscript:msgbox(1)")).isFalse();
        assertThat(HtmlSanitizer.isSafeUrl("data:text/html;base64,PHNjcmlwdD4=")).isFalse();
    }

    @Test
    @DisplayName("style 里的 url() 整段移除")
    void cssUrlRemoved() {
        // url() 可以引用外部资源，老浏览器还能执行 javascript: 伪协议
        String cleaned = HtmlSanitizer.clean(
                "<p style=\"background:url(javascript:alert(1))\">正文</p>");

        assertThat(cleaned).doesNotContain("url(");
        assertThat(cleaned).contains("正文");
    }

    @Test
    @DisplayName("style 里的 expression() 整段移除")
    void cssExpressionRemoved() {
        String cleaned = HtmlSanitizer.clean("<p style=\"width:expression(alert(1))\">正文</p>");

        assertThat(cleaned).doesNotContain("expression");
        assertThat(cleaned).contains("正文");
    }

    @Test
    @DisplayName("正常排版与内联样式被保留")
    void legitimateFormattingPreserved() {
        // 现有知识里大量正文依赖内联样式排版，净化不能把它们一起删掉
        String html = "<p style=\"color:red;font-size:14px\"><strong>加粗</strong>"
                + "<a href=\"https://example.com\" target=\"_blank\">链接</a></p>"
                + "<table><tr><td colspan=\"2\">单元格</td></tr></table>"
                + "<img src=\"https://example.com/a.png\" width=\"100\">";

        String cleaned = HtmlSanitizer.clean(html);

        assertThat(cleaned).contains("style=\"color:red;font-size:14px\"");
        assertThat(cleaned).contains("<strong>加粗</strong>");
        assertThat(cleaned).contains("https://example.com");
        assertThat(cleaned).contains("colspan");
        assertThat(cleaned).contains("width=\"100\"");
    }

    @Test
    @DisplayName("iframe / form 等危险容器被移除")
    void dangerousContainersRemoved() {
        String cleaned = HtmlSanitizer.clean(
                "<iframe src=\"https://evil.com\"></iframe><form action=\"/x\"><input></form><p>正文</p>");

        assertThat(cleaned).doesNotContain("iframe");
        assertThat(cleaned).doesNotContain("<form");
        assertThat(cleaned).doesNotContain("<input");
        assertThat(cleaned).contains("正文");
    }

    @Test
    @DisplayName("相对路径与锚点放行")
    void relativeAndAnchorUrlsAllowed() {
        assertThat(HtmlSanitizer.isSafeUrl("/img/a.png")).isTrue();
        assertThat(HtmlSanitizer.isSafeUrl("#section")).isTrue();
        assertThat(HtmlSanitizer.isSafeUrl("https://a.com")).isTrue();
        assertThat(HtmlSanitizer.isSafeUrl("mailto:a@b.com")).isTrue();
    }

    @Test
    @DisplayName("null 与空白原样返回，交由业务层决定是否合法")
    void nullAndBlankPassThrough() {
        assertThat(HtmlSanitizer.clean(null)).isNull();
        assertThat(HtmlSanitizer.clean("")).isEmpty();
        assertThat(HtmlSanitizer.clean("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("纯文本内容原样保留，不被当成标签吞掉")
    void plainTextPreserved() {
        assertThat(HtmlSanitizer.clean("a < b 且 c > d")).contains("a &lt; b");
    }
}
