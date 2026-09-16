package com.novelreader;

import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.parser.ChapterReader;
import com.novelreader.parser.RuleLoader;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真实站点 e2e：用项目自带规则 + HtmlFetcher 真打
 * {@code https://www.danfoaaa.com/shu/509705/}，断言完整链路成功。
 *
 * <p>默认 <b>skip</b>，避免 CI / 离线环境被外部网络拒绝。只有显式传
 * {@code -DrunRealE2E=true} 才会真的发请求：
 * <pre>
 * gradlew test --tests com.novelreader.RealDanfoaaaSiteTest -DrunRealE2E=true
 * </pre>
 *
 * <p><b>根因记录（2026-09-11 实测）</b>：本站 WAF 的策略是「拉黑浏览器 UA」——
 * 凡是 {@code Mozilla/...Chrome/...} 这类浏览器 UA 一律 301 到 {@code /404.html}
 * （该端点还会自跳形成死循环）；反而是非浏览器 UA（{@code Java/21}、
 * {@code novel-reader/1.0}、curl）全部 200 放行。因此规则里
 * {@code userAgent} 必须填非浏览器值，不能留空（留空会退回内置 Chrome UA 被拦）。
 *
 * <p>历史包袱：最初以为是「重定向太多需要 cookie/referer」，加完 Cookie 维护 +
 * Referer + 限重定向之后仍然失败——因为根因根本不是重定向策略，是 UA 黑名单。
 */
public class RealDanfoaaaSiteTest {

    private static final String TOC_URL = "https://www.danfoaaa.com/shu/509705/";
    private static final Path RULES_PATH = Paths.get(System.getProperty("user.home"),
            "AppData/Roaming/JetBrains/IntelliJIdea2026.1/novelReader/rules.json");

    @Before
    public void onlyRunsWhenExplicitlyRequested() {
        boolean enabled = "true".equalsIgnoreCase(System.getProperty("runRealE2E", "false"));
        Assume.assumeTrue(
                "RealDanfoaaaSiteTest 默认跳过；加 -DrunRealE2E=true 才真打外网",
                enabled);
        Assume.assumeTrue("规则文件不存在：" + RULES_PATH, Files.exists(RULES_PATH));
    }

    /**
     * 完整链路：规则匹配（且 UA 是非浏览器值）→ 抓目录 → 解析出 900+ 章 →
     * 抓第 1 章正文 → 解析出标题与非空正文。
     */
    @Test
    public void fetchRealDanfoaaaTocAndFirstChapter() throws Exception {
        List<NovelRule> rules = RuleLoader.load(RULES_PATH).getRules();
        NovelRule rule = RuleLoader.match(rules, TOC_URL);
        assertNotNull("规则的 danfoaaa 条目应被匹配", rule);
        assertEquals("品书网 danfoaaa.com", rule.getRuleName());

        // UA 必须是非浏览器值：本站 WAF 拉黑 Mozilla/Chrome 系列 UA。
        String ua = rule.userAgent;
        assertNotNull("danfoaaa 规则的 userAgent 不应缺失", ua);
        assertFalse("danfoaaa 规则的 userAgent 不应留空（空会退回 Chrome UA 被 WAF 拦截）",
                ua.trim().isEmpty());
        assertFalse("danfoaaa 规则的 userAgent 不能是浏览器 UA（会被 301 到 /404.html 死循环）",
                ua.contains("Mozilla") || ua.contains("Chrome"));
        assertEquals("HtmlFetcher 应选用规则里的 UA", ua, HtmlFetcher.resolveUserAgent(rule));

        // 抓目录
        HtmlFetcher fetcher = new HtmlFetcher(15000);
        ChapterReader reader = new ChapterReader(fetcher);
        List<Chapter> chapters = reader.loadToc(TOC_URL, rule);
        assertTrue("真实目录应有 900+ 章（实测 927），实际：" + chapters.size(),
                chapters.size() >= 900);
        assertEquals("第1章 每日一卦，未卜先知？！", chapters.get(0).getTitle());

        // 抓第 1 章正文
        ChapterReader.ChapterText first = reader.read(chapters.get(0).getUrl(), rule);
        assertEquals("第1章 每日一卦，未卜先知？！", first.getTitle());
        assertTrue("第 1 章正文不应为空", !first.isEmpty());
        assertTrue("第 1 章正文应以「大周」开头，实际："
                        + first.getBody().substring(0, Math.min(30, first.getBody().length())),
                first.getBody().replaceAll("\\s+", "").startsWith("大周"));
    }
}
