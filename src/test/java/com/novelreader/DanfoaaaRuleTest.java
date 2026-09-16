package com.novelreader;

import com.google.gson.JsonParser;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.parser.ContentParser;
import com.novelreader.parser.RuleLoader;
import com.novelreader.parser.TocParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 用本地 HTML 验证品书网 danfoaaa.com 规则能跑通：
 * 1. 用户规则文件能加载、能匹配 danfoaaa.com；
 * 2. 用 #list dl + dd a 解析目录，能拿到 927 章；
 * 3. 用 .bookname h1 + #content 抽取正文，第 1 章首段是「大周，兴业十八年，三山村」。
 *
 * 不依赖网络、HttpFetcher、PersistentStateComponent；只读本地资源，
 * 因此可以裸 JVM 跑、可入测试套件、可在 CI 跑。
 */
public class DanfoaaaRuleTest {

    private static final Path USER_RULES = Path.of(
            System.getProperty("user.home"),
            "AppData", "Roaming", "JetBrains",
            "IntelliJIdea2026.1", "novelReader", "rules.json");

    private static Document read(String name) throws Exception {
        try (InputStream in = DanfoaaaRuleTest.class.getResourceAsStream("/danfoaaa/" + name)) {
            assertNotNull("missing test resource danfoaaa/" + name, in);
            byte[] bytes;
            try (var bis = new java.io.BufferedInputStream(in)) {
                bytes = bis.readAllBytes();
            }
            return Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void userRulesLoadAndMatchDanfoaaa() throws Exception {
        List<NovelRule> rules = RuleLoader.load(USER_RULES).getRules();
        NovelRule matched = RuleLoader.match(rules, "https://www.danfoaaa.com/shu/509705/");
        assertNotNull("应该匹配到品书网规则", matched);
        assertEquals("品书网 danfoaaa.com", matched.getRuleName());
        assertEquals("UTF-8", matched.getEncoding());
    }

    @Test
    public void tocParserExtractsAllChaptersFromLocalHtml() throws Exception {
        Document doc = read("toc.html");
        NovelRule rule = RuleLoader.match(
                RuleLoader.load(USER_RULES).getRules(),
                "https://www.danfoaaa.com/shu/509705/");
        List<Chapter> chapters = new TocParser().parse(doc, rule);
        assertEquals("927 章全部应被解析", 927, chapters.size());
        assertEquals("第1章 每日一卦，未卜先知？！", chapters.get(0).getTitle());
        assertEquals("https://www.danfoaaa.com/shu/509705/23443246.html", chapters.get(0).getUrl());
        assertEquals("第858章 回乡", chapters.get(927 - 1).getTitle());
    }

    @Test
    public void contentParserExtractsTitleAndBodyFromLocalHtml() throws Exception {
        Document doc = read("chapter1.html");
        NovelRule rule = RuleLoader.match(
                RuleLoader.load(USER_RULES).getRules(),
                "https://www.danfoaaa.com/shu/509705/23443246.html");
        ContentParser.ParsedContent parsed = new ContentParser().parse(doc, rule);
        assertEquals("第1章 每日一卦，未卜先知？！", parsed.getTitle());
        String body = parsed.getBody().replaceAll("\\s+", "");
        assertTrue("正文首段应与原文一致；实际首 60 字：" +
                        body.substring(0, Math.min(60, body.length())),
                body.startsWith("大周，兴业十八年，三山村"));
        // 正文不应带导航条（章节目录/上一章/下一章）
        assertTrue("正文不应包含导航文字：「章节目录/上一章/下一章」",
                !body.contains("章节目录") && !body.contains("上一章") && !body.contains("下一章"));
        // 下一页应为空（一章一页，没有下一页）
        assertNull(parsed.getNextPageUrl());
    }

    @Test
    public void bundledDefaultRulesStillLoadAndMatchTianLai() throws Exception {
        // 顺手验证：追加了 danfoaaa 后，天籁规则没有被改坏
        List<NovelRule> rules = RuleLoader.load(USER_RULES).getRules();
        NovelRule tianLai = RuleLoader.match(rules, "https://www.tlxsbook.com/255_255330/");
        assertNotNull("追加新规则后，天籁仍应可匹配", tianLai);
        assertEquals("天籁小说网 tlxsbook.com", tianLai.getRuleName());
        assertEquals("GBK", tianLai.getEncoding());
    }

    @Test
    public void danfoaaaRuleIsValidJson() throws Exception {
        // 用独立于 RuleLoader 的 JSON 解析器再校验一遍
        String raw = Files.readString(USER_RULES, StandardCharsets.UTF_8);
        String sanitized = RuleLoader.sanitize(raw);
        var node = JsonParser.parseString(sanitized);
        var arr = node.getAsJsonObject().getAsJsonArray("rules");
        boolean found = false;
        for (var el : arr) {
            var o = el.getAsJsonObject();
            if ("品书网 danfoaaa.com".equals(o.get("ruleName").getAsString())) {
                found = true;
                assertTrue("toc 字段应存在", o.has("toc"));
                assertTrue("content 字段应存在", o.has("content"));
                assertTrue("paging 字段应存在", o.has("paging"));
                assertEquals("UTF-8", o.get("encoding").getAsString());
            }
        }
        assertTrue("sanitize 后应仍能找到「品书网 danfoaaa.com」条目", found);
    }
}