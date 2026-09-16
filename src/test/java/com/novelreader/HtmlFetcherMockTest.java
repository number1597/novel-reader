package com.novelreader;

import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.parser.ChapterReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.nodes.Document;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端到端验证：起一个本地 mock HTTP 服务，返回 **GBK 编码 + gzip 压缩** 的真实响应形态，
 * 覆盖「抓取 → 解码 → 解析目录 → 解析正文 → 多页拼接」整条链路。
 *
 * <p>在线小说站点已大面积失效，这个测试是主要的回归防线。
 */
public class HtmlFetcherMockTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final String TOC_HTML = "<html><head><meta charset='gbk'></head><body>"
            + "<div id='list'><dl>"
            + "<dd><a href='/book/ch1'>第一章 起始</a></dd>"
            + "<dd><a href='/book/ch2'>第二章 终章</a></dd>"
            + "</dl></div></body></html>";

    private static final String CHAPTER_ONE_PAGE_ONE = "<html><head><meta charset='gbk'></head><body>"
            + "<h1 class='bookname'>第一章 起始</h1>"
            + "<div id='content'>"
            + "<p>第一页的正文内容，中文必须不能乱码。</p>"
            + "<div class='ad'>广告位招租，应当被剔除</div>"
            + "</div>"
            + "<a href='/book/ch1?page=2'>下一页</a></body></html>";

    private static final String CHAPTER_ONE_PAGE_TWO = "<html><head><meta charset='gbk'></head><body>"
            + "<h1 class='bookname'>第一章 起始</h1>"
            + "<div id='content'><p>第二页的正文内容，同样不能乱码。</p></div>"
            + "<a href='/book/ch2'>下一章</a></body></html>";

    private static final String CHAPTER_TWO = "<html><head><meta charset='gbk'></head><body>"
            + "<h1 class='bookname'>第二章 终章</h1>"
            + "<div id='content'><p>第二章的正文内容。</p></div></body></html>";

    private HttpServer server;
    private String base;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/toc", exchange -> respondGbkGzip(exchange, TOC_HTML));
        server.createContext("/book/ch1", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean secondPage = query != null && query.contains("page=2");
            respondGbkGzip(exchange, secondPage ? CHAPTER_ONE_PAGE_TWO : CHAPTER_ONE_PAGE_ONE);
        });
        server.createContext("/book/ch2", exchange -> respondGbkGzip(exchange, CHAPTER_TWO));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respondGbkGzip(HttpExchange exchange, String html) throws IOException {
        byte[] gzipped = gzip(html.getBytes(GBK));
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=GBK");
        exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, gzipped.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(gzipped);
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buffer)) {
            gz.write(raw);
        }
        return buffer.toByteArray();
    }

    private static NovelRule rule() {
        NovelRule rule = new NovelRule();
        rule.siteMatch.type = "url_contains";
        rule.siteMatch.pattern = "127.0.0.1";
        rule.toc.containerSelector = "#list dl";
        rule.toc.linkSelector = "dd a";
        rule.content.titleSelector = ".bookname";
        rule.content.bodySelector = "#content";
        rule.content.removeSelectors = List.of("#content .ad");
        return rule;
    }

    @Test
    public void decodesGbkGzipResponseWithoutMojibake() throws IOException {
        HtmlFetcher fetcher = new HtmlFetcher(5000);

        HtmlFetcher.Response response = fetcher.get(base + "/toc", rule());
        Document document = response.parse();

        assertEquals("响应头声明 GBK 时应嗅探为 GBK", "GBK", response.getCharset().name());
        assertTrue("中文不应乱码，实际内容：" + document.text(),
                document.text().contains("第一章 起始"));
        assertTrue(document.text().contains("第二章 终章"));
    }

    @Test
    public void loadsTocFromMockServer() throws IOException {
        ChapterReader reader = new ChapterReader(new HtmlFetcher(5000));

        List<Chapter> chapters = reader.loadToc(base + "/toc", rule());

        assertEquals(2, chapters.size());
        assertEquals("第一章 起始", chapters.get(0).getTitle());
        assertEquals(base + "/book/ch1", chapters.get(0).getUrl());
        assertEquals(base + "/book/ch2", chapters.get(1).getUrl());
    }

    @Test
    public void assemblesMultiPageChapterAndStopsAtNextChapter() throws IOException {
        ChapterReader reader = new ChapterReader(new HtmlFetcher(5000));

        ChapterReader.ChapterText text = reader.read(base + "/book/ch1", rule());

        assertEquals("应恰好抓取 2 页", 2, text.getPages());
        assertEquals("第一章 起始", text.getTitle());
        assertTrue("应包含第一页正文", text.getBody().contains("第一页的正文内容"));
        assertTrue("应包含第二页正文", text.getBody().contains("第二页的正文内容"));
        assertFalse("广告节点应被剔除", text.getBody().contains("广告位招租"));
        assertFalse("「下一章」不应被拼接进来", text.getBody().contains("第二章的正文内容"));
    }

    @Test
    public void zeroBasedRuleEncodingOverrideWins() {
        byte[] gbkBytes = "中文内容".getBytes(GBK);

        Charset sniffed = HtmlFetcher.sniffEncoding("text/html; charset=GBK", gbkBytes, "UTF-8");

        assertEquals("规则显式指定编码时优先于响应头", "UTF-8", sniffed.name());
    }

    @Test
    public void fallsBackToGbkForNonUtf8BodyWithoutHints() {
        byte[] gbkBytes = "这是一段中文，不是合法的 UTF-8 字节流。".getBytes(GBK);

        Charset sniffed = HtmlFetcher.sniffEncoding(null, gbkBytes, "auto");

        assertEquals("无任何提示且非合法 UTF-8 时按 GBK 处理", "GBK", sniffed.name());
    }

    @Test
    public void detectsMetaCharsetFromBody() {
        byte[] body = "<html><head><meta charset=\"gb2312\"></head><body>x</body></html>"
                .getBytes(GBK);

        assertEquals("gb2312", HtmlFetcher.detectMetaCharset(body));
    }

    @Test
    public void gunzipRoundTrip() throws IOException {
        byte[] raw = "压缩前的中文内容".getBytes(GBK);

        byte[] restored = HtmlFetcher.gunzip(gzip(raw));

        assertEquals("压缩前的中文内容", new String(restored, GBK));
        assertNotNull(HtmlFetcher.gunzip(new byte[0]));
    }

    @Test
    public void detectsGzipMagicBytes() throws IOException {
        assertTrue(HtmlFetcher.looksLikeGzip(gzip("x".getBytes(GBK))));
        assertFalse(HtmlFetcher.looksLikeGzip("<html>".getBytes(GBK)));
    }
}
