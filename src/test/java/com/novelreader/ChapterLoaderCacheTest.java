package com.novelreader;

import com.novelreader.cache.CacheKeys;
import com.novelreader.cache.CachedChapter;
import com.novelreader.cache.ChapterCache;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.reader.ChapterLoader;
import com.novelreader.settings.NovelReaderSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 「章节抓取」与离线缓存的配合。
 *
 * <p>核心契约是三条：
 * <ul>
 *   <li><b>命中缓存就不再发请求</b>——否则「离线阅读」每次都要先等超时与重试；</li>
 *   <li><b>抓取成功就写缓存</b>，且分段原样存下来（离线读到的段落必须和在线一致）；</li>
 *   <li><b>没有缓存又没有规则时</b>，报错要说清「这一章没缓存」，
 *       而不是丢一句「缺少规则」让人以为规则文件坏了。</li>
 * </ul>
 * 用本地 mock HTTP 服务 + 请求计数器来验证「到底发没发请求」。
 */
public class ChapterLoaderCacheTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final String PAGE = "<html><head><meta charset='gbk'></head><body>"
            + "<h1 class='bookname'>第一章 起始</h1>"
            + "<div id='content'><p>第一段正文内容。</p><p>第二段正文内容。</p></div>"
            + "</body></html>";

    private static final String PAGE_EMPTY = "<html><head><meta charset='gbk'></head>"
            + "<body></body></html>";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/book/ch1", exchange -> {
            requests.incrementAndGet();
            respondGbkGzip(exchange, PAGE);
        });
        server.createContext("/book/empty", exchange -> {
            requests.incrementAndGet();
            respondGbkGzip(exchange, PAGE_EMPTY);
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

    private ChapterCache cache() throws Exception {
        Constructor<ChapterCache> ctor =
                ChapterCache.class.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(folder.getRoot().toPath().resolve("cache"));
    }

    private String tocUrl() {
        return base + "/book/";
    }

    private Chapter chapter() {
        return chapterAt("/book/ch1");
    }

    private Chapter chapterAt(String path) {
        return new Chapter("第一章（目录名）", base + path);
    }

    private static NovelRule rule() {
        NovelRule rule = new NovelRule();
        rule.encoding = "GBK";
        rule.content.titleSelector = "h1.bookname";
        rule.content.bodySelector = "#content";
        return rule;
    }

    private static NovelReaderSettings settings(int maxChars) {
        NovelReaderSettings settings = new NovelReaderSettings();
        settings.setMaxCharsPerPage(maxChars);
        settings.setRequestTimeoutMs(3000);
        settings.setMaxRetries(0);   // 测试里不需要退避等待
        return settings;
    }

    // ---------- 缓存优先 ----------

    @Test
    public void firstLoadFetchesAndCaches() throws Exception {
        ChapterCache cache = cache();

        ChapterLoader.Loaded loaded = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), cache);

        assertEquals("首次应发一次请求", 1, requests.get());
        assertFalse(loaded.isEmpty());
        assertTrue("抓取成功后应写进缓存",
                cache.hasChapter(tocUrl(), chapter().getUrl()));
        CachedChapter cached = cache.read(tocUrl(), chapter().getUrl());
        assertEquals("缓存的分段应与刚读到的一致",
                loaded.getSegments(), cached.getSegments());
        assertEquals("章节标题也应缓存", loaded.getTitle(), cached.getChapterTitle());
    }

    @Test
    public void secondLoadServesFromCacheWithoutAnyRequest() throws Exception {
        ChapterCache cache = cache();
        ChapterLoader.Loaded first = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), cache);

        int afterFirst = requests.get();
        ChapterLoader.Loaded second = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), cache);

        assertEquals("第二次不该再发请求（缓存命中就该直接返回）", afterFirst, requests.get());
        assertEquals("两次读到的段落必须一致", first.getSegments(), second.getSegments());
        assertEquals("标题也应一致", first.getTitle(), second.getTitle());
    }

    @Test
    public void cacheHitSurvivesSiteGoingDown() throws Exception {
        ChapterCache cache = cache();
        ChapterLoader.load(tocUrl(), chapter(), rule(), settings(1000), cache);

        // 模拟站点挂掉：端口直接不可达
        server.stop(0);
        server = null;

        ChapterLoader.Loaded offline = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), cache);

        assertFalse("站点打不开时应当还能从缓存读出来", offline.isEmpty());
        assertTrue("缓存内容应包含正文",
                String.join("", offline.getSegments()).contains("第一段正文内容"));
    }

    @Test
    public void offlineWithCacheAndNoRuleStillReads() throws Exception {
        ChapterCache cache = cache();
        ChapterLoader.load(tocUrl(), chapter(), rule(), settings(1000), cache);

        server.stop(0);
        server = null;

        // 离线打开的书没有规则，但只要缓存命中就不该报错
        ChapterLoader.Loaded offline = ChapterLoader.load(tocUrl(), chapter(), null,
                settings(1000), cache);

        assertFalse("没有规则也应当能从缓存读到正文", offline.isEmpty());
    }

    @Test
    public void offlineWithoutCacheAndWithoutRuleExplainsTheSituation() throws Exception {
        ChapterCache cache = cache();

        try {
            ChapterLoader.load(tocUrl(), chapter(), null, settings(1000), cache);
            fail("没有缓存又没有规则时应当抛错");
        } catch (IOException e) {
            String message = e.getMessage();
            assertNotNull(message);
            assertTrue("错误应说清「没有离线缓存」，实际：" + message,
                    message.contains("离线缓存"));
            assertTrue("并给出可操作的建议，实际：" + message,
                    message.contains("缓存整本书") || message.contains("联网"));
        }
    }

    // ---------- 关闭缓存 / 设置变化 ----------

    @Test
    public void nullCacheMeansPureNetworkBehaviour() throws Exception {
        ChapterCache observer = cache();

        ChapterLoader.Loaded loaded = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), null);

        assertFalse("关掉缓存后仍应正常联网抓到正文", loaded.isEmpty());
        assertEquals("但不应写任何缓存", 0, observer.countChapters(tocUrl()));
    }

    @Test
    public void changingSplitSettingInvalidatesCache() throws Exception {
        ChapterCache cache = cache();
        ChapterLoader.Loaded first = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), cache);
        assertEquals("1000 字上限下整段正文应是一段", 1, first.getSegments().size());
        CachedChapter before = cache.read(tocUrl(), chapter().getUrl());
        assertEquals("缓存应记住当时的切分参数", 1000, before.getMaxChars());

        // 用户把「每段最大字数」改小了：旧缓存的段落划分不再适用，应重新抓取
        ChapterLoader.Loaded again = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(8), cache);

        assertEquals("切分参数变了就该重新抓一次", 2, requests.get());
        assertTrue("段落数应随新设置变多，实际：" + again.getSegments().size() + " 段"
                        + again.getSegments(),
                again.getSegments().size() > first.getSegments().size());
        assertEquals("缓存里的切分参数应更新", 8,
                cache.read(tocUrl(), chapter().getUrl()).getMaxChars());
    }

    @Test
    public void handEditedCacheWithoutSplitSettingIsAccepted() throws Exception {
        ChapterCache cache = cache();
        Path dir = cache.dirFor(tocUrl());
        Files.createDirectories(dir);
        // 顺手把「章节缓存的文件名规则」也钉住：c-<URL 摘要>.json
        Path file = dir.resolve("c-" + CacheKeys.key(chapter().getUrl()) + ".json");
        Files.write(file, ("{\n"
                + "  // 手写的缓存，没有 maxChars\n"
                + "  \"tocUrl\": \"" + tocUrl() + "\",\n"
                + "  \"chapterUrl\": \"" + chapter().getUrl() + "\",\n"
                + "  \"chapterTitle\": \"手写的标题\",\n"
                + "  \"segments\": [\"手写的正文\"],\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        ChapterLoader.Loaded loaded = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), cache);

        assertEquals("手改过的缓存不该被当作未命中（maxChars 未知即采信）", 0, requests.get());
        assertEquals("应读到手工写的内容",
                Collections.singletonList("手写的正文"), loaded.getSegments());
        assertEquals("标题也应来自手写文件", "手写的标题", loaded.getTitle());
    }

    // ---------- 坏数据 ----------

    @Test
    public void corruptCacheEntryFallsBackToNetwork() throws Exception {
        ChapterCache cache = cache();
        ChapterLoader.load(tocUrl(), chapter(), rule(), settings(1000), cache);

        // 把缓存文件写坏：应当视为未命中、重新联网，而不是抛异常
        Path dir = cache.dirFor(tocUrl());
        Path file;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "c-*.json")) {
            file = stream.iterator().next();
        }
        Files.write(file, "{ 坏掉的 JSON".getBytes(StandardCharsets.UTF_8));

        ChapterLoader.Loaded reloaded = ChapterLoader.load(tocUrl(), chapter(), rule(),
                settings(1000), cache);

        assertEquals("缓存坏了应重新抓取", 2, requests.get());
        assertFalse("重新抓取应拿到正文", reloaded.isEmpty());
        assertNotNull("重新抓取后缓存应被修好", cache.read(tocUrl(), chapter().getUrl()));
    }

    @Test
    public void emptyBodyIsNotCached() throws Exception {
        ChapterCache cache = cache();
        ChapterLoader.Loaded loaded = ChapterLoader.load(tocUrl(), chapterAt("/book/empty"),
                rule(), settings(1000), cache);

        assertTrue("空正文的抓取结果应为空", loaded.isEmpty());
        assertNull("空正文不该写进缓存（否则离线读到空白页）",
                cache.read(tocUrl(), chapterAt("/book/empty").getUrl()));
        assertFalse(cache.hasChapter(tocUrl(), chapterAt("/book/empty").getUrl()));
    }

    @Test
    public void nullChapterIsRejected() throws Exception {
        try {
            ChapterLoader.load(tocUrl(), null, rule(), settings(1000), cache());
            fail("章节为空时应抛错");
        } catch (IOException e) {
            assertTrue("错误应说明章节为空：" + e.getMessage(),
                    e.getMessage().contains("章节"));
        }
    }

    @Test
    public void plainLoadStillWorksWithoutCache() throws Exception {
        // 保留的无缓存重载（3 参）行为必须与之前完全一致
        ChapterLoader.Loaded loaded = ChapterLoader.load(chapter(), rule(), settings(1000));

        assertFalse(loaded.isEmpty());
        assertEquals("应发一次请求", 1, requests.get());
        List<String> all = loaded.getSegments();
        assertTrue("正文内容应正确", String.join("", all).contains("第一段正文内容"));
    }

    // ---------- mock 服务 ----------

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
