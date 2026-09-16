package com.novelreader;

import com.novelreader.bookmark.BookmarkEntry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 单条书签的纯逻辑：位置标识、展示文案、摘录提取。
 *
 * <p>重点是 {@link BookmarkEntry#key()} —— 它决定「同一个位置再次添加会不会变两条」，
 * 一旦章节那一段算错，用户补备注就会留下重复书签。
 */
public class BookmarkEntryTest {

    private static BookmarkEntry entry(String tocUrl, int chapterIndex, int segmentIndex,
                                       String chapterUrl) {
        BookmarkEntry entry = new BookmarkEntry();
        entry.tocUrl = tocUrl;
        entry.chapterIndex = chapterIndex;
        entry.segmentIndex = segmentIndex;
        entry.chapterUrl = chapterUrl;
        return entry;
    }

    // ---------- 位置标识 ----------

    @Test
    public void keyPrefersChapterUrlOverIndex() {
        BookmarkEntry a = entry("https://a.com/book/", 11, 2, "https://a.com/book/12.html");
        BookmarkEntry b = entry("https://a.com/book/", 3, 2, "https://a.com/book/12.html");
        assertEquals("同一章 URL 的不同下标应视为同一位置（站点改版后下标会漂移）",
                a.key(), b.key());
    }

    @Test
    public void keyFallsBackToIndexWhenUrlMissing() {
        BookmarkEntry a = entry("https://a.com/book/", 11, 2, "");
        BookmarkEntry b = entry("https://a.com/book/", 11, 2, "");
        BookmarkEntry other = entry("https://a.com/book/", 11, 3, "");
        assertEquals("没有章节 URL 时按下标构成 key", a.key(), b.key());
        assertNotEquals("不同段必须是不同的 key", a.key(), other.key());
    }

    @Test
    public void keyDiffersAcrossBooks() {
        BookmarkEntry a = entry("https://a.com/1/", 0, 0, "https://a.com/1/1.html");
        BookmarkEntry b = entry("https://b.com/2/", 0, 0, "https://b.com/1/1.html");
        assertNotEquals("不同书即使章 / 段相同也不能算同一位置", a.key(), b.key());
    }

    @Test
    public void samePositionRequiresSameBook() {
        BookmarkEntry a = entry("https://a.com/1/", 0, 1, "https://a.com/1/1.html");
        BookmarkEntry b = entry("https://a.com/1/", 0, 1, "https://a.com/1/1.html");
        BookmarkEntry c = entry("https://b.com/2/", 0, 1, "https://a.com/1/1.html");
        assertTrue("同书同位置应为同一位置", a.samePosition(b));
        assertFalse("换一本书就不是同一位置", a.samePosition(c));
        assertFalse("null 不应抛异常", a.samePosition(null));
    }

    @Test
    public void entryWithoutTocUrlIsInvalid() {
        BookmarkEntry blank = new BookmarkEntry();
        assertFalse("没有目录 URL 无法确定属于哪本书，不算有效书签", blank.isValid());
        assertTrue("有目录 URL 即有效",
                entry("https://a.com/1/", 0, 0, "").isValid());
    }

    @Test
    public void invalidEntryNeverMatchesAnything() {
        BookmarkEntry blank = new BookmarkEntry();
        BookmarkEntry real = entry("https://a.com/1/", 0, 0, "https://a.com/1/1.html");
        assertFalse("空书签不应与任何书签算同一位置", blank.samePosition(real));
    }

    // ---------- 展示文案 ----------

    @Test
    public void labelContainsNotePositionAndSnippet() {
        BookmarkEntry entry = entry("https://a.com/1/", 11, 2, "https://a.com/1/12.html");
        entry.chapterTitle = "中举";
        entry.note = "伏笔";
        entry.snippet = "大周朝永和三年";

        String label = entry.displayLabel();
        assertTrue("应含备注：" + label, label.contains("伏笔"));
        assertTrue("章节序号从 1 开始：" + label, label.contains("第 12 章"));
        assertTrue("应含章节标题：" + label, label.contains("中举"));
        assertTrue("段号从 1 开始：" + label, label.contains("第 3 段"));
        assertTrue("应含摘录：" + label, label.contains("大周朝永和三年"));
    }

    @Test
    public void labelOmitsMissingPieces() {
        BookmarkEntry entry = entry("https://a.com/1/", 0, 0, "");
        String label = entry.displayLabel();
        assertEquals("没有备注 / 标题 / 摘录时只显示位置", "第 1 章  ·  第 1 段", label);
    }

    @Test
    public void firstChapterFirstSegmentIsOneBased() {
        BookmarkEntry entry = entry("https://a.com/1/", 0, 0, "");
        assertTrue("第 1 章不应显示成第 0 章：" + entry.displayLabel(),
                entry.displayLabel().contains("第 1 章"));
        assertTrue("第 1 段不应显示成第 0 段：" + entry.displayLabel(),
                entry.displayLabel().contains("第 1 段"));
    }

    // ---------- 摘录提取 ----------

    @Test
    public void snippetCollapsesWhitespace() {
        // 注意口径：是「每一段连续空白各压成一个空格」，不是「把所有空白全删掉」——
        // 输入里有两段空白（换行、以及三个空格），所以结果里应有两个单空格
        assertEquals("换行与连续空白应压成单个空格（列表是单行展示）",
                "大周 永和 三年", BookmarkEntry.snippet("大周\n\n永和   三年", 40));
        assertEquals("制表符同样算空白", "A B",
                BookmarkEntry.snippet("A\t\n B", 40));
    }

    @Test
    public void snippetTruncatesWithEllipsis() {
        assertEquals("超长应截断并加省略号", "一二三…", BookmarkEntry.snippet("一二三四五六", 3));
        assertEquals("正好等于上限时不截断", "一二三", BookmarkEntry.snippet("一二三", 3));
    }

    @Test
    public void snippetHandlesNullOrBlank() {
        assertEquals("null 返回空串", "", BookmarkEntry.snippet(null, 10));
        assertEquals("纯空白返回空串", "", BookmarkEntry.snippet("   \n\t ", 10));
        assertEquals("maxChars<=0 视为不截断", "一二三四", BookmarkEntry.snippet("一二三四", 0));
    }

    // ---------- 拷贝 ----------

    @Test
    public void copyIsDeep() {
        BookmarkEntry entry = entry("https://a.com/1/", 3, 4, "https://a.com/1/4.html");
        entry.note = "原备注";
        BookmarkEntry copy = entry.copy();
        copy.note = "改过的";
        copy.chapterIndex = 99;

        assertEquals("改副本不该影响原对象（note）", "原备注", entry.getNote());
        assertEquals("改副本不该影响原对象（chapterIndex）", 3, entry.getChapterIndex());
    }
}
