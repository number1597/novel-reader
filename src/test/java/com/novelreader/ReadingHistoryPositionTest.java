package com.novelreader;

import com.novelreader.history.ReadingHistoryEntry;
import com.novelreader.reader.SessionOpener;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 「回到上次阅读位置」的定位策略。
 *
 * <p>这是整个阅读历史里最容易出错的一环：历史存的是<b>章 / 段下标</b>，
 * 而站点目录随时可能增删章节，直接用旧下标会跳到别的地方。
 * 因此恢复时优先用<b>章节 URL</b> 精确定位，URL 找不到才退回下标。
 */
public class ReadingHistoryPositionTest {

    private static ReadingHistoryEntry entry(int chapterIndex, int segmentIndex, String chapterUrl) {
        ReadingHistoryEntry e = new ReadingHistoryEntry("https://a.com/book/1/", "书");
        e.updateProgress(chapterIndex, segmentIndex, "某章", chapterUrl, 100, 1000L);
        return e;
    }

    // ---------- 章节定位 ----------

    @Test
    public void chapterUrlWinsOverStaleIndex() {
        // 站点在第 2 章前插入了 3 章，原来的下标 5 已经指向别的章
        List<String> urls = List.of(
                "https://a.com/1.html", "https://a.com/2.html", "https://a.com/3.html",
                "https://a.com/4.html", "https://a.com/5.html",
                "https://a.com/target.html", "https://a.com/7.html");
        // 上一章读的是 target.html，当时下标是 5，如今它在第 6 位（下标 5）
        ReadingHistoryEntry e = entry(2, 4, "https://a.com/target.html");

        assertEquals("应按 URL 精确命中，而非用旧下标",
                5, e.resolveChapterIndex(urls));
    }

    @Test
    public void fallsBackToIndexWhenChapterUrlMissingFromNewToc() {
        List<String> urls = List.of(
                "https://a.com/1.html", "https://a.com/2.html", "https://a.com/3.html");
        ReadingHistoryEntry e = entry(2, 0, "https://a.com/已下线.html");

        assertEquals("URL 找不到时退回下标", 2, e.resolveChapterIndex(urls));
    }

    @Test
    public void fallbackIndexIsClampedToTheNewChapterCount() {
        // 站点删章后目录变短，旧下标 40 已越界
        List<String> urls = List.of("https://a.com/1.html", "https://a.com/2.html");
        ReadingHistoryEntry e = entry(40, 0, "https://a.com/已下线.html");

        assertEquals("越界下标应夹到最后章而不是越界", 1, e.resolveChapterIndex(urls));
    }

    @Test
    public void emptyOrNullTocFallsBackToZero() {
        ReadingHistoryEntry e = entry(7, 0, "https://a.com/x.html");

        assertEquals(0, e.resolveChapterIndex(List.of()));
        assertEquals(0, e.resolveChapterIndex(null));
    }

    @Test
    public void noChapterUrlRecordedStillUsesIndex() {
        List<String> urls = List.of("https://a.com/1.html", "https://a.com/2.html",
                "https://a.com/3.html");
        ReadingHistoryEntry e = entry(1, 0, "");

        assertEquals("没记 URL 时按下标定位", 1, e.resolveChapterIndex(urls));
    }

    // ---------- 段落定位 ----------

    @Test
    public void segmentIndexIsClampedToLastSegmentNotFirst() {
        // 正文变短了：原来第 9 段已不存在
        ReadingHistoryEntry e = entry(0, 9, "u");

        assertEquals("越界应退到最后一段，继续阅读不该倒退到开头",
                4, e.resolveSegmentIndex(5));
    }

    @Test
    public void segmentIndexWithinRangeIsKeptExactly() {
        ReadingHistoryEntry e = entry(0, 3, "u");

        assertEquals("合法段号必须原样保留", 3, e.resolveSegmentIndex(10));
    }

    @Test
    public void segmentIndexHandlesEmptyChapter() {
        ReadingHistoryEntry e = entry(0, 5, "u");

        assertEquals(0, e.resolveSegmentIndex(0));
        assertEquals(0, e.resolveSegmentIndex(-1));
    }

    // ---------- SessionOpener 的静态定位工具 ----------

    @Test
    public void openerPrefersUrlMatchThenIndex() {
        List<com.novelreader.model.Chapter> chapters = List.of(
                new com.novelreader.model.Chapter("第1章", "https://a.com/1.html"),
                new com.novelreader.model.Chapter("第2章", "https://a.com/2.html"),
                new com.novelreader.model.Chapter("第3章", "https://a.com/3.html"));

        // URL 命中第 3 章，即便传进来的下标是 0
        assertEquals(2, SessionOpener.resolveChapterIndex(chapters, "https://a.com/3.html", 0));
        // URL 不在目录里，退回下标
        assertEquals(1, SessionOpener.resolveChapterIndex(chapters, "https://a.com/gone.html", 1));
        // 越界下标被夹住
        assertEquals(2, SessionOpener.resolveChapterIndex(chapters, "", 99));
    }

    @Test
    public void openerClampsSegmentSafely() {
        assertEquals(0, SessionOpener.clampSegment(0, 0));
        assertEquals(0, SessionOpener.clampSegment(5, 0));
        assertEquals(0, SessionOpener.clampSegment(-3, 4));
        assertEquals(3, SessionOpener.clampSegment(99, 4));
        assertEquals(2, SessionOpener.clampSegment(2, 4));
    }

    @Test
    public void openerResolvesChapterIndexOnEmptyChapterList() {
        assertEquals(0, SessionOpener.resolveChapterIndex(List.of(), "u", 5));
        assertEquals(0, SessionOpener.resolveChapterIndex(null, "u", 5));
    }

    // ---------- 展示文案 ----------

    @Test
    public void displayLabelShowsBookChapterAndSegment() {
        ReadingHistoryEntry e = new ReadingHistoryEntry("https://a.com/1/", "有钱才能考科举");
        e.updateProgress(11, 2, "第12章 中举", "u", 392, 1000L);

        String label = e.displayLabel();

        assertTrue("应含书名: " + label, label.contains("有钱才能考科举"));
        assertTrue("应含章序号: " + label, label.contains("第 12 章"));
        assertTrue("应含章标题: " + label, label.contains("第12章 中举"));
        assertTrue("应含段序号: " + label, label.contains("第 3 段"));
    }

    @Test
    public void displayLabelFallsBackToUrlWhenTitleMissing() {
        ReadingHistoryEntry e = new ReadingHistoryEntry("https://a.com/1/", "");
        e.updateProgress(0, 0, "", "u", 0, 1000L);

        assertTrue("没有书名时用 URL 兜底", e.displayLabel().contains("https://a.com/1/"));
    }

    @Test
    public void invalidEntryIsRejected() {
        assertTrue(entry(0, 0, "u").isValid());
        assertTrue("没有目录 URL 的条目无效", !new ReadingHistoryEntry("", "x").isValid());
    }

    @Test
    public void sameBookComparesByTocUrl() {
        ReadingHistoryEntry a = new ReadingHistoryEntry("https://a.com/1/", "A");
        ReadingHistoryEntry b = new ReadingHistoryEntry("https://a.com/1/", "另一个书名");
        ReadingHistoryEntry c = new ReadingHistoryEntry("https://b.com/1/", "A");

        assertTrue("同一目录 URL 视为同一本书", a.sameBook(b));
        assertTrue("目录不同则是不同的书", !a.sameBook(c));
    }
}
