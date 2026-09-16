package com.novelreader;

import com.novelreader.model.NovelRule;
import com.novelreader.util.UrlUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * URL 工具：相对链接补全、域名提取、规则匹配。
 *
 * <p>之前这个类（109 行纯逻辑）一直没有测试，但它是<b>所有解析的入口</b>：
 * 补链接错了会导致抓不到章节，域名匹配错了会导致规则选不上。
 * 这些错误的现象都离原因很远，所以补上。
 */
public class UrlUtilTest {

    // ---------- isAbsolute ----------

    @Test
    public void isAbsoluteAcceptsHttpAndHttps() {
        assertTrue(UrlUtil.isAbsolute("http://a.com/1"));
        assertTrue(UrlUtil.isAbsolute("https://a.com/1"));
        assertTrue("大小写不敏感", UrlUtil.isAbsolute("HTTPS://A.COM/1"));
        assertTrue("前后空格应被容忍", UrlUtil.isAbsolute("  https://a.com/1  "));
    }

    @Test
    public void isAbsoluteRejectsRelativeAndNull() {
        assertFalse(UrlUtil.isAbsolute(null));
        assertFalse(UrlUtil.isAbsolute(""));
        assertFalse(UrlUtil.isAbsolute("/path/page.html"));
        assertFalse(UrlUtil.isAbsolute("//a.com/page"));
        assertFalse(UrlUtil.isAbsolute("ftp://a.com/x"));
    }

    // ---------- toAbsolute ----------

    @Test
    public void absoluteUrlIsReturnedAsIs() {
        assertEquals("https://a.com/1.html", UrlUtil.toAbsolute("https://a.com/1.html", "https://b.com/"));
    }

    @Test
    public void protocolRelativeUrlGetsHttps() {
        assertEquals("https://a.com/1.html", UrlUtil.toAbsolute("//a.com/1.html", "https://b.com/"));
    }

    @Test
    public void relativeUrlIsResolvedAgainstBase() {
        assertEquals("同目录下的相对链接",
                "https://a.com/shu/12.html",
                UrlUtil.toAbsolute("12.html", "https://a.com/shu/1.html"));
        assertEquals("相对路径是相对于基准的目录，而不是域名根",
                "https://a.com/shu/next/1.html",
                UrlUtil.toAbsolute("next/1.html", "https://a.com/shu/1.html"));
        assertEquals("根相对路径才回到域名根",
                "https://a.com/12.html",
                UrlUtil.toAbsolute("/12.html", "https://a.com/shu/1.html"));
    }

    @Test
    public void toAbsoluteReturnsNullForNullOrBlank() {
        assertNull(UrlUtil.toAbsolute(null, "https://a.com/"));
        assertNull(UrlUtil.toAbsolute("", "https://a.com/"));
        assertNull(UrlUtil.toAbsolute("   ", "https://a.com/"));
    }

    @Test
    public void withoutBaseTheRelativeUrlIsReturnedTrimmed() {
        assertEquals("12.html", UrlUtil.toAbsolute("  12.html  ", null));
        assertEquals("12.html", UrlUtil.toAbsolute("12.html", ""));
        assertEquals("12.html", UrlUtil.toAbsolute("12.html", "   "));
    }

    @Test
    public void unparsableCombinationFallsBackToTheOriginalValue() {
        // base 本身不是合法 URL 时，不应该抛异常，原样返回让调用方自己判断
        assertEquals("12.html", UrlUtil.toAbsolute("12.html", "不是一个 URL"));
    }

    // ---------- host ----------

    @Test
    public void hostIsLowercased() {
        assertEquals("www.example.com", UrlUtil.host("https://WWW.Example.COM/shu/1/"));
    }

    @Test
    public void hostReturnsEmptyForBadInput() {
        assertEquals("", UrlUtil.host(null));
        assertEquals("", UrlUtil.host(""));
        assertEquals("", UrlUtil.host("   "));
        assertEquals("", UrlUtil.host("不是一个 URL"));
    }

    // ---------- matches ----------

    private static NovelRule hostRule(String pattern) {
        NovelRule rule = new NovelRule();
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "host";
        rule.siteMatch.pattern = pattern;
        return rule;
    }

    @Test
    public void hostTypeMatchesExactHost() {
        assertTrue(UrlUtil.matches(hostRule("example.com"), "https://example.com/shu/1/"));
    }

    @Test
    public void hostTypeMatchesSubdomains() {
        assertTrue(UrlUtil.matches(hostRule("example.com"), "https://www.example.com/shu/1/"));
        assertTrue(UrlUtil.matches(hostRule("example.com"), "https://m.example.com/shu/1/"));
    }

    @Test
    public void hostTypeDoesNotMatchLookalikeDomains() {
        // notexample.com 的域名后缀不是 .example.com，不能命中
        assertFalse(UrlUtil.matches(hostRule("example.com"), "https://notexample.com/shu/1/"));
        assertFalse("不同域名不该命中", UrlUtil.matches(hostRule("example.com"), "https://other.com/"));
    }

    @Test
    public void hostTypeToleratesPatternWrittenAsFullUrl() {
        // 用户常把 pattern 误写成完整 URL，这里会自动取出域名部分
        assertTrue(UrlUtil.matches(hostRule("https://example.com/"), "https://m.example.com/shu/1/"));
    }

    @Test
    public void defaultTypeIsHostWhenTypeIsMissing() {
        NovelRule rule = new NovelRule();
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "";
        rule.siteMatch.pattern = "example.com";

        assertTrue("type 缺失时按 host 处理", UrlUtil.matches(rule, "https://example.com/"));
    }

    @Test
    public void urlContainsTypeMatchesSubstring() {
        NovelRule rule = new NovelRule();
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "url_contains";
        rule.siteMatch.pattern = "/shu/";

        assertTrue(UrlUtil.matches(rule, "https://example.com/shu/1/"));
        assertFalse(UrlUtil.matches(rule, "https://example.com/book/1/"));
    }

    @Test
    public void regexTypeUsesFindSemantics() {
        NovelRule rule = new NovelRule();
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "regex";
        rule.siteMatch.pattern = "example\\.com/\\d+/";

        assertTrue("正则是 find 而不是全匹配", UrlUtil.matches(rule, "https://example.com/123/x"));
        assertFalse(UrlUtil.matches(rule, "https://example.com/abc/x"));
    }

    @Test
    public void invalidRegexDoesNotThrowAndDoesNotMatch() {
        NovelRule rule = new NovelRule();
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "regex";
        rule.siteMatch.pattern = "([unclosed";

        assertFalse("非法正则应当返回 false 而不是抛异常", UrlUtil.matches(rule, "https://example.com/"));
    }

    @Test
    public void typeIsCaseInsensitive() {
        NovelRule rule = new NovelRule();
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "URL_CONTAINS";
        rule.siteMatch.pattern = "/shu/";

        assertTrue(UrlUtil.matches(rule, "https://example.com/shu/1/"));
    }

    @Test
    public void missingSiteMatchFallsBackToExampleUrlHost() {
        // 用户只写了 exampleUrl 时，仍然希望规则能被命中
        NovelRule rule = new NovelRule();
        rule.exampleUrl = "https://example.com/shu/1/";

        assertTrue(UrlUtil.matches(rule, "https://www.example.com/shu/2/"));
        assertFalse(UrlUtil.matches(rule, "https://other.com/"));
    }

    @Test
    public void missingBothSiteMatchAndExampleUrlMatchesNothing() {
        assertFalse(UrlUtil.matches(new NovelRule(), "https://example.com/"));
    }

    @Test
    public void matchesRejectsNullAndBlankInput() {
        assertFalse(UrlUtil.matches(null, "https://example.com/"));
        assertFalse(UrlUtil.matches(hostRule("example.com"), null));
        assertFalse(UrlUtil.matches(hostRule("example.com"), ""));
        assertFalse(UrlUtil.matches(hostRule("example.com"), "   "));
        assertFalse("pattern 为空且没有 exampleUrl 时不命中",
                UrlUtil.matches(hostRule("   "), "https://example.com/"));
    }
}
