package com.novelreader;

import com.novelreader.model.NovelRule;
import com.novelreader.parser.RuleLoader;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 多规则匹配（站点改版自愈的基础）。
 *
 * <p>{@code match} 只给第一条命中的规则，而站点改版后<b>旧规则往往还能匹配域名、
 * 但选择器已经失效</b>。所以需要 {@code matchAll} 把所有候选都拿出来，
 * 交给 {@code ChapterReader.loadTocWithFallback} 依次尝试。
 */
public class RuleLoaderMatchAllTest {

    private static NovelRule hostRule(String name, String pattern, boolean enabled) {
        NovelRule rule = new NovelRule();
        rule.ruleName = name;
        rule.enabled = enabled;
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "host";
        rule.siteMatch.pattern = pattern;
        return rule;
    }

    @Test
    public void returnsEveryMatchingRuleInFileOrder() {
        List<NovelRule> rules = Arrays.asList(
                hostRule("第一条", "example.com", true),
                hostRule("别的站", "other.com", true),
                hostRule("第二条", "example.com", true));

        List<NovelRule> matched = RuleLoader.matchAll(rules, "https://www.example.com/shu/1/");

        assertEquals("两条命中的规则都要返回，供改版后依次兜底", 2, matched.size());
        assertEquals("顺序必须与规则文件一致", "第一条", matched.get(0).getRuleName());
        assertEquals("顺序必须与规则文件一致", "第二条", matched.get(1).getRuleName());
    }

    @Test
    public void skipsDisabledRules() {
        List<NovelRule> rules = Arrays.asList(
                hostRule("停用的", "example.com", false),
                hostRule("启用的", "example.com", true));

        List<NovelRule> matched = RuleLoader.matchAll(rules, "https://example.com/");

        assertEquals("停用的规则不该进入候选", 1, matched.size());
        assertEquals("启用的", matched.get(0).getRuleName());
    }

    @Test
    public void skipsNullEntries() {
        List<NovelRule> rules = Arrays.asList(
                hostRule("正常", "example.com", true),
                null,
                hostRule("空的", "example.com", true));

        List<NovelRule> matched = RuleLoader.matchAll(rules, "https://example.com/");

        assertEquals("null 条目应被跳过而不是抛 NPE", 2, matched.size());
    }

    @Test
    public void returnsEmptyListInsteadOfNull() {
        assertTrue("rules 为 null 时返回空列表", RuleLoader.matchAll(null, "https://example.com/").isEmpty());
        assertTrue("空规则集返回空列表",
                RuleLoader.matchAll(new ArrayList<>(), "https://example.com/").isEmpty());
        assertTrue("没有命中时返回空列表",
                RuleLoader.matchAll(List.of(hostRule("别的站", "other.com", true)), "https://example.com/").isEmpty());
        assertTrue("url 为 null 时也不该抛异常",
                RuleLoader.matchAll(List.of(hostRule("x", "example.com", true)), null).isEmpty());
    }

    @Test
    public void matchStillReturnsTheFirstOneForBackwardCompatibility() {
        List<NovelRule> rules = Arrays.asList(
                hostRule("第一条", "example.com", true),
                hostRule("第二条", "example.com", true));

        NovelRule first = RuleLoader.match(rules, "https://example.com/");

        assertNotNull(first);
        assertEquals("单条 match 的语义不变：仍取第一条", "第一条", first.getRuleName());
    }

    @Test
    public void fallbackCandidateCapIsThree() {
        assertEquals("候选上限固定为 3：够兜底，又不会一次打开就打出一串请求",
                3, RuleLoader.MAX_FALLBACK_CANDIDATES);
        assertTrue("上限必须大于 1，否则兜底无意义", RuleLoader.MAX_FALLBACK_CANDIDATES > 1);
        assertFalse("上限不该大到失控", RuleLoader.MAX_FALLBACK_CANDIDATES > 10);
    }
}
