package com.novelreader;

import com.novelreader.action.ShowChapterListAction;
import com.novelreader.model.Chapter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 章节目录的「筛选 / 定位」纯逻辑。
 *
 * <p>这两个功能的错误都表现为「跳到了别的章」——用户很难描述清楚，也很难复现，
 * 所以在这里钉死：
 * <ul>
 *   <li><b>筛选返回的是真实章节下标</b>，不是过滤后的位置。一旦错位，
 *       在过滤结果里点第 1 条会跳到某个完全无关的章节；</li>
 *   <li><b>打开目录时默认选中当前章</b>，当前章被过滤掉时才退回第 1 条。</li>
 * </ul>
 */
public class ChapterListFilterTest {

    private static List<Chapter> chapters(int count) {
        List<Chapter> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(new Chapter("第" + i + "章 标题" + i, "https://example.com/ch" + i));
        }
        return list;
    }

    /** 用标题里带关键字的一小撮章节，方便断言命中集合。 */
    private static List<Chapter> mixed() {
        return Arrays.asList(
                new Chapter("第1章 风起", "u1"),
                new Chapter("第2章 雨落", "u2"),
                new Chapter("第3章 风再起", "u3"),
                new Chapter("第4章 雪", "u4"),
                new Chapter("第5章 风停", "u5"));
    }

    // ---------- 展示文案 ----------

    @Test
    public void labelUsesOneBasedNumber() {
        // 序号从 1 开始、下标从 0 开始，这是最容易写反的地方
        assertEquals("1. 第1章 标题1", ShowChapterListAction.label(0, chapters(1).get(0)));
        assertEquals("3. 第3章 标题3", ShowChapterListAction.label(2, chapters(3).get(2)));
    }

    @Test
    public void labelToleratesMissingTitle() {
        assertEquals("7. ", ShowChapterListAction.label(6, new Chapter(null, "u")));
        assertEquals("1. ", ShowChapterListAction.label(0, null));
    }

    // ---------- 过滤 ----------

    @Test
    public void blankQueryReturnsEveryChapter() {
        List<Chapter> all = chapters(5);

        assertEquals(5, ShowChapterListAction.filterChapterIndices(all, null).size());
        assertEquals(5, ShowChapterListAction.filterChapterIndices(all, "").size());
        assertEquals(5, ShowChapterListAction.filterChapterIndices(all, "   ").size());
    }

    @Test
    public void queryMatchesChapterTitle() {
        List<Integer> hit = ShowChapterListAction.filterChapterIndices(mixed(), "风");

        assertEquals("第1/3/5 章标题含「风」", Arrays.asList(0, 2, 4), hit);
    }

    @Test
    public void queryMatchesChapterNumber() {
        List<Integer> hit = ShowChapterListAction.filterChapterIndices(chapters(20), "7");

        assertTrue("应命中第 7 章（下标 6）", hit.contains(6));
        // 同时也应命中 17（下标 16）这类"包含 7"的章号
        assertTrue("「7」作为子串也应命中第 17 章", hit.contains(16));
    }

    @Test
    public void exactChapterNumberComesFirst() {
        // 搜 "2" 时，若把 "12"、"20" 排在前面，想跳第 2 章反而要往下找
        List<Integer> hit = ShowChapterListAction.filterChapterIndices(chapters(30), "2");

        assertEquals("章号正好相等的那一章必须排第一", Integer.valueOf(1), hit.get(0));
        assertTrue("其余命中项也不能丢", hit.size() > 1);
    }

    @Test
    public void returnedIndicesPointAtMatchingChapters() {
        List<Chapter> all = mixed();

        List<Integer> hit = ShowChapterListAction.filterChapterIndices(all, "雪");

        assertEquals(1, hit.size());
        int index = hit.get(0);
        // 关键不变量：返回的下标拿回原列表，必须真的是那一章（防错位）
        assertEquals("第4章 雪", all.get(index).getTitle());
        assertEquals(3, index);
    }

    @Test
    public void noMatchYieldsEmptyList() {
        assertTrue(ShowChapterListAction.filterChapterIndices(mixed(), "不存在的关键字").isEmpty());
    }

    @Test
    public void filteringHandlesEmptyAndNullInput() {
        assertTrue(ShowChapterListAction.filterChapterIndices(null, "x").isEmpty());
        assertTrue(ShowChapterListAction.filterChapterIndices(new ArrayList<>(), "x").isEmpty());
    }

    @Test
    public void filterIsCaseInsensitive() {
        List<Chapter> all = List.of(new Chapter("Chapter One Alpha", "u"));
        assertEquals(1, ShowChapterListAction.filterChapterIndices(all, "alpha").size());
        assertEquals(1, ShowChapterListAction.filterChapterIndices(all, "ALPHA").size());
    }

    @Test
    public void filterPreservesOriginalOrder() {
        List<Integer> hit = ShowChapterListAction.filterChapterIndices(chapters(392), "1");

        for (int i = 1; i < hit.size(); i++) {
            assertTrue("过滤结果必须保持原有先后顺序", hit.get(i) > hit.get(i - 1));
        }
    }

    // ---------- 打开时定位到当前章 ----------

    @Test
    public void selectionLandsOnCurrentChapter() {
        List<Integer> visible = Arrays.asList(0, 1, 2, 3, 4);

        assertEquals("当前是第 3 章（下标 2）→ 应选中第 3 项",
                2, ShowChapterListAction.selectionPositionFor(visible, 2));
        assertEquals("第 1 章时选中第 1 项",
                0, ShowChapterListAction.selectionPositionFor(visible, 0));
        assertEquals("最后一章时选中最后一项",
                4, ShowChapterListAction.selectionPositionFor(visible, 4));
    }

    @Test
    public void selectionPositionsCorrectlyInsideFilteredList() {
        // 过滤后只剩 [10, 200, 391]，当前是第 201 章（下标 200）
        List<Integer> visible = Arrays.asList(10, 200, 391);

        assertEquals("应按可见项的位置选中，而不是用章节下标当位置",
                1, ShowChapterListAction.selectionPositionFor(visible, 200));
    }

    @Test
    public void selectionFallsBackToFirstRowWhenCurrentChapterIsFilteredOut() {
        List<Integer> visible = Arrays.asList(0, 2, 4);

        assertEquals("当前章不在筛选结果里时退回第 1 项",
                0, ShowChapterListAction.selectionPositionFor(visible, 3));
    }

    @Test
    public void selectionIsNoneWhenNothingVisible() {
        assertEquals(-1, ShowChapterListAction.selectionPositionFor(new ArrayList<>(), 3));
        assertEquals(-1, ShowChapterListAction.selectionPositionFor(null, 3));
    }

    // ---------- 下标夹取 ----------

    @Test
    public void clampKeepsIndexInsideRange() {
        assertEquals(0, ShowChapterListAction.clampToRange(0, 5));
        assertEquals(4, ShowChapterListAction.clampToRange(4, 5));
        assertEquals("越界向下夹到最后一章", 4, ShowChapterListAction.clampToRange(99, 5));
        assertEquals("负数夹到第 1 章", 0, ShowChapterListAction.clampToRange(-3, 5));
    }

    @Test
    public void clampReportsNoIndexWhenThereAreNoChapters() {
        assertEquals(-1, ShowChapterListAction.clampToRange(0, 0));
        assertEquals(-1, ShowChapterListAction.clampToRange(5, 0));
    }

    @Test
    public void currentChapterIndexAndFilterWorkTogetherForLargeToc() {
        // 927 章的真实规模：打开时定位到第 500 章，再过滤出它所在的分组
        List<Chapter> all = chapters(927);
        int current = ShowChapterListAction.clampToRange(499, all.size());
        assertEquals(499, current);

        List<Integer> full = ShowChapterListAction.filterChapterIndices(all, "");
        assertEquals("空关键字应返回全部 927 章", 927, full.size());
        assertEquals("打开时选中位置等于当前章的位置",
                499, ShowChapterListAction.selectionPositionFor(full, current));

        List<Integer> filtered = ShowChapterListAction.filterChapterIndices(all, "50");
        assertTrue("过滤后当前章若还在列表里，也应能正确定位",
                ShowChapterListAction.selectionPositionFor(filtered, 499) >= 0);
    }
}
