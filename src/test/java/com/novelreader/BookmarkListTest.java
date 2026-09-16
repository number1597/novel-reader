package com.novelreader;

import com.novelreader.bookmark.BookmarkEntry;
import com.novelreader.bookmark.BookmarkList;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 书签集合的去重、排序、上限与删除。
 *
 * <p>两条最要紧的不变量：
 * <ul>
 *   <li><b>同一位置不会出现两条</b> —— 否则用户想补备注就会留下一堆重复项；</li>
 *   <li><b>展示顺序按阅读顺序（章 → 段）</b> —— 跳转列表不按位置排就没法用。</li>
 * </ul>
 */
public class BookmarkListTest {

    private static final String BOOK_A = "https://a.com/1/";
    private static final String BOOK_B = "https://b.com/2/";

    private static BookmarkEntry at(String tocUrl, int chapterIndex, int segmentIndex) {
        BookmarkEntry entry = new BookmarkEntry();
        entry.tocUrl = tocUrl;
        entry.chapterIndex = chapterIndex;
        entry.segmentIndex = segmentIndex;
        entry.chapterUrl = tocUrl + (chapterIndex + 1) + ".html";
        entry.chapterTitle = "第" + (chapterIndex + 1) + "章";
        entry.createdAt = 1000L + chapterIndex * 10 + segmentIndex;
        return entry;
    }

    @Test
    public void addStoresEntry() {
        BookmarkList list = new BookmarkList(null);
        BookmarkEntry saved = list.add(at(BOOK_A, 0, 0));

        assertNotNull("有效书签应能加入", saved);
        assertEquals("加入后应有 1 条", 1, list.size());
        assertEquals("返回的是副本，内容应一致", list.snapshot().get(0).key(), saved.key());
    }

    @Test
    public void samePositionIsDeduplicated() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 3, 1));
        list.add(at(BOOK_A, 3, 1));

        assertEquals("同一位置再次添加不应新增条目", 1, list.size());
    }

    @Test
    public void readdingUpdatesNoteAndSnippet() {
        BookmarkList list = new BookmarkList(null);
        BookmarkEntry first = at(BOOK_A, 3, 1);
        list.add(first);

        BookmarkEntry again = at(BOOK_A, 3, 1);
        again.note = "补充的备注";
        again.snippet = "大周朝永和三年";
        list.add(again);

        BookmarkEntry stored = list.snapshot().get(0);
        assertEquals("备注应被更新", "补充的备注", stored.getNote());
        assertEquals("摘录应被更新", "大周朝永和三年", stored.getSnippet());
    }

    @Test
    public void readdingWithEmptyNoteKeepsExistingNote() {
        BookmarkList list = new BookmarkList(null);
        BookmarkEntry first = at(BOOK_A, 3, 1);
        first.note = "重要伏笔";
        list.add(first);

        // 用户重复点了一次「添加书签」但没填备注：不该把已有备注清掉
        list.add(at(BOOK_A, 3, 1));

        assertEquals("重复添加且不填备注时，原备注必须保留", "重要伏笔",
                list.snapshot().get(0).getNote());
    }

    @Test
    public void reAddKeepsOriginalCreatedAt() {
        BookmarkList list = new BookmarkList(null);
        BookmarkEntry first = at(BOOK_A, 0, 0);
        first.createdAt = 111L;
        list.add(first);

        BookmarkEntry again = at(BOOK_A, 0, 0);
        again.createdAt = 999L;
        list.add(again);

        assertEquals("创建时间保留「第一次标记」的语义", 111L,
                list.snapshot().get(0).getCreatedAt());
    }

    @Test
    public void invalidEntryIsRejected() {
        BookmarkList list = new BookmarkList(null);
        assertNull("没有目录 URL 的书签不该被接受", list.add(new BookmarkEntry()));
        assertNull("null 不该被接受", list.add(null));
        assertEquals("被拒绝的条目不应留在列表里", 0, list.size());
    }

    @Test
    public void forBookFiltersAndSortsByPosition() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 11, 3));
        list.add(at(BOOK_B, 0, 0));
        list.add(at(BOOK_A, 2, 5));
        list.add(at(BOOK_A, 2, 1));

        List<BookmarkEntry> forA = list.forBook(BOOK_A);
        assertEquals("只应含这本书的书签", 3, forA.size());
        assertEquals("应按章升序：先第 3 章", 2, forA.get(0).getChapterIndex());
        assertEquals("同章内按段升序：第 2 段在前", 1, forA.get(0).getSegmentIndex());
        assertEquals("同章同段顺序不能乱", 5, forA.get(1).getSegmentIndex());
        assertEquals("最后是第 12 章", 11, forA.get(2).getChapterIndex());
    }

    @Test
    public void forBookOfUnknownBookIsEmpty() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 0, 0));
        assertTrue("没有书签的书应返回空列表", list.forBook(BOOK_B).isEmpty());
        assertTrue("空 URL 应返回空列表", list.forBook("").isEmpty());
        assertTrue("null 应返回空列表", list.forBook(null).isEmpty());
    }

    @Test
    public void countForBookCountsOnlyThatBook() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 0, 0));
        list.add(at(BOOK_A, 1, 0));
        list.add(at(BOOK_B, 0, 0));

        assertEquals("只数这本书", 2, list.countForBook(BOOK_A));
        assertEquals("不存在的书为 0", 0, list.countForBook("https://c.com/"));
    }

    @Test
    public void removeByKeyDeletesOnlyThatOne() {
        BookmarkList list = new BookmarkList(null);
        BookmarkEntry a = list.add(at(BOOK_A, 0, 0));
        list.add(at(BOOK_A, 1, 0));

        assertTrue("删除应成功", list.remove(a.key()));
        assertEquals("只应删掉一条", 1, list.size());
        assertEquals("剩下的是另一条", 1, list.snapshot().get(0).getChapterIndex());
    }

    @Test
    public void removeUnknownKeyReturnsFalse() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 0, 0));
        assertFalse("删不存在的 key 不该成功", list.remove("nope"));
        assertFalse("空 key 不该成功", list.remove(""));
        assertFalse("null key 不该成功", list.remove(null));
        assertEquals("列表不应被误删", 1, list.size());
    }

    @Test
    public void clearEmptiesList() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 0, 0));
        list.clear();
        assertTrue("清空后应为空", list.isEmpty());
    }

    @Test
    public void trimDropsOldestWhenOverLimit() {
        BookmarkList list = new BookmarkList(null);
        int total = BookmarkList.MAX_ENTRIES + 5;
        for (int i = 0; i < total; i++) {
            // 每条的章节都不同，避免被去重；createdAt 递增，最旧的应被淘汰
            BookmarkEntry entry = at(BOOK_A, i, 0);
            entry.createdAt = i;
            list.add(entry);
        }
        assertEquals("应被裁到上限", BookmarkList.MAX_ENTRIES, list.size());

        List<Integer> remaining = new ArrayList<>();
        for (BookmarkEntry entry : list.snapshot()) {
            remaining.add(entry.getChapterIndex());
        }
        assertFalse("最旧的几条应被淘汰", remaining.contains(0));
        assertFalse("最旧的几条应被淘汰", remaining.contains(4));
        assertTrue("最新的应保留", remaining.contains(total - 1));
    }

    @Test
    public void snapshotKeepsCreationOrder() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 5, 0));
        list.add(at(BOOK_A, 1, 0));

        List<BookmarkEntry> snapshot = list.snapshot();
        assertEquals("落盘顺序按创建顺序，便于用户手看 JSON", 5,
                snapshot.get(0).getChapterIndex());
        assertEquals("落盘顺序按创建顺序", 1, snapshot.get(1).getChapterIndex());
    }

    @Test
    public void snapshotIsDefensiveCopy() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 0, 0));

        List<BookmarkEntry> snapshot = list.snapshot();
        snapshot.get(0).chapterIndex = 99;

        assertEquals("改快照不该影响内部状态", 0, list.snapshot().get(0).getChapterIndex());
    }

    @Test
    public void constructorSkipsNullAndInvalidEntries() {
        BookmarkEntry valid = at(BOOK_A, 0, 0);
        BookmarkList list = new BookmarkList(Arrays.asList(null, new BookmarkEntry(), valid));
        assertEquals("构造时只保留有效条目", 1, list.size());
    }

    @Test
    public void constructorDeduplicatesLoadedDuplicates() {
        // 手改 JSON 可能造成重复；加载时应自行收敛，而不是把重复带进内存
        List<BookmarkEntry> loaded = Arrays.asList(at(BOOK_A, 0, 0), at(BOOK_A, 0, 0));
        BookmarkList list = new BookmarkList(loaded);
        assertEquals("加载时同位置应合并为一条", 1, list.size());
    }

    @Test
    public void differentSegmentsInSameChapterAreDistinct() {
        BookmarkList list = new BookmarkList(null);
        list.add(at(BOOK_A, 0, 0));
        list.add(at(BOOK_A, 0, 1));
        assertEquals("同章不同段是两条书签", 2, list.size());
    }
}
