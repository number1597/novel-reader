package com.novelreader;

import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.NovelRule;
import com.novelreader.parser.ChapterReader;
import com.novelreader.parser.RuleLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 目录解析的多规则兜底：站点改版后能自动换下一条候选规则。
 *
 * <p>真实场景就是本项目遇到的：站点改版后，旧规则依然能匹配上域名（所以
 * {@code match} 会把它选出来），但选择器已经失效、解析结果为空。
 * 过去这时直接报错，用户得自己去改规则；现在会接着试后面的候选规则。
 *
 * <p>用内嵌 {@code HttpServer} 提供固定 HTML，避免依赖真实站点。
 */
public class ChapterLoaderFallbackTest {

    private static final String TOC_HTML =
            "<html><body><div id=\"list\"><dl>"
                    + "<dt>《测试书》正文</dt>"
                    + "<dd><a href=\"/c1.html\">第1章 开始</a></dd>"
                    + "<dd><a href=\"/c2.html\">第2章 继续</a></dd>"
                    + "</dl></div></body></html>";

    private HttpServer server;
    private String base;
    private final AtomicInteger hits = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/toc", (HttpExchange exchange) -> {
            hits.incrementAndGet();
            byte[] body = TOC_HTML.getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 造一条只配目录选择器的规则。 */
    private static NovelRule rule(String name, String container, String link) {
        NovelRule rule = new NovelRule();
        rule.ruleName = name;
        rule.toc = new NovelRule.TocConfig();
        rule.toc.containerSelector = container;
        rule.toc.linkSelector = link;
        return rule;
    }

    /** 旧规则：选择器已失效（页面上没有 table）。 */
    private static NovelRule brokenRule(String name) {
        return rule(name, "#list dl", "table a");
    }

    /** 新规则：选择器正确。 */
    private static NovelRule goodRule(String name) {
        return rule(name, "#list dl", "dd a");
    }

    private ChapterReader reader() {
        return new ChapterReader(new HtmlFetcher(3000));
    }

    @Test
    public void fallsBackToTheNextRuleWhenTheFirstParsesNothing() throws Exception {
        ChapterReader.TocResult result = reader().loadTocWithFallback(
                base + "/toc", Arrays.asList(brokenRule("改版前的旧规则"), goodRule("新规则")));

        assertEquals("应换用能解析出章节的那条规则", "新规则", result.getRule().getRuleName());
        assertEquals(2, result.getChapters().size());
        assertEquals("第1章 开始", result.getChapters().get(0).getTitle());
        assertEquals("两条规则各请求一次", 2, hits.get());
    }

    @Test
    public void returnsTheFirstWorkingRuleWithoutTryingTheRest() throws Exception {
        ChapterReader.TocResult result = reader().loadTocWithFallback(
                base + "/toc", Arrays.asList(goodRule("首选规则"), goodRule("备选规则")));

        assertEquals("第一条就能用时不该继续试后面的", "首选规则", result.getRule().getRuleName());
        assertEquals("只应请求一次", 1, hits.get());
    }

    @Test
    public void throwsLastErrorWhenNoCandidateWorks() {
        try {
            reader().loadTocWithFallback(base + "/toc",
                    Arrays.asList(brokenRule("旧规则1"), brokenRule("旧规则2")));
            fail("全都解析不出来时应当抛错");
        } catch (IOException e) {
            assertNotNull("错误信息不该为空", e.getMessage());
            assertTrue("错误信息应能提示选择器问题，实际：" + e.getMessage(),
                    e.getMessage().contains("linkSelector") || e.getMessage().contains("解析"));
        }
        assertEquals("每条候选都应被尝试过一次", 2, hits.get());
    }

    @Test
    public void triesAtMostThreeCandidates() {
        List<NovelRule> candidates = Arrays.asList(
                brokenRule("坏1"), brokenRule("坏2"), brokenRule("坏3"), goodRule("第四条本来能用"));

        try {
            reader().loadTocWithFallback(base + "/toc", candidates);
            fail("候选上限是 3，第 4 条不该被尝试");
        } catch (IOException expected) {
            // 期望如此：上限之外的候选不尝试
        }
        assertEquals("只应尝试前 " + RuleLoader.MAX_FALLBACK_CANDIDATES + " 条",
                RuleLoader.MAX_FALLBACK_CANDIDATES, hits.get());
    }

    @Test
    public void rejectsEmptyCandidateListWithoutAnyRequest() {
        try {
            reader().loadTocWithFallback(base + "/toc", new ArrayList<>());
            fail("没有候选规则时应当直接报错");
        } catch (IOException e) {
            assertTrue("错误信息应说明没有可用规则，实际：" + e.getMessage(),
                    e.getMessage().contains("没有可用的规则"));
        }
        assertEquals("没有候选就不该发请求", 0, hits.get());
    }

    @Test
    public void rejectsNullCandidateList() {
        try {
            reader().loadTocWithFallback(base + "/toc", null);
            fail("候选为 null 时应当报错而不是 NPE");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void skipsNullCandidatesAndStillFindsAWorkingOne() throws Exception {
        ChapterReader.TocResult result = reader().loadTocWithFallback(
                base + "/toc", Arrays.asList(null, brokenRule("旧规则"), goodRule("能用")));

        assertEquals("null 候选应被跳过", "能用", result.getRule().getRuleName());
        assertEquals(2, result.getChapters().size());
    }
}
