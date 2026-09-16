package com.novelreader;

import com.novelreader.bookmark.BookmarkEntry;
import com.novelreader.bookmark.BookmarkList;
import com.novelreader.bookmark.BookmarkStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 书签的持久化：写完能读回来，且能扛住损坏文件。
 *
 * <p>这是「关掉 IDEA 再打开，书签还在」的直接兑现点，因此重点覆盖：
 * <b>重启后仍在</b>、删除后重启不会复活、损坏 / 空 / 目录当文件等异常形态不炸插件。
 *
 * <p>{@code BookmarkStore} 的包私有构造器接受自定义路径，这里用反射调用，
 * 避免为了测试把构造器放开成 public 而污染生产 API（与历史测试同一套路）。
 */
public class BookmarkStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private BookmarkStore storeAt(Path file) throws Exception {
        Constructor<BookmarkStore> ctor = BookmarkStore.class.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(file);
    }

    private Path bookmarkFile() {
        return folder.getRoot().toPath().resolve("novelReader/bookmarks.json");
    }

    private static BookmarkEntry entry(String tocUrl, int chapterIndex, int segmentIndex) {
        BookmarkEntry entry = new BookmarkEntry();
        entry.tocUrl = tocUrl;
        entry.bookTitle = "有钱才能考科举";
        entry.chapterIndex = chapterIndex;
        entry.segmentIndex = segmentIndex;
        entry.chapterUrl = tocUrl + (chapterIndex + 1) + ".html";
        entry.chapterTitle = "第" + (chapterIndex + 1) + "章 中举";
        entry.note = "伏笔";
        entry.createdAt = 1000L;
        return entry;
    }

    @Test
    public void bookmarksSurviveRestart() throws Exception {
        Path file = bookmarkFile();

        BookmarkStore first = storeAt(file);
        first.add(entry("https://www.tlxsbook.com/255_255330/", 11, 2));

        assertTrue("书签文件应当落盘", Files.exists(file));

        // 模拟「关掉 IDEA 再打开」：新建 store，内存缓存为空，只能从磁盘读
        BookmarkStore afterRestart = storeAt(file);
        List<BookmarkEntry> loaded = afterRestart.forBook("https://www.tlxsbook.com/255_255330/");

        assertEquals("重启后应还能看到书签", 1, loaded.size());
        BookmarkEntry entry = loaded.get(0);
        assertEquals("章节下标应保留", 11, entry.getChapterIndex());
        assertEquals("段下标应保留", 2, entry.getSegmentIndex());
        assertEquals("备注应保留", "伏笔", entry.getNote());
        assertEquals("章节标题应保留", "第12章 中举", entry.getChapterTitle());
    }

    @Test
    public void samePositionIsMergedAcrossRestart() throws Exception {
        Path file = bookmarkFile();
        storeAt(file).add(entry("https://a.com/1/", 3, 1));

        // 重启后再在同一位置加一次：磁盘上仍应只有一条
        BookmarkStore afterRestart = storeAt(file);
        afterRestart.add(entry("https://a.com/1/", 3, 1));

        assertEquals("同一位置跨重启也不该变成两条", 1, afterRestart.size());
    }

    @Test
    public void deleteIsPersisted() throws Exception {
        Path file = bookmarkFile();
        BookmarkStore store = storeAt(file);
        BookmarkEntry saved = store.add(entry("https://a.com/1/", 3, 1));
        store.add(entry("https://a.com/1/", 5, 0));

        assertTrue("删除应成功", store.remove(saved.key()));

        BookmarkStore afterRestart = storeAt(file);
        assertEquals("删除后重启不应复活", 1, afterRestart.size());
        assertEquals("留下的应是另一条", 5,
                afterRestart.forBook("https://a.com/1/").get(0).getChapterIndex());
    }

    @Test
    public void clearRemovesEverythingAndPersists() throws Exception {
        Path file = bookmarkFile();
        BookmarkStore store = storeAt(file);
        store.add(entry("https://a.com/1/", 0, 0));
        store.add(entry("https://b.com/2/", 0, 0));

        store.clear();

        assertEquals("清空后应为空", 0, store.size());
        assertEquals("重启后仍应为空", 0, storeAt(file).size());
    }

    @Test
    public void missingFileYieldsEmptyInsteadOfFailing() throws Exception {
        BookmarkStore store = storeAt(folder.getRoot().toPath().resolve("nope/bookmarks.json"));
        assertTrue("文件不存在时应返回空书签", store.getBookmarks().isEmpty());
    }

    @Test
    public void corruptedFileDoesNotBreakThePlugin() throws Exception {
        Path file = bookmarkFile();
        Files.createDirectories(file.getParent());
        Files.write(file, "{ 这不是 JSON".getBytes(StandardCharsets.UTF_8));

        assertTrue("损坏文件应退化为空书签而不是抛异常",
                storeAt(file).getBookmarks().isEmpty());
    }

    @Test
    public void emptyFileYieldsEmpty() throws Exception {
        Path file = bookmarkFile();
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);

        assertTrue("空文件应视为没有书签", storeAt(file).getBookmarks().isEmpty());
    }

    @Test
    public void ioExceptionOnReadIsSwallowed() throws Exception {
        // 让路径指向一个目录：读取时必然 IOException
        Path dir = folder.getRoot().toPath().resolve("novelReader/bookmarks.json");
        Files.createDirectories(dir);

        assertTrue("读取异常时应返回空书签", storeAt(dir).getBookmarks().isEmpty());
    }

    @Test
    public void handEditedFileWithCommentsIsAccepted() throws Exception {
        Path file = bookmarkFile();
        Files.createDirectories(file.getParent());
        // 复用规则文件那套宽松解析：允许注释与尾随逗号
        String json = "{\n"
                + "  // 手工加个注释\n"
                + "  \"version\": 1,\n"
                + "  \"entries\": [\n"
                + "    {\n"
                + "      \"tocUrl\": \"https://a.com/1/\",\n"
                + "      \"bookTitle\": \"手改的书\",\n"
                + "      \"chapterIndex\": 4,\n"
                + "      \"segmentIndex\": 1,\n"
                + "      \"note\": \"手写的备注\",\n"
                + "    },\n"
                + "  ]\n"
                + "}\n";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        BookmarkList loaded = storeAt(file).getBookmarks();

        assertEquals("应能读出手改的书签", 1, loaded.size());
        BookmarkEntry entry = loaded.forBook("https://a.com/1/").get(0);
        assertEquals("手改的书", entry.getBookTitle());
        assertEquals("手写的备注", entry.getNote());
        assertEquals(4, entry.getChapterIndex());
    }

    @Test
    public void writingIntoUnwritableLocationDoesNotThrow() throws Exception {
        // 把文件写到「一个文件路径下面」必定失败；要求静默失败而不是抛异常
        Path blocked = folder.getRoot().toPath().resolve("a.txt").resolve("b/bookmarks.json");
        BookmarkStore store = storeAt(blocked);

        assertNotNull("写盘失败仍应返回条目", store.add(entry("https://a.com/1/", 0, 0)));
        assertEquals("写盘失败不该影响会话内可用性", 1, store.size());
    }

    @Test
    public void noTempFileLeftBehindAfterSuccessfulWrite() throws Exception {
        Path file = bookmarkFile();
        BookmarkStore store = storeAt(file);
        store.add(entry("https://a.com/1/", 0, 0));

        assertTrue("书签文件应存在", Files.exists(file));
        assertFalse("写成功不该留下 .tmp",
                Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
    }

    @Test
    public void invalidEntryIsNotPersisted() throws Exception {
        Path file = bookmarkFile();
        BookmarkStore store = storeAt(file);

        assertNull("无效书签应被拒绝", store.add(new BookmarkEntry()));
        assertFalse("被拒绝的书签不该落盘", Files.exists(file));
    }

    @Test
    public void bookmarkFileLandsNextToRulesFile() {
        // 三个文件都放在 {配置目录}/novelReader/ 下，便于用户统一备份
        Path file = BookmarkStore.defaultBookmarkFile();

        assertNotNull(file);
        assertTrue("书签文件应位于 novelReader 目录下: " + file,
                file.toString().replace('\\', '/').contains("novelReader/"));
        assertTrue("书签文件名应为 bookmarks.json: " + file,
                file.toString().endsWith("bookmarks.json"));
    }

    @Test
    public void invalidateForcesReloadFromDisk() throws Exception {
        Path file = bookmarkFile();
        BookmarkStore store = storeAt(file);
        store.add(entry("https://a.com/1/", 0, 0));

        // 外部（另一实例）改了文件后，invalidate 应让本实例重新读盘
        storeAt(file).add(entry("https://a.com/1/", 7, 0));
        assertEquals("未 invalidate 时仍是缓存", 1, store.size());

        store.invalidate();
        assertEquals("invalidate 后应从磁盘重新加载", 2, store.size());
    }

    @Test
    public void twoBooksAreKeptSeparate() throws Exception {
        Path file = bookmarkFile();
        BookmarkStore store = storeAt(file);
        store.add(entry("https://a.com/1/", 0, 0));
        store.add(entry("https://b.com/2/", 0, 0));

        assertEquals("A 书 1 条", 1, store.forBook("https://a.com/1/").size());
        assertEquals("B 书 1 条", 1, store.forBook("https://b.com/2/").size());
        assertEquals("总数 2", 2, store.size());
    }
}
