package com.novelreader;

import com.novelreader.history.ReadingHistory;
import com.novelreader.history.ReadingHistoryEntry;
import com.novelreader.history.ReadingHistoryStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
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
 * 阅读历史的持久化：写完能读回来，且能扛住损坏文件。
 *
 * <p>这是需求里「下次打开 IDEA 还能看到上次的书」的直接兑现点，
 * 因此重点覆盖：<b>重启后仍在</b>、删除后重启不会复活、损坏文件不炸插件。
 *
 * <p>{@code ReadingHistoryStore} 的包私有构造器接受自定义路径，
 * 这里用反射调用，避免为了测试把构造器放开成 public 而污染生产 API。
 */
public class ReadingHistoryStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ReadingHistoryStore storeAt(Path file) throws Exception {
        Constructor<ReadingHistoryStore> ctor =
                ReadingHistoryStore.class.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(file);
    }

    private Path historyFile() {
        return folder.getRoot().toPath().resolve("novelReader/history.json");
    }

    @Test
    public void historySurvivesRestart() throws Exception {
        Path file = historyFile();

        ReadingHistoryStore first = storeAt(file);
        first.record("https://www.tlxsbook.com/255_255330/", "有钱才能考科举",
                11, 2, "第12章 中举", "https://www.tlxsbook.com/255_255330/12.html",
                392, "天籁小说网", 1000L);

        assertTrue("历史文件应当落盘", Files.exists(file));

        // 模拟「关掉 IDEA 再打开」：新建一个 store，内存缓存为空，只能从磁盘读
        ReadingHistoryStore afterRestart = storeAt(file);
        ReadingHistory history = afterRestart.getHistory();

        assertEquals("重启后应还能看到上次的书", 1, history.size());
        ReadingHistoryEntry entry = history.snapshot().get(0);
        assertEquals("https://www.tlxsbook.com/255_255330/", entry.getTocUrl());
        assertEquals("有钱才能考科举", entry.getTitle());
        assertEquals("章节下标应被保留", 11, entry.getChapterIndex());
        assertEquals("段游标应被保留", 2, entry.getSegmentIndex());
        assertEquals("章节标题应被保留", "第12章 中举", entry.getChapterTitle());
        assertEquals("章节 URL 应被保留", 392, entry.getChapterCount());
    }

    @Test
    public void deletedEntryStaysDeletedAfterRestart() throws Exception {
        Path file = historyFile();

        ReadingHistoryStore first = storeAt(file);
        first.record("https://a.com/1/", "A书", 0, 0, "第1章", "u1", 10, "r", 100L);
        first.record("https://b.com/1/", "B书", 0, 0, "第1章", "u2", 20, "r", 200L);
        assertTrue(first.remove("https://a.com/1/"));

        ReadingHistoryStore afterRestart = storeAt(file);
        assertEquals("删除应持久化，重启后不能复活", 1, afterRestart.getHistory().size());
        assertNull(afterRestart.getHistory().find("https://a.com/1/"));
        assertNotNull(afterRestart.getHistory().find("https://b.com/1/"));
    }

    @Test
    public void progressUpdateOverwritesPreviousPosition() throws Exception {
        Path file = historyFile();

        ReadingHistoryStore store = storeAt(file);
        store.record("https://a.com/1/", "A书", 3, 1, "第4章", "u4", 50, "r", 100L);
        store.record("https://a.com/1/", "A书", 7, 5, "第8章", "u8", 50, "r", 200L);

        ReadingHistoryStore afterRestart = storeAt(file);
        List<ReadingHistoryEntry> entries = afterRestart.getHistory().snapshot();

        assertEquals("同一本书只应有一条", 1, entries.size());
        assertEquals("应保留最新进度", 7, entries.get(0).getChapterIndex());
        assertEquals("应保留最新段号", 5, entries.get(0).getSegmentIndex());
    }

    @Test
    public void missingFileYieldsEmptyHistoryInsteadOfFailing() throws Exception {
        ReadingHistoryStore store = storeAt(historyFile());

        assertTrue("文件不存在时应返回空历史',", store.getHistory().isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    public void corruptedFileDoesNotBreakThePlugin() throws Exception {
        Path file = historyFile();
        Files.createDirectories(file.getParent());
        // 半个 JSON：模拟写盘时断电或被手工改坏
        Files.write(file, "{\"version\":1,\"entries\":[{\"tocUrl\":\"https".getBytes(StandardCharsets.UTF_8));

        ReadingHistoryStore store = storeAt(file);

        assertTrue("损坏的历史应当被当作空历史，而不是抛异常让插件起不来",
                store.getHistory().isEmpty());
    }

    @Test
    public void emptyFileYieldsEmptyHistory() throws Exception {
        Path file = historyFile();
        Files.createDirectories(file.getParent());
        Files.write(file, "".getBytes(StandardCharsets.UTF_8));

        ReadingHistoryStore store = storeAt(file);

        assertTrue(store.getHistory().isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    public void handEditedFileWithCommentsIsAccepted() throws Exception {
        Path file = historyFile();
        Files.createDirectories(file.getParent());
        // 复用规则文件那套宽松解析：允许注释与尾随逗号
        String json = "{\n"
                + "  // 手工加个注释\n"
                + "  \"version\": 1,\n"
                + "  \"entries\": [\n"
                + "    {\n"
                + "      \"tocUrl\": \"https://a.com/1/\",\n"
                + "      \"title\": \"手改的书\",\n"
                + "      \"chapterIndex\": 4,\n"
                + "      \"segmentIndex\": 1,\n"
                + "      \"chapterCount\": 30,\n"
                + "      \"lastReadAt\": 500,\n"
                + "    },\n"
                + "  ]\n"
                + "}\n";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        ReadingHistory history = storeAt(file).getHistory();

        assertEquals(1, history.size());
        assertEquals("手改的书", history.snapshot().get(0).getTitle());
        assertEquals(4, history.snapshot().get(0).getChapterIndex());
    }

    @Test
    public void invalidateForcesReloadFromDisk() throws Exception {
        Path file = historyFile();
        ReadingHistoryStore store = storeAt(file);
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "u", 5, "r", 100L);
        assertEquals(1, store.size());

        // 模拟外部（用户手工）改了文件后要求刷新
        String json = "{\"version\":1,\"entries\":["
                + "{\"tocUrl\":\"https://external.com/1/\",\"title\":\"外部来的\",\"lastReadAt\":9999}"
                + "]}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        store.invalidate();

        assertEquals(1, store.size());
        assertNotNull("刷新后应看到磁盘上的新内容", store.getHistory().find("https://external.com/1/"));
        assertNull("刷新后不应还留着内存里的旧内容", store.getHistory().find("https://a.com/1/"));
    }

    @Test
    public void historyFileLandsNextToRulesFile() {
        // 两个文件都放在 {配置目录}/novelReader/ 下，便于用户统一备份
        Path history = ReadingHistoryStore.defaultHistoryFile();

        assertNotNull(history);
        assertTrue("历史文件应位于 novelReader 目录下: " + history,
                history.toString().replace('\\', '/').contains("novelReader/"));
        assertTrue("历史文件名应为 history.json: " + history,
                history.toString().endsWith("history.json"));
    }

    @Test
    public void writingIntoUnwritableLocationDoesNotThrow() throws Exception {
        ReadingHistoryStore store = storeAt(folder.getRoot().toPath().resolve("a.txt").resolve("b/history.json"));

        // 把文件写到「一个文件路径下面」必定失败；要求静默失败而不是抛异常
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "u", 5, "r", 100L);

        // 内存里的记录仍然生效（当次会话可用），只是没能落盘
        assertEquals("写盘失败不该影响会话内可用性", 1, store.size());
    }

    @Test
    public void noTempFileLeftBehindAfterSuccessfulWrite() throws Exception {
        Path file = historyFile();
        ReadingHistoryStore store = storeAt(file);

        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "u", 5, "r", 100L);

        assertTrue("正式文件应存在", Files.exists(file));
        assertFalse("临时文件不应残留",
                Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
    }

    @Test
    public void clearRemovesEverythingAndPersists() throws Exception {
        Path file = historyFile();
        ReadingHistoryStore store = storeAt(file);
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "u", 5, "r", 100L);
        store.record("https://b.com/1/", "B书", 0, 0, "第1章", "u", 5, "r", 200L);

        store.clear();

        assertTrue(store.getHistory().isEmpty());
        assertTrue("清空应持久化", storeAt(file).getHistory().isEmpty());
    }

    @Test
    public void manyBooksRoundTripWithoutLoss() throws Exception {
        Path file = historyFile();
        ReadingHistoryStore store = storeAt(file);
        int count = 20;
        for (int i = 0; i < count; i++) {
            store.record("https://s" + i + ".com/1/", "书" + i, i, 0, "第1章", "u" + i,
                    100, "r", 1000L + i);
        }

        ReadingHistory afterRestart = storeAt(file).getHistory();

        assertEquals("多本书都应完整往返", count, afterRestart.size());
        // 最新的排最前
        assertEquals("https://s19.com/1/", afterRestart.snapshot().get(0).getTocUrl());
    }

    @Test
    public void unicodeTitlesRoundTripCorrectly() throws Exception {
        Path historyFile = historyFile();
        ReadingHistoryStore store = storeAt(historyFile);
        String title = "有钱才能考科举（池上楼台）·第 12 章「中举」";
        store.record("https://a.com/1/", title, 11, 0, "第12章 中举", "u", 392, "天籁小说网", 100L);

        String raw = new String(Files.readAllBytes(historyFile), StandardCharsets.UTF_8);

        assertTrue("中文不应被转义成 \\u 序列，便于用户直接看文件", raw.contains("有钱才能考科举"));
        assertEquals(title, storeAt(historyFile).getHistory().snapshot().get(0).getTitle());
    }

    @Test
    public void ioExceptionOnReadIsSwallowed() throws Exception {
        // 让路径指向一个目录：读取时必然 IOException
        Path dir = folder.getRoot().toPath().resolve("novelReader/history.json");
        Files.createDirectories(dir);

        ReadingHistoryStore store = storeAt(dir);

        assertTrue("读取异常时应返回空历史", store.getHistory().isEmpty());
    }
}
