package com.novelreader;

import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.PaginationSplitter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 阅读会话的跨章导航逻辑。
 *
 * <p>{@link ReaderState} 被刻意设计成纯内存状态，所以这里不需要启动 IntelliJ 平台，
 * 也不需要起 HTTP 服务，就能把「自动续读下一章」的游标行为完整覆盖。
 */
public class ReaderStateTest {

    private static List<Chapter> toc(int count) {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            chapters.add(new Chapter("第" + i + "章", "https://example.com/ch" + i));
        }
        return chapters;
    }

    private static List<String> segments(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    // ---------- 构造 ----------

    @Test
    public void singleChapterConstructorHasNoChapterList() {
        ReaderState state = new ReaderState("单章", segments("甲", "乙"));

        assertFalse(state.hasChapterList());
        assertEquals(0, state.getChapterCount());
        assertEquals(0, state.getChapterNumber());
        assertFalse("没有目录时不应能翻到下一章", state.hasNextChapter());
        assertFalse("没有目录时不应能翻到上一章", state.hasPrevChapter());
        assertNull(state.getCurrentChapter());
    }

    @Test
    public void fullConstructorKeepsChapterListAndCursorAtStart() {
        ReaderState state = new ReaderState(
                toc(3), 1, "第2章", segments("甲", "乙", "丙"), null, "https://example.com/toc");

        assertTrue(state.hasChapterList());
        assertEquals(3, state.getChapterCount());
        assertEquals(1, state.getChapterIndex());
        assertEquals(2, state.getChapterNumber());
        assertEquals("第2章", state.getChapterTitle());
        assertEquals(0, state.getIndex());
        assertEquals("https://example.com/toc", state.getTocUrl());
        assertEquals("第2章", state.getCurrentChapter().getTitle());
    }

    @Test
    public void chapterIndexIsClampedIntoValidRange() {
        assertEquals(0, new ReaderState(toc(3), -5, "x", segments("a"), null, "").getChapterIndex());
        assertEquals(2, new ReaderState(toc(3), 99, "x", segments("a"), null, "").getChapterIndex());
    }

    @Test
    public void outOfRangeChapterLookupReturnsNull() {
        ReaderState state = new ReaderState(toc(2), 0, "第1章", segments("a"), null, "");

        assertNull(state.getChapterAt(-1));
        assertNull(state.getChapterAt(2));
        assertEquals("第1章", state.getChapterAt(0).getTitle());
    }

    // ---------- 能否翻章 ----------

    @Test
    public void hasNextChapterIsFalseOnLastChapter() {
        ReaderState first = new ReaderState(toc(3), 0, "第1章", segments("a"), null, "");
        ReaderState middle = new ReaderState(toc(3), 1, "第2章", segments("a"), null, "");
        ReaderState last = new ReaderState(toc(3), 2, "第3章", segments("a"), null, "");

        assertTrue(first.hasNextChapter());
        assertFalse(first.hasPrevChapter());
        assertTrue(middle.hasNextChapter());
        assertTrue(middle.hasPrevChapter());
        assertFalse("最后一章不应再有下一章", last.hasNextChapter());
        assertTrue(last.hasPrevChapter());
    }

    @Test
    public void singleChapterHasNeitherNextNorPrevChapter() {
        ReaderState state = new ReaderState("单章", segments("a"));

        assertFalse(state.hasNextChapter());
        assertFalse(state.hasPrevChapter());
    }

    // ---------- 换章 ----------

    @Test
    public void applyChapterReplacesContentAndResetsCursorToFirstSegment() {
        ReaderState state = new ReaderState(toc(3), 0, "第1章", segments("甲1", "甲2", "甲3"), null, "");
        state.moveToLastSegment();
        assertEquals(2, state.getIndex());

        boolean applied = state.applyChapter(1, "第2章", segments("乙1", "乙2"));

        assertTrue(applied);
        assertEquals(1, state.getChapterIndex());
        assertEquals("第2章", state.getChapterTitle());
        assertEquals(2, state.getTotal());
        assertEquals("游标应复位到第 1 段", 0, state.getIndex());
        assertEquals("乙1", state.currentSegment());
        assertTrue("光标在第 1 段，本章后面还有 1 段", state.hasNext());
        assertFalse("刚进入新章，不应有上一段", state.hasPrev());
    }

    @Test
    public void applyChapterRejectsOutOfRangeIndex() {
        ReaderState state = new ReaderState(toc(2), 0, "第1章", segments("甲"), null, "");

        assertFalse(state.applyChapter(5, "越界", segments("乙")));
        assertFalse(state.applyChapter(-1, "越界", segments("乙")));
        assertEquals("被拒绝后应保持原状", "第1章", state.getChapterTitle());
        assertEquals("甲", state.currentSegment());
    }

    @Test
    public void applyChapterRejectsEmptyOrNullSegments() {
        ReaderState state = new ReaderState(toc(2), 0, "第1章", segments("甲"), null, "");

        assertFalse(state.applyChapter(1, "第2章", Collections.emptyList()));
        assertFalse(state.applyChapter(1, "第2章", null));
        assertEquals("被拒绝后应保持原状", "第1章", state.getChapterTitle());
    }

    @Test
    public void applyChapterWorksInSingleChapterModeWithoutChapterList() {
        ReaderState state = new ReaderState("单章", segments("甲"));

        // 没有目录时下标被强制为 0，但仍可替换内容（例如手动重新抓取）
        assertTrue(state.applyChapter(0, "单章·新", segments("乙", "丙")));
        assertEquals(0, state.getChapterIndex());
        assertEquals("乙", state.currentSegment());
    }

    // ---------- 落点控制 ----------

    @Test
    public void moveToLastSegmentJumpsToTailAndBack() {
        ReaderState state = new ReaderState(toc(2), 1, "第2章", segments("甲", "乙", "丙"), null, "");

        state.moveToLastSegment();
        assertEquals(2, state.getIndex());
        assertEquals("丙", state.currentSegment());
        assertFalse(state.hasNext());
        assertTrue(state.hasPrev());

        state.moveToFirstSegment();
        assertEquals(0, state.getIndex());
        assertEquals("甲", state.currentSegment());
    }

    @Test
    public void moveToLastSegmentOnEmptyChapterIsNoOp() {
        ReaderState state = new ReaderState(toc(2), 0, "第1章", Collections.emptyList(), null, "");

        state.moveToLastSegment();

        assertEquals(0, state.getIndex());
        assertTrue(state.isEmpty());
    }

    // ---------- 端到端游标走位 ----------

    /**
     * 模拟「读完一章自动进入下一章」的完整走位：
     * 第 1 章 2 段读完后切换到第 2 章，应从第 2 章第 1 段重新开始。
     */
    @Test
    public void readingThroughChapterThenAdvancingStartsAtNextChapterFirstSegment() {
        ReaderState state = new ReaderState(
                toc(2), 0, "第1章", segments("甲1", "甲2"), null, "https://example.com/toc");

        assertTrue(state.next());
        assertEquals("甲2", state.currentSegment());
        assertFalse("本章已读完", state.next());
        assertTrue("应能自动进入下一章", state.hasNextChapter());

        // ReaderManager 拿到 hasNextChapter 后抓取下一章，落到第 1 段
        assertTrue(state.applyChapter(1, "第2章", segments("乙1", "乙2", "乙3")));
        assertEquals("乙1", state.currentSegment());
        assertEquals(1, state.getChapterIndex());
        assertFalse("刚进入新章，不应有上一段", state.hasPrev());
    }

    /**
     * 模拟「在第 1 章开头往回翻」：应回到上一章的<b>最后一段</b>，保证连续阅读不断档。
     */
    @Test
    public void goingBackFromChapterStartLandsOnPreviousChapterLastSegment() {
        ReaderState state = new ReaderState(
                toc(2), 1, "第2章", segments("乙1", "乙2"), null, "");

        assertFalse("已在本章第 1 段", state.prev());
        assertTrue("应能回到上一章", state.hasPrevChapter());

        // ReaderManager 用 Landing.LAST 回到上一章末段
        assertTrue(state.applyChapter(0, "第1章", segments("甲1", "甲2", "甲3")));
        state.moveToLastSegment();

        assertEquals("甲3", state.currentSegment());
        assertEquals("落点应是上一章最后一段", 2, state.getIndex());
        assertTrue("上一章末段之后还有新章可进", state.hasNextChapter());
    }

    @Test
    public void ruleAndTocUrlAreRetainedForLaterChapterLoads() {
        NovelRule rule = new NovelRule();
        rule.ruleName = "测试规则";
        ReaderState state = new ReaderState(toc(2), 0, "第1章", segments("甲"), rule, "https://example.com/toc");

        assertEquals("必须保留规则，否则无法抓取后续章节", rule, state.getRule());
        assertEquals("测试规则", state.getRule().getRuleName());
        assertEquals("https://example.com/toc", state.getTocUrl());
    }

    // ---------- 整章正文（阅读面板整章显示的来源） ----------

    @Test
    public void fullTextConcatenatesSegmentsWithoutSeparators() {
        // 切分是无损的，拼回来必须还是原文 —— 否则面板里读到的字和站点不一致，
        // 而这种偏差肉眼很难发现，只会在复制粘贴时才暴露
        ReaderState state = new ReaderState("标题", segments("aaa", "bbb"));

        assertEquals("无损拼接：不能凭空插入分隔符", "aaabbb", state.getFullText());
    }

    @Test
    public void fullTextSkipsEmptyAndNullSegments() {
        ReaderState state = new ReaderState("标题", Arrays.asList("aa", "", null, "b"));

        assertEquals("空段与 null 段都不该出现在正文里", "aab", state.getFullText());
    }

    @Test
    public void fullTextOfEmptyChapterIsEmpty() {
        assertEquals("", new ReaderState("标题", new ArrayList<>()).getFullText());
    }

    @Test
    public void fullTextFollowsChapterSwitch() {
        ReaderState state = new ReaderState(toc(2), 0, "第1章", segments("甲"), null, "u");
        assertEquals("甲", state.getFullText());

        state.applyChapter(1, "第2章", segments("乙", "丙"));

        assertEquals("换章后正文必须跟着换，否则面板会停在上一次的文本上",
                "乙丙", state.getFullText());
    }

    @Test
    public void fullTextRestoresExactlyWhatPaginationSplitWasGiven() {
        // 与切分器的往返：拆开再拼回来必须一字不差
        String body = "第一句。第二句，第三句；第四句！\n\n第五段。";
        List<String> parts = PaginationSplitter.split(body, 5);

        assertTrue("前提：正文确实被切成了多段", parts.size() > 1);
        assertEquals("切分再拼回必须还原原文",
                body, new ReaderState("标题", parts).getFullText());
    }
}
