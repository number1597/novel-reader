package com.novelreader;

import com.novelreader.util.PositionResolver;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 位置还原的共享逻辑：URL 优先、下标兜底并夹取、段越界退末段。
 *
 * <p>这段逻辑被「阅读历史」与「书签」共用 —— 一旦这里的行为变了，两处会同时变，
 * 所以把边界情形全部钉死：目录改版导致下标漂移、URL 找不到、空目录、
 * 负数下标、段号超出本章实际段数。
 */
public class PositionResolverTest {

    private static List<String> urls(String... values) {
        return Arrays.asList(values);
    }

    // ---------- 章节定位 ----------

    @Test
    public void chapterUrlWinsOverStaleIndex() {
        // 站点在目录前面插了两章，存下来的下标 1 已漂移；URL 才是稳定的
        int index = PositionResolver.resolveChapterIndex(
                urls("https://a.com/1.html", "https://a.com/x.html", "https://a.com/y.html",
                        "https://a.com/2.html"),
                "https://a.com/2.html", 1);
        assertEquals("URL 命中时必须用 URL 的位置，而不是存下来的下标", 3, index);
    }

    @Test
    public void fallsBackToIndexWhenUrlMissing() {
        int index = PositionResolver.resolveChapterIndex(
                urls("https://a.com/1.html", "https://a.com/2.html", "https://a.com/3.html"),
                "https://a.com/gone.html", 1);
        assertEquals("URL 找不到时用存下来的下标", 1, index);
    }

    @Test
    public void fallsBackToIndexWhenSavedUrlIsEmpty() {
        int index = PositionResolver.resolveChapterIndex(
                urls("https://a.com/1.html", "https://a.com/2.html"), "", 1);
        assertEquals("没存 URL 时按下标定位", 1, index);
    }

    @Test
    public void fallbackIndexIsClampedToRange() {
        List<String> three = urls("a", "b", "c");
        assertEquals("下标超出目录长度时夹到最后", 2,
                PositionResolver.resolveChapterIndex(three, "", 99));
        assertEquals("负数下标视为 0", 0,
                PositionResolver.resolveChapterIndex(three, "", -5));
    }

    @Test
    public void emptyChapterListYieldsZero() {
        assertEquals("空目录返回 0", 0,
                PositionResolver.resolveChapterIndex(Collections.emptyList(), "https://a.com/1.html", 7));
        assertEquals("null 目录返回 0", 0,
                PositionResolver.resolveChapterIndex(null, "", 7));
    }

    @Test
    public void exactUrlMatchOnSingleChapter() {
        assertEquals("只有一章且 URL 命中", 0,
                PositionResolver.resolveChapterIndex(urls("https://a.com/1.html"),
                        "https://a.com/1.html", 5));
    }

    @Test
    public void blankAndNullUrlsAreIgnored() {
        List<String> list = new ArrayList<>();
        list.add(null);
        list.add("https://a.com/2.html");
        assertEquals("列表里混入 null 不应抛异常", 1,
                PositionResolver.resolveChapterIndex(list, "https://a.com/2.html", 0));
        assertEquals("保存的 URL 为 null 时用下标", 0,
                PositionResolver.resolveChapterIndex(list, null, 0));
    }

    // ---------- 段定位 ----------

    @Test
    public void segmentInsideRangeIsKept() {
        assertEquals("段号在范围内保持不变", 2, PositionResolver.resolveSegmentIndex(2, 10));
    }

    @Test
    public void segmentBeyondRangeFallsToLastSegment() {
        assertEquals("段号越界退到最后一段（不是第 1 段，避免进度倒退）",
                9, PositionResolver.resolveSegmentIndex(99, 10));
    }

    @Test
    public void negativeSegmentBecomesZero() {
        assertEquals("负数段号视为第 1 段", 0, PositionResolver.resolveSegmentIndex(-3, 10));
    }

    @Test
    public void emptyChapterYieldsZeroSegment() {
        assertEquals("本章没有分段时返回 0", 0, PositionResolver.resolveSegmentIndex(5, 0));
        assertEquals("段数为负时返回 0", 0, PositionResolver.resolveSegmentIndex(5, -1));
    }

    @Test
    public void lastValidSegmentIsBoundary() {
        assertEquals("正好是最后一段时不夹取", 9, PositionResolver.resolveSegmentIndex(9, 10));
        assertEquals("只有一段时任何段号都落 0", 0, PositionResolver.resolveSegmentIndex(9, 1));
    }
}
