package com.novelreader;

import com.novelreader.action.AddBookmarkAction;
import com.novelreader.action.ShowBookmarksAction;
import com.novelreader.bookmark.BookmarkEntry;
import com.novelreader.bookmark.BookmarkStore;
import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 书签动作层的纯逻辑与接线守卫。
 *
 * <p>这一层最容易出的错都是「现象上看不出来」的那类：
 * <ul>
 *   <li>没选中就点跳转 → 默默跳到第 1 条（所以这里钉死「没选中必须返回 null」）；</li>
 *   <li>删除时把没选中的也删了 → 用户以为只是删了一条；</li>
 *   <li>会话字段没映射到书签 → 站点改版后跳错章、或永远跳第 1 段；</li>
 *   <li>动作写了但没在 plugin.xml 注册 → 菜单里根本没有，代码看起来却完全正常。</li>
 * </ul>
 */
public class BookmarkSelectionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String BOOK = "https://a.com/book/";

    private BookmarkStore storeAt(Path file) throws Exception {
        Constructor<BookmarkStore> ctor = BookmarkStore.class.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(file);
    }

    private static BookmarkEntry bookmark(int chapterIndex, int segmentIndex, String chapterUrl,
                                          String note) {
        BookmarkEntry entry = new BookmarkEntry();
        entry.tocUrl = BOOK;
        entry.chapterIndex = chapterIndex;
        entry.segmentIndex = segmentIndex;
        entry.chapterUrl = chapterUrl;
        entry.note = note;
        entry.createdAt = 100L;
        return entry;
    }

    /** 三章的书，当前停在第 2 章、第 2 段。 */
    private static ReaderState threeChapterState() {
        List<Chapter> chapters = Arrays.asList(
                new Chapter("第1章 起点", "https://a.com/1.html"),
                new Chapter("第2章 风起", "https://a.com/2.html"),
                new Chapter("第3章 云涌", "https://a.com/3.html"));
        ReaderState state = new ReaderState(chapters, 1, "第2章 风起",
                Arrays.asList("第一段正文", "第二段正文内容"), null, BOOK);
        state.moveToSegment(1);
        return state;
    }

    // ---------- 跳转：没选中就绝不跳 ----------

    @Test
    public void pickForJumpReturnsNullWhenNothingSelected() {
        assertNull("没选中时不能默认跳第一条", ShowBookmarksAction.pickForJump(Collections.emptyList()));
        assertNull("null 选中列表应安全返回 null", ShowBookmarksAction.pickForJump(null));
    }

    @Test
    public void pickForJumpSkipsDirtyEntries() {
        List<BookmarkEntry> selected = new ArrayList<>();
        selected.add(null);
        selected.add(new BookmarkEntry());          // 没有目录 URL，无效
        selected.add(bookmark(2, 0, "https://a.com/3.html", "有效"));

        BookmarkEntry picked = ShowBookmarksAction.pickForJump(selected);
        assertNotNull("应能跳过脏数据找到有效条目", picked);
        assertEquals("应取第一个有效条目", 2, picked.getChapterIndex());
    }

    @Test
    public void pickForJumpReturnsNullWhenAllEntriesInvalid() {
        List<BookmarkEntry> selected = Arrays.asList(null, new BookmarkEntry());
        assertNull("全无效时应返回 null，而不是任意一条", ShowBookmarksAction.pickForJump(selected));
    }

    @Test
    public void pickForJumpTakesFirstOfMultipleSelection() {
        List<BookmarkEntry> selected = Arrays.asList(
                bookmark(5, 0, "https://a.com/6.html", "先"),
                bookmark(1, 0, "https://a.com/2.html", "后"));
        assertEquals("多选时应取列表中的第一条", "先",
                ShowBookmarksAction.pickForJump(selected).getNote());
    }

    // ---------- 删除：只删选中的 ----------

    @Test
    public void deleteSelectedRemovesOnlySelected() throws Exception {
        BookmarkStore store = storeAt(folder.getRoot().toPath().resolve("bookmarks.json"));
        BookmarkEntry keep = store.add(bookmark(0, 0, "https://a.com/1.html", "留着"));
        BookmarkEntry drop = store.add(bookmark(1, 0, "https://a.com/2.html", "删掉"));
        store.add(bookmark(2, 0, "https://a.com/3.html", "也留着"));

        assertEquals("应删掉 1 条", 1,
                ShowBookmarksAction.deleteSelected(store, Collections.singletonList(drop)));

        List<String> notes = new ArrayList<>();
        for (BookmarkEntry entry : store.forBook(BOOK)) {
            notes.add(entry.getNote());
        }
        assertFalse("删掉的不应还在", notes.contains("删掉"));
        assertTrue("没选中的必须一条不少", notes.contains("留着") && notes.contains("也留着"));
        assertEquals("总数应剩 2", 2, store.size());
        assertNotNull(keep);
    }

    @Test
    public void deleteSelectedHandlesDirtyAndDuplicateSelection() throws Exception {
        BookmarkStore store = storeAt(folder.getRoot().toPath().resolve("bookmarks.json"));
        BookmarkEntry entry = store.add(bookmark(0, 0, "https://a.com/1.html", "唯一的"));
        store.add(bookmark(1, 0, "https://a.com/2.html", "别人"));

        List<BookmarkEntry> selected = new ArrayList<>();
        selected.add(null);
        selected.add(new BookmarkEntry());   // 无效条目
        selected.add(entry);
        selected.add(entry);                 // 同一条被重复选中

        assertEquals("重复选中只应算一次", 1, ShowBookmarksAction.deleteSelected(store, selected));
        assertEquals("其余书签不该受影响", 1, store.size());
    }

    @Test
    public void deleteSelectedHandlesNullArguments() {
        assertEquals("store 为 null 时返回 0", 0,
                ShowBookmarksAction.deleteSelected(null, Collections.emptyList()));
        assertEquals("选中为 null 时返回 0", 0, ShowBookmarksAction.deleteSelected(null, null));
    }

    // ---------- 展示文案对齐 ----------

    @Test
    public void labelsAlignOneToOneWithEntries() {
        List<BookmarkEntry> entries = Arrays.asList(
                bookmark(0, 0, "https://a.com/1.html", "第一条"),
                bookmark(11, 2, "https://a.com/12.html", "第二条"));

        List<String> labels = ShowBookmarksAction.labels(entries);

        assertEquals("展示项数量必须与书签数量一致（错位会跳到错位置）",
                entries.size(), labels.size());
        assertTrue("第 1 项应来自第 1 条：" + labels.get(0), labels.get(0).contains("第一条"));
        assertTrue("第 2 项应来自第 2 条：" + labels.get(1), labels.get(1).contains("第二条"));
        assertTrue("应含 1-based 章节号：" + labels.get(1), labels.get(1).contains("第 12 章"));
        assertTrue("应含 1-based 段号：" + labels.get(1), labels.get(1).contains("第 3 段"));
    }

    @Test
    public void labelsOfEmptyOrNullAreEmpty() {
        assertTrue("空列表返回空", ShowBookmarksAction.labels(Collections.emptyList()).isEmpty());
        assertTrue("null 返回空", ShowBookmarksAction.labels(null).isEmpty());
    }

    // ---------- 会话 → 书签 的字段映射 ----------

    @Test
    public void newEntryMapsEveryFieldFromSession() {
        ReaderState state = threeChapterState();
        BookmarkEntry entry = AddBookmarkAction.newEntry(state, "  记一下  ", 12345L);

        assertNotNull("有目录的会话应能生成书签", entry);
        assertEquals("目录 URL 是书的标识", BOOK, entry.getTocUrl());
        assertEquals("书名取第 1 章标题", "第1章 起点", entry.getBookTitle());
        assertEquals("章节下标应来自会话", 1, entry.getChapterIndex());
        assertEquals("段下标应来自会话", 1, entry.getSegmentIndex());
        assertEquals("章节标题应来自会话", "第2章 风起", entry.getChapterTitle());
        assertEquals("章节 URL 必须带上（否则站点改版后会跳错章）",
                "https://a.com/2.html", entry.getChapterUrl());
        assertEquals("摘录应取自当前段", "第二段正文内容", entry.getSnippet());
        assertEquals("备注应去掉首尾空白", "记一下", entry.getNote());
        assertEquals("创建时间应使用传入值", 12345L, entry.getCreatedAt());
    }

    @Test
    public void newEntryTruncatesLongSnippet() {
        ReaderState state = new ReaderState(
                Arrays.asList(new Chapter("第1章", "https://a.com/1.html")), 0, "第1章",
                Collections.singletonList("甲".repeat(200)), null, BOOK);

        String snippet = AddBookmarkAction.newEntry(state, "", 1L).getSnippet();
        assertEquals("摘录应截断到上限（含省略号）",
                AddBookmarkAction.SNIPPET_LENGTH + 1, snippet.length());
        assertTrue("截断后应有省略号", snippet.endsWith("…"));
    }

    @Test
    public void newEntryRejectsSessionWithoutChapterList() {
        ReaderState single = new ReaderState("只有一章", Arrays.asList("正文一", "正文二"));
        assertNull("单章模式没有目录 URL，不能做书签",
                AddBookmarkAction.newEntry(single, "备注", 1L));
        assertNull("null 会话应返回 null", AddBookmarkAction.newEntry(null, "备注", 1L));
    }

    // ---------- 书签 → 章节下标 的还原 ----------

    @Test
    public void resolveTargetChapterPrefersUrlOverStaleIndex() {
        ReaderState state = threeChapterState();
        // 站点改版：原来在目录最后的第 3 章现在排到了最前，存下来的下标 2 已漂移
        BookmarkEntry entry = bookmark(2, 0, "https://a.com/1.html", "改版前存的");

        assertEquals("URL 命中时必须按 URL 定位，而不是存下来的下标",
                0, ShowBookmarksAction.resolveTargetChapter(state, entry));
    }

    @Test
    public void resolveTargetChapterFallsBackToIndexAndClamps() {
        ReaderState state = threeChapterState();
        assertEquals("URL 找不到时按下标",
                2, ShowBookmarksAction.resolveTargetChapter(
                        state, bookmark(2, 0, "https://a.com/gone.html", "x")));
        assertEquals("下标越界应夹到最后",
                2, ShowBookmarksAction.resolveTargetChapter(
                        state, bookmark(99, 0, "https://a.com/gone.html", "x")));
    }

    @Test
    public void resolveTargetChapterHandlesNullInputs() {
        assertEquals("state 为 null 时返回 0",
                0, ShowBookmarksAction.resolveTargetChapter(null, bookmark(1, 0, "u", "x")));
        assertEquals("entry 为 null 时返回 0",
                0, ShowBookmarksAction.resolveTargetChapter(threeChapterState(), null));
    }

    @Test
    public void resolveTargetSegmentClampsWithinCurrentChapter() {
        ReaderState state = threeChapterState();  // 本章共 2 段
        assertEquals("目标就是当前章时，段号按本章段数夹取",
                1, ShowBookmarksAction.resolveTargetSegment(
                        state, bookmark(1, 9, "https://a.com/2.html", "x")));
    }

    @Test
    public void resolveTargetSegmentKeepsRawIndexForOtherChapter() {
        ReaderState state = threeChapterState();
        assertEquals("换章时正文还没抓、段数未知，应原样传给加载流程（抓完再夹）",
                9, ShowBookmarksAction.resolveTargetSegment(
                        state, bookmark(2, 9, "https://a.com/3.html", "x")));
    }

    @Test
    public void resolveTargetSegmentHandlesNullInputs() {
        assertEquals("state 为 null 时返回 0",
                0, ShowBookmarksAction.resolveTargetSegment(null, bookmark(1, 0, "u", "x")));
        assertEquals("entry 为 null 时返回 0",
                0, ShowBookmarksAction.resolveTargetSegment(threeChapterState(), null));
    }

    // ---------- 接线守卫（源码级） ----------

    @Test
    public void bookmarkServiceIsRegisteredInPluginXml() throws Exception {
        String xml = Files.readString(Paths.get("src/main/resources/META-INF/plugin.xml"));

        assertTrue("BookmarkStore 必须注册为应用级服务，否则 getInstance() 拿不到",
                xml.contains("com.novelreader.bookmark.BookmarkStore"));
        for (String id : new String[]{"NovelReader.AddBookmark", "NovelReader.Bookmarks"}) {
            int start = xml.indexOf("id=\"" + id + "\"");
            assertTrue("plugin.xml 应声明动作 " + id, start >= 0);
            String block = xml.substring(start, xml.indexOf("</action>", start));
            assertTrue(id + " 应注册到虚拟子分组",
                    block.contains("group-id=\"NovelReader.MenuItems\""));
            assertTrue(id + " 应有动作类", block.contains("class=\"com.novelreader.action."));
        }
    }

    @Test
    public void bookmarksButtonIsOnTheReadingPanelToolbar() throws Exception {
        String source = Files.readString(
                Paths.get("src/main/java/com/novelreader/reader/ReadingPanel.java"));

        assertTrue("阅读面板工具栏应包含书签入口（否则面板里没法查看书签）",
                source.contains("\"NovelReader.Bookmarks\""));
    }
}
