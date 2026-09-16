package com.novelreader;

import com.novelreader.cache.CachedBook;
import com.novelreader.cache.CachedChapter;
import com.novelreader.cache.ChapterCache;
import com.novelreader.model.Chapter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 离线缓存的读写、统计与清理。
 *
 * <p>重点覆盖三件事：
 * <ul>
 *   <li><b>写进去能原样读回来</b>（分段一字不差 —— 段落划分错了正文就乱了）；</li>
 *   <li><b>任何损坏都退化为「未命中」</b>，绝不抛异常给阅读链路；</li>
 *   <li>清理只删该删的（清一本书不能把别的书也清了）。</li>
 * </ul>
 */
public class ChapterCacheTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String BOOK_A = "https://a.com/book/1/";
    private static final String BOOK_B = "https://b.com/book/2/";

    private ChapterCache cacheAt(Path dir) throws Exception {
        Constructor<ChapterCache> ctor = ChapterCache.class.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(dir);
    }

    private ChapterCache cache() throws Exception {
        return cacheAt(folder.getRoot().toPath().resolve("cache"));
    }

    private static CachedChapter chapter(String tocUrl, String url, String title, String... segments) {
        CachedChapter cached = new CachedChapter();
        cached.tocUrl = tocUrl;
        cached.chapterUrl = url;
        cached.chapterTitle = title;
        cached.segments = Arrays.asList(segments);
        cached.pages = 2;
        cached.fetchedAt = 1000L;
        return cached;
    }

    private static CachedBook book(String tocUrl, String title, String... chapterUrls) {
        CachedBook cached = new CachedBook();
        cached.tocUrl = tocUrl;
        cached.bookTitle = title;
        cached.ruleName = "测试规则";
        List<Chapter> chapters = new java.util.ArrayList<>();
        for (int i = 0; i < chapterUrls.length; i++) {
            chapters.add(new Chapter("第" + (i + 1) + "章", chapterUrls[i]));
        }
        cached.chapters = chapters;
        cached.cachedAt = 2000L;
        return cached;
    }

    // ---------- 章节往返 ----------

    @Test
    public void chapterRoundTripKeepsSegmentsExactly() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章 起点",
                "第一段正文", "第二段正文", "第三段正文"));

        CachedChapter loaded = cache.read(BOOK_A, "https://a.com/1.html");

        assertNotNull("写入后应能读回", loaded);
        assertEquals("章节标题应保留", "第1章 起点", loaded.getChapterTitle());
        assertEquals("分段必须一字不差（段落划分错了正文就乱了）",
                Arrays.asList("第一段正文", "第二段正文", "第三段正文"), loaded.getSegments());
        assertEquals("页数应保留", 2, loaded.getPages());
        assertEquals("目录 URL 应保留", BOOK_A, loaded.getTocUrl());
    }

    @Test
    public void missingChapterIsAMiss() throws Exception {
        assertNull("没写过的章节应返回 null（去联网）",
                cache().read(BOOK_A, "https://a.com/nope.html"));
        assertFalse("没写过的章节 hasChapter 应为 false",
                cache().hasChapter(BOOK_A, "https://a.com/nope.html"));
    }

    @Test
    public void hasChapterIsTrueAfterSave() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        assertTrue(cache.hasChapter(BOOK_A, "https://a.com/1.html"));
    }

    @Test
    public void savingSameChapterAgainOverwrites() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "旧正文"));
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "新正文"));

        assertEquals("重抓后应覆盖（否则永远读不到更新）",
                Collections.singletonList("新正文"), cache.read(BOOK_A, "https://a.com/1.html").getSegments());
        assertEquals("同一章不应变成两份", 1, cache.countChapters(BOOK_A));
    }

    @Test
    public void emptyOrInvalidChapterIsNotSaved() throws Exception {
        ChapterCache cache = cache();
        assertFalse("没有正文的缓存没有意义", cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章")));
        assertFalse("没有 URL 的缓存无法定位", cache.save(chapter(BOOK_A, "", "第1章", "正文")));
        assertFalse("null 不该抛异常", cache.save(null));
        assertEquals("被拒绝的条目不该落盘", 0, cache.countChapters(BOOK_A));
    }

    @Test
    public void blankKeysAreRejected() throws Exception {
        ChapterCache cache = cache();
        assertNull("空目录 URL 读不出东西", cache.read("", "https://a.com/1.html"));
        assertNull("空章节 URL 读不出东西", cache.read(BOOK_A, ""));
        assertNull("空目录 URL 没有目录", cache.dirFor(""));
        assertFalse("空 URL 不该写下去", cache.save(chapter("", "https://a.com/1.html", "x", "正文")));
    }

    @Test
    public void corruptChapterFileIsTreatedAsMiss() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        // 找到那个文件并写坏它
        Path dir = cache.dirFor(BOOK_A);
        Path file;
        try (java.nio.file.DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, "c-*.json")) {
            file = stream.iterator().next();
        }
        Files.write(file, "{ 这不是 JSON".getBytes(StandardCharsets.UTF_8));

        assertNull("损坏的缓存应视为未命中，而不是抛异常",
                cache.read(BOOK_A, "https://a.com/1.html"));
    }

    @Test
    public void emptyChapterFileIsTreatedAsMiss() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        Path dir = cache.dirFor(BOOK_A);
        Path file;
        try (java.nio.file.DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, "c-*.json")) {
            file = stream.iterator().next();
        }
        Files.write(file, new byte[0]);

        assertNull("空文件应视为未命中", cache.read(BOOK_A, "https://a.com/1.html"));
    }

    @Test
    public void handEditedChapterFileWithCommentsIsAccepted() throws Exception {
        ChapterCache cache = cache();
        Path dir = cache.dirFor(BOOK_A);
        Files.createDirectories(dir);
        // 复用规则文件那套宽松解析：允许注释与尾随逗号
        String json = "{\n"
                + "  // 手工改了正文\n"
                + "  \"tocUrl\": \"" + BOOK_A + "\",\n"
                + "  \"chapterUrl\": \"https://a.com/1.html\",\n"
                + "  \"chapterTitle\": \"手改的标题\",\n"
                + "  \"segments\": [\"手改的正文\",],\n"
                + "}\n";
        // 文件名必须与 cache 自己算的一致，这里直接让 cache 写一次再覆盖内容
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "占位"));
        Path file;
        try (java.nio.file.DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, "c-*.json")) {
            file = stream.iterator().next();
        }
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        CachedChapter loaded = cache.read(BOOK_A, "https://a.com/1.html");
        assertNotNull("手改过的缓存应能读出来", loaded);
        assertEquals("手改的标题", loaded.getChapterTitle());
        assertEquals(Collections.singletonList("手改的正文"), loaded.getSegments());
    }

    @Test
    public void noTempFileLeftBehind() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));

        Path dir = cache.dirFor(BOOK_A);
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tmp")) {
            assertFalse("写成功不该留下 .tmp", stream.iterator().hasNext());
        }
    }

    // ---------- 书目清单 ----------

    @Test
    public void bookRoundTripKeepsChapters() throws Exception {
        ChapterCache cache = cache();
        cache.saveBook(book(BOOK_A, "有钱才能考科举",
                "https://a.com/1.html", "https://a.com/2.html", "https://a.com/3.html"));

        CachedBook loaded = cache.readBook(BOOK_A);

        assertNotNull("应能读回目录清单（离线建会话要用）", loaded);
        assertEquals("书名应保留", "有钱才能考科举", loaded.getBookTitle());
        assertEquals("规则名应保留（仅展示用）", "测试规则", loaded.getRuleName());
        assertEquals("章节数应保留", 3, loaded.getChapterCount());
        assertEquals("章节标题应保留", "第2章", loaded.getChapters().get(1).getTitle());
        assertEquals("章节 URL 应保留（定位正靠它）",
                "https://a.com/2.html", loaded.getChapters().get(1).getUrl());
    }

    @Test
    public void missingBookIsNull() throws Exception {
        assertNull("没缓存过的书应返回 null", cache().readBook(BOOK_A));
        assertNull("空 URL 返回 null", cache().readBook(""));
    }

    @Test
    public void invalidBookIsNotSaved() throws Exception {
        ChapterCache cache = cache();
        assertFalse("没有章节的清单建不出会话", cache.saveBook(book(BOOK_A, "空书")));
        assertFalse("null 不该抛异常", cache.saveBook(null));
        assertNull(cache.readBook(BOOK_A));
    }

    // ---------- 统计与清理 ----------

    @Test
    public void countChaptersCountsOnlyThatBook() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        cache.save(chapter(BOOK_A, "https://a.com/2.html", "第2章", "正文"));
        cache.save(chapter(BOOK_B, "https://b.com/1.html", "第1章", "正文"));

        assertEquals("只数这本书", 2, cache.countChapters(BOOK_A));
        assertEquals("另一本也各算各的", 1, cache.countChapters(BOOK_B));
        assertEquals("没缓存过的书为 0", 0, cache.countChapters("https://c.com/x/"));
    }

    @Test
    public void sizeAndBookCountReflectWhatWasWritten() throws Exception {
        ChapterCache cache = cache();
        assertTrue("空缓存的书籍数为 0", cache.bookCount() == 0);
        assertEquals("空缓存大小为 0", 0L, cache.totalSizeBytes());

        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        cache.save(chapter(BOOK_B, "https://b.com/1.html", "第1章", "正文"));

        assertEquals("两本书各占一个目录", 2, cache.bookCount());
        assertTrue("大小应大于 0", cache.totalSizeBytes() > 0L);
    }

    @Test
    public void clearBookRemovesOnlyThatBook() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        cache.saveBook(book(BOOK_A, "A 书", "https://a.com/1.html"));
        cache.save(chapter(BOOK_B, "https://b.com/1.html", "第1章", "正文"));
        cache.saveBook(book(BOOK_B, "B 书", "https://b.com/1.html"));

        assertTrue("应确实删掉了东西", cache.clearBook(BOOK_A));

        assertNull("A 书的章节应没了", cache.read(BOOK_A, "https://a.com/1.html"));
        assertNull("A 书的目录清单也应没了", cache.readBook(BOOK_A));
        assertNotNull("B 书的章节必须留着", cache.read(BOOK_B, "https://b.com/1.html"));
        assertNotNull("B 书的目录清单必须留着", cache.readBook(BOOK_B));
    }

    @Test
    public void clearAllWipesEverything() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        cache.save(chapter(BOOK_B, "https://b.com/1.html", "第1章", "正文"));

        assertTrue(cache.clearAll());

        assertEquals("清空后书籍数为 0", 0, cache.bookCount());
        assertEquals("清空后大小为 0", 0L, cache.totalSizeBytes());
        assertNull(cache.read(BOOK_A, "https://a.com/1.html"));
    }

    @Test
    public void clearingUnknownBookIsHarmless() throws Exception {
        ChapterCache cache = cache();
        assertFalse("删不存在的书不该报错", cache.clearBook("https://nope.com/x/"));
        assertFalse("清空不存在的缓存目录也不该报错",
                cacheAt(folder.getRoot().toPath().resolve("never")).clearAll());
    }

    @Test
    public void writingIntoUnwritableLocationDoesNotThrow() throws Exception {
        // 先放一个普通文件 a.txt，再把缓存根指到 a.txt/b —— 建目录必定失败
        Path blocker = folder.getRoot().toPath().resolve("a.txt");
        Files.write(blocker, new byte[0]);

        ChapterCache cache = cacheAt(blocker.resolve("b"));
        assertFalse("写不进去应返回 false 而不是抛异常",
                cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文")));
        assertNull("写失败后也读不出东西", cache.read(BOOK_A, "https://a.com/1.html"));
    }

    @Test
    public void removeDeletesSingleChapter() throws Exception {
        ChapterCache cache = cache();
        cache.save(chapter(BOOK_A, "https://a.com/1.html", "第1章", "正文"));
        cache.save(chapter(BOOK_A, "https://a.com/2.html", "第2章", "正文"));

        assertTrue("删一章应成功", cache.remove(BOOK_A, "https://a.com/1.html"));
        assertNull(cache.read(BOOK_A, "https://a.com/1.html"));
        assertNotNull("另一章必须留着", cache.read(BOOK_A, "https://a.com/2.html"));
        assertFalse("删不存在的章节返回 false", cache.remove(BOOK_A, "https://a.com/nope.html"));
    }

    @Test
    public void cacheDirLandsUnderNovelReaderFolder() {
        Path dir = ChapterCache.defaultCacheDir();
        assertNotNull(dir);
        assertTrue("缓存目录应位于 novelReader 下：" + dir,
                dir.toString().replace('\\', '/').contains("novelReader/"));
        assertTrue("目录名应为 cache：" + dir, dir.toString().endsWith("cache"));
    }

    @Test
    public void dirIsStableForSameUrl() throws Exception {
        ChapterCache cache = cache();
        assertEquals("同一个 URL 的目录必须稳定（否则写了读不回来）",
                cache.dirFor(BOOK_A), cache.dirFor("  " + BOOK_A + " "));
    }
}
