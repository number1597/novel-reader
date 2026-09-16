package com.novelreader;

import com.novelreader.history.ReadingHistory;
import com.novelreader.history.ReadingHistoryEntry;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 阅读历史的列表逻辑：去重、排序、上限、删除。
 *
 * <p>这些规则如果写错，现象是「列表里出现两本一样的书」「刚读的排到了后面」
 * 「删掉一本结果少了另一本」，从界面很难反推原因，因此在这里逐个钉死。
 */
public class ReadingHistoryTest {

    private static ReadingHistoryEntry entry(String tocUrl, String title, long at) {
        ReadingHistoryEntry e = new ReadingHistoryEntry(tocUrl, title);
        e.updateProgress(0, 0, "第1章", tocUrl + "1.html", 10, at);
        return e;
    }

    @Test
    public void sameBookIsRecordedOnceAndMovedToTop() {
        ReadingHistory history = ReadingHistory.empty();

        history.record("https://a.com/book/1/", "A书", 0, 0, "第1章", "u1", 10, "r", 100L);
        history.record("https://b.com/book/1/", "B书", 0, 0, "第1章", "u2", 20, "r", 200L);
        // 再读一次 A 书：不应新增条目，而应更新进度并顶到最前
        history.record("https://a.com/book/1/", "A书", 3, 5, "第4章", "u4", 10, "r", 300L);

        assertEquals("同一个目录 URL 只能有一条历史", 2, history.size());
        List<ReadingHistoryEntry> snapshot = history.snapshot();
        assertEquals("最近读的应排在第一位", "https://a.com/book/1/", snapshot.get(0).getTocUrl());
        assertEquals("进度应被更新为最新", 3, snapshot.get(0).getChapterIndex());
        assertEquals("段游标应被更新为最新", 5, snapshot.get(0).getSegmentIndex());
        assertEquals("章节标题应被更新", "第4章", snapshot.get(0).getChapterTitle());
    }

    @Test
    public void snapshotIsOrderedByLastReadTimeDescending() {
        ReadingHistory history = new ReadingHistory(List.of(
                entry("https://a.com/1/", "A", 100L),
                entry("https://b.com/1/", "B", 300L),
                entry("https://c.com/1/", "C", 200L)));

        List<ReadingHistoryEntry> snapshot = history.snapshot();
        assertEquals("https://b.com/1/", snapshot.get(0).getTocUrl());
        assertEquals("https://c.com/1/", snapshot.get(1).getTocUrl());
        assertEquals("https://a.com/1/", snapshot.get(2).getTocUrl());
    }

    @Test
    public void snapshotIsADefensiveCopy() {
        ReadingHistory history = ReadingHistory.empty();
        history.record("https://a.com/1/", "A", 0, 0, "第1章", "u", 5, "r", 100L);

        List<ReadingHistoryEntry> first = history.snapshot();
        first.get(0).tocUrl = "被外部改坏";

        assertEquals("外部修改快照不应影响内部状态",
                "https://a.com/1/", history.snapshot().get(0).getTocUrl());
    }

    @Test
    public void removeDropsOnlyTheTargetBook() {
        ReadingHistory history = ReadingHistory.empty();
        history.record("https://a.com/1/", "A", 0, 0, "第1章", "u", 5, "r", 100L);
        history.record("https://b.com/1/", "B", 0, 0, "第1章", "u", 5, "r", 200L);

        assertTrue(history.remove("https://a.com/1/"));

        assertEquals(1, history.size());
        assertNull("被删的书应查不到", history.find("https://a.com/1/"));
        assertNotNull("不该误删其它书", history.find("https://b.com/1/"));
    }

    @Test
    public void removeMissingBookReturnsFalse() {
        ReadingHistory history = ReadingHistory.empty();
        history.record("https://a.com/1/", "A", 0, 0, "第1章", "u", 5, "r", 100L);

        assertFalse(history.remove("https://nope.com/1/"));
        assertFalse("空 URL 不应误删任何东西", history.remove(""));
        assertFalse("null 不应误删任何东西", history.remove(null));
        assertEquals(1, history.size());
    }

    @Test
    public void removeAtWorksByListPosition() {
        ReadingHistory history = ReadingHistory.empty();
        history.record("https://a.com/1/", "A", 0, 0, "第1章", "u", 5, "r", 100L);
        history.record("https://b.com/1/", "B", 0, 0, "第1章", "u", 5, "r", 200L);

        // 列表按时间倒序：position 0 是 B
        assertTrue(history.removeAt(0));
        assertEquals("https://a.com/1/", history.snapshot().get(0).getTocUrl());

        assertFalse("越界下标不应删东西", history.removeAt(99));
        assertFalse("负数下标不应删东西", history.removeAt(-1));
    }

    @Test
    public void entriesBeyondLimitAreTrimmedFromTheTail() {
        ReadingHistory history = ReadingHistory.empty();
        // 写入超过上限的条目，时间递增确保顺序可控
        for (int i = 0; i < ReadingHistory.MAX_ENTRIES + 5; i++) {
            history.record("https://s" + i + ".com/1/", "书" + i,
                    0, 0, "第1章", "u" + i, 5, "r", 1000L + i);
        }

        assertEquals("历史条数应被裁到上限", ReadingHistory.MAX_ENTRIES, history.size());

        List<ReadingHistoryEntry> snapshot = history.snapshot();
        // 最新的一本必须还在
        assertEquals("https://s" + (ReadingHistory.MAX_ENTRIES + 4) + ".com/1/",
                snapshot.get(0).getTocUrl());
        // 最老的几本应已被丢弃
        assertNull("最久未读的应被裁掉", history.find("https://s0.com/1/"));
    }

    @Test
    public void invalidEntriesAreIgnoredOnConstruction() {
        ReadingHistoryEntry noUrl = new ReadingHistoryEntry("", "没有URL");
        ReadingHistory history = new ReadingHistory(List.of(noUrl, entry("https://a.com/1/", "A", 100L)));

        assertEquals("没有目录 URL 的条目无法作为书签，应被忽略", 1, history.size());
        assertNotNull(history.find("https://a.com/1/"));
    }

    @Test
    public void recordRejectsEmptyTocUrl() {
        ReadingHistory history = ReadingHistory.empty();

        assertNull(history.record("", "X", 0, 0, "第1章", "u", 5, "r", 100L));
        assertNull(history.record(null, "X", 0, 0, "第1章", "u", 5, "r", 100L));
        assertTrue("空 URL 不该产生历史", history.isEmpty());
    }

    @Test
    public void titleIsNotOverwrittenByLaterEmptyTitle() {
        ReadingHistory history = ReadingHistory.empty();
        history.record("https://a.com/1/", "原始书名", 0, 0, "第1章", "u", 5, "r", 100L);
        // 后续更新拿不到书名时，已有书名不该被清空
        history.record("https://a.com/1/", "", 1, 0, "第2章", "u", 5, "r", 200L);

        assertEquals("原始书名", history.find("https://a.com/1/").getTitle());
    }
}
