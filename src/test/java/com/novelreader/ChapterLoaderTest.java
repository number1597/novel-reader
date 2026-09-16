package com.novelreader;

import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.reader.ChapterLoader;
import com.novelreader.settings.NovelReaderSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「章节链接 → 可展示正文分段」的抓取链路。
 *
 * <p>用本地 mock HTTP 服务返回 GBK + gzip 的真实响应形态，覆盖：
 * 标题提取与回退、分页拼接、广告剔除、按字数切段、正文为空的情况。
 */
public class ChapterLoaderTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final String PAGE_ONE = "<html><head><meta charset='gbk'></head><body>"
            + "<h1 class='bookname'>第一章 起始</h1>"
            + "<div id='content'>"
            + "<p>第一页的正文内容，中文必须不能乱码。</p>"
            + "<div class='ad'>广告位招租，应当被剔除</div>"
            + "</div>"
            + "<a href='/book/ch1?page=2'>下一页</a></body></html>";

    /** 第二页没有「下一页」，只有「下一章」，用于确认不会误抓下一章。 */
    private static final String PAGE_TWO = "<html><head><meta charset='gbk'></head><body>"
            + "<h1 class='bookname'>第一章 起始</h1>"
            + "<div id='content'><p>第二页的正文内容，同样不能乱码。</p></div>"
            + "<a href='/book/ch2'>下一章</a></body></html>";

    /** 正文页里没有标题元素，用于验证回退到目录中的章节名。 */
    private static final String PAGE_WITHOUT_TITLE = "<html><head><meta charset='gbk'></head><body>"
            + "<div id='content'><p>没有标题的正文页。</p></div></body></html>";

    private static final String PAGE_EMPTY_BODY = "<html><head><meta charset='gbk'></head>"
            + "<body></body></html>";

    private HttpServer server;
    private String base;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/book/ch1", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            respondGbkGzip(exchange, query != null && query.contains("page=2") ? PAGE_TWO : PAGE_ONE);
        });
        server.createContext("/book/noTitle", exchange -> respondGbkGzip(exchange, PAGE_WITHOUT_TITLE));
        server.createContext("/book/empty", exchange -> respondGbkGzip(exchange, PAGE_EMPTY_BODY));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static NovelRule rule(String titleSelector) {
        NovelRule rule = new NovelRule();
        rule.encoding = "GBK";
        rule.content.titleSelector = titleSelector;
        rule.content.bodySelector = "#content";
        // 广告节点需要显式声明才会被剔除：ContentParser.clean 默认只清
        // script/style/noscript/iframe，其余一律按规则里的 removeSelectors 处理。
        rule.content.removeSelectors = new ArrayList<>(List.of("#content .ad"));
        rule.paging.nextTextContains = "下一页";
        return rule;
    }

    private NovelReaderSettings settings(int maxChars) {
        NovelReaderSettings settings = new NovelReaderSettings();
        settings.setMaxCharsPerPage(maxChars);
        settings.setRequestTimeoutMs(5000);
        return settings;
    }

    @Test
    public void loadsMultiPageChapterAndSplitsIntoSegments() throws Exception {
        Chapter chapter = new Chapter("第一章 起始（目录名）", base + "/book/ch1");

        ChapterLoader.Loaded loaded = ChapterLoader.load(chapter, rule("h1.bookname"), settings(1000));

        assertEquals("正文页解析出的标题优先于目录名", "第一章 起始", loaded.getTitle());
        assertEquals("应拼接两页后停止（第二页只有「下一章」）", 2, loaded.getPages());
        assertFalse(loaded.isEmpty());

        List<String> segments = loaded.getSegments();
        String all = String.join("", segments);
        assertTrue("应包含第一页正文", all.contains("第一页的正文内容"));
        assertTrue("应包含第二页正文，说明分页已拼接", all.contains("第二页的正文内容"));
        assertFalse("广告节点应被剔除", all.contains("广告位招租"));
        assertFalse("不应把下一章内容抓进来", all.contains("第二章"));
    }

    @Test
    public void fallsBackToTocTitleWhenBodyPageHasNoTitle() throws Exception {
        Chapter chapter = new Chapter("目录里的章节名", base + "/book/noTitle");

        ChapterLoader.Loaded loaded = ChapterLoader.load(chapter, rule("h1.bookname"), settings(1000));

        assertEquals("解析不到标题时应回退为目录中的章节名",
                "目录里的章节名", loaded.getTitle());
        assertTrue(String.join("", loaded.getSegments()).contains("没有标题的正文页"));
    }

    @Test
    public void respectsMaxCharsPerSegment() throws Exception {
        Chapter chapter = new Chapter("第一章 起始", base + "/book/ch1");

        ChapterLoader.Loaded loaded = ChapterLoader.load(chapter, rule("h1.bookname"), settings(12));

        assertTrue("小字数应切出多段", loaded.getSegments().size() > 1);
        for (String segment : loaded.getSegments()) {
            assertTrue("每段不应超过设定字数，实际 " + segment.length(), segment.length() <= 12);
        }
    }

    @Test
    public void emptyBodyProducesEmptyResultAndDoesNotThrow() throws Exception {
        Chapter chapter = new Chapter("空章节", base + "/book/empty");

        ChapterLoader.Loaded loaded = ChapterLoader.load(chapter, rule("h1.bookname"), settings(1000));

        assertTrue("正文为空时应标记为 empty", loaded.isEmpty());
        assertTrue(loaded.getSegments().isEmpty());
        assertEquals("正文页无标题时回退目录名", "空章节", loaded.getTitle());
    }

    /** gzip 压缩 + GBK 编码，贴近真实站点返回形态。 */
    private static void respondGbkGzip(HttpExchange exchange, String html) throws IOException {
        byte[] compressed = gzip(html.getBytes(GBK));
        exchange.getResponseHeaders().add("Content-Type", "text/html");
        exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, compressed.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(compressed);
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
            gz.write(data);
        }
        return buffer.toByteArray();
    }
}
