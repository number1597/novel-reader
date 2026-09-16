package com.novelreader;

import com.novelreader.model.NovelRule;
import com.novelreader.settings.RuleTextUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 规则编辑器里的文本 ⇄ 结构转换与校验（纯逻辑）。
 *
 * <p>这里的转换写错了，用户会看到「明明填了选择器却解析不出来」这种极难定位的现象，
 * 所以逐条钉死。重点是两条反直觉的规则：
 * <ul>
 *   <li>选择器列表<b>只按换行切</b>，不切逗号 —— CSS 分组选择器本身就含逗号；</li>
 *   <li>请求头按<b>第一个</b>冒号切 —— 值里常有冒号（URL）。</li>
 * </ul>
 */
public class RuleTextUtilsTest {

    // ---------- 选择器列表 ----------

    @Test
    public void selectorsSplitOnNewLinesOnly() {
        List<String> selectors = RuleTextUtils.splitSelectors(
                "#content .ad\n#content .bottem\n\n  #content .bottem2  ");

        assertEquals("应按换行切、trim、丢掉空行", 3, selectors.size());
        assertEquals("#content .ad", selectors.get(0));
        assertEquals("#content .bottem", selectors.get(1));
        assertEquals("#content .bottem2", selectors.get(2));
    }

    @Test
    public void commaIsNotASeparatorBecauseCssGroupedSelectorsUseIt() {
        // "#content .ad, #content .bottem" 是一条合法的分组选择器；
        // 若按逗号切，会变成两条都不完整的选择器，广告就删不掉了
        List<String> selectors = RuleTextUtils.splitSelectors("#content .ad, #content .bottem");

        assertEquals("含逗号的选择器必须当成一条", 1, selectors.size());
        assertEquals("#content .ad, #content .bottem", selectors.get(0));
    }

    @Test
    public void selectorsAreDeduplicatedButKeepOrder() {
        List<String> selectors = RuleTextUtils.splitSelectors("b\na\nb\nc\na");

        assertEquals(Arrays.asList("b", "a", "c"), selectors);
    }

    @Test
    public void selectorsHandleEmptyAndNullInput() {
        assertTrue(RuleTextUtils.splitSelectors(null).isEmpty());
        assertTrue(RuleTextUtils.splitSelectors("").isEmpty());
        assertTrue(RuleTextUtils.splitSelectors("   \n  \n").isEmpty());
    }

    @Test
    public void joinSelectorsIsOnePerLine() {
        assertEquals("a\nb", RuleTextUtils.joinSelectors(Arrays.asList("a", "b")));
        assertEquals("空项被跳过", "a\nb",
                RuleTextUtils.joinSelectors(Arrays.asList("a", "", null, "  ", "b")));
        assertEquals("", RuleTextUtils.joinSelectors(null));
        assertEquals("", RuleTextUtils.joinSelectors(List.of()));
    }

    @Test
    public void selectorsRoundTripSurvives() {
        List<String> original = Arrays.asList("#content .ad", "#content .bottem1, #content .bottem2");

        List<String> back = RuleTextUtils.splitSelectors(RuleTextUtils.joinSelectors(original));

        assertEquals("往返一次必须原样还原", original, back);
    }

    // ---------- 请求头 ----------

    @Test
    public void headersSplitOnTheFirstColonOnly() {
        // 值里的冒号（URL 的 https://）不能被当成分隔符
        Map<String, String> headers = RuleTextUtils.parseHeaders(
                "Referer: https://example.com/shu/1/\nX-Requested-With: XMLHttpRequest");

        assertEquals(2, headers.size());
        assertEquals("https://example.com/shu/1/", headers.get("Referer"));
        assertEquals("XMLHttpRequest", headers.get("X-Requested-With"));
    }

    @Test
    public void headersSkipJunkLinesAndComments() {
        Map<String, String> headers = RuleTextUtils.parseHeaders(
                "\n# 这是注释\n没有冒号的行\n: 空键\nKey: \nGood: value\n");

        assertEquals("只保留合法且有值的行", 1, headers.size());
        assertEquals("value", headers.get("Good"));
    }

    @Test
    public void headersHandleEmptyAndNullInput() {
        assertTrue(RuleTextUtils.parseHeaders(null).isEmpty());
        assertTrue(RuleTextUtils.parseHeaders("").isEmpty());
        assertTrue(RuleTextUtils.parseHeaders("  \n ").isEmpty());
    }

    @Test
    public void headersRoundTripSurvives() {
        Map<String, String> original = RuleTextUtils.parseHeaders(
                "Referer: https://example.com/\nX-Test: abc");

        Map<String, String> back = RuleTextUtils.parseHeaders(RuleTextUtils.formatHeaders(original));

        assertEquals(original, back);
    }

    @Test
    public void formatHeadersSkipsBlankKeys() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("  ", "value");
        headers.put("Real", "1");

        assertEquals("Real: 1", RuleTextUtils.formatHeaders(headers));
        assertEquals("", RuleTextUtils.formatHeaders(null));
    }

    // ---------- 校验 ----------

    private static NovelRule validRule() {
        NovelRule rule = new NovelRule();
        rule.ruleName = "示例站";
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "host";
        rule.siteMatch.pattern = "example.com";
        rule.encoding = "auto";
        rule.toc = new NovelRule.TocConfig();
        rule.toc.linkSelector = "dd a";
        rule.content = new NovelRule.ContentConfig();
        rule.content.bodySelector = "#content";
        rule.paging = new NovelRule.PagingConfig();
        rule.paging.maxPages = 20;
        return rule;
    }

    @Test
    public void aCompleteRuleHasNoProblems() {
        assertTrue("这条规则是完整的", RuleTextUtils.describeProblems(validRule()).isEmpty());
    }

    @Test
    public void missingPatternIsReported() {
        NovelRule rule = validRule();
        rule.siteMatch.pattern = "  ";

        List<String> problems = RuleTextUtils.describeProblems(rule);

        assertEquals(1, problems.size());
        assertTrue("应指出命中不了，实际：" + problems, problems.get(0).contains("siteMatch.pattern"));
    }

    @Test
    public void missingSelectorsAreReported() {
        NovelRule rule = validRule();
        rule.toc.linkSelector = "";
        rule.content.bodySelector = null;

        List<String> problems = RuleTextUtils.describeProblems(rule);

        assertEquals(2, problems.size());
        assertTrue(problems.toString().contains("linkSelector"));
        assertTrue(problems.toString().contains("bodySelector"));
    }

    @Test
    public void badMatchTypeIsReported() {
        NovelRule rule = validRule();
        rule.siteMatch.type = "wildcard";

        List<String> problems = RuleTextUtils.describeProblems(rule);

        assertEquals(1, problems.size());
        assertTrue("应指出合法的匹配方式，实际：" + problems, problems.get(0).contains("url_contains"));
    }

    @Test
    public void badCharsetIsReportedButAutoIsNot() {
        NovelRule bad = validRule();
        bad.encoding = "NOT-A-CHARSET";
        assertEquals(1, RuleTextUtils.describeProblems(bad).size());
        assertTrue(RuleTextUtils.describeProblems(bad).get(0).contains("字符集"));

        NovelRule gbk = validRule();
        gbk.encoding = "GBK";
        assertTrue("真实存在的字符集不该被报错", RuleTextUtils.describeProblems(gbk).isEmpty());
    }

    @Test
    public void nonPositiveMaxPagesIsReported() {
        NovelRule rule = validRule();
        rule.paging.maxPages = 0;

        List<String> problems = RuleTextUtils.describeProblems(rule);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("maxPages"));
    }

    @Test
    public void nullRuleAndNullListAreHandled() {
        assertEquals("null 规则也要报出来而不是抛异常", 1, RuleTextUtils.describeProblems((NovelRule) null).size());
        assertTrue(RuleTextUtils.describeProblems((List<NovelRule>) null).isEmpty());
    }

    @Test
    public void problemsAreAggregatedAcrossRules() {
        NovelRule ok = validRule();
        NovelRule broken = validRule();
        broken.toc.linkSelector = "";
        broken.ruleName = "坏规则";

        List<String> problems = RuleTextUtils.describeProblems(Arrays.asList(ok, broken));

        assertEquals(1, problems.size());
        assertTrue("问题里要带上规则名，方便定位是哪一条：" + problems, problems.get(0).contains("坏规则"));
    }

    @Test
    public void matchTypesAreTheThreeSupportedOnesAndReadOnly() {
        assertEquals(Arrays.asList("host", "url_contains", "regex"), RuleTextUtils.matchTypes());

        try {
            RuleTextUtils.matchTypes().add("wildcard");
            fail("对外暴露的匹配方式列表应当是只读的");
        } catch (UnsupportedOperationException expected) {
            // 期望如此
        }
    }
}
