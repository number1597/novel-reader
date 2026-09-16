package com.novelreader;

import com.novelreader.action.ShowChapterListAction;
import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 章节目录对话框的展示项生成。
 *
 * <p>核心不变量是「展示项下标 === 章节下标」：对话框跳章时按
 * 「可见项位置 → 真实章节下标」的映射表反查（见 {@code ChapterListDialog}），
 * 一旦错位就会跳到错误的章节。
 */
public class ChapterListLabelsTest {

    private static ReaderState stateWith(int chapterCount) {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 1; i <= chapterCount; i++) {
            chapters.add(new Chapter("第" + i + "章 标题" + i, "https://example.com/ch" + i));
        }
        return new ReaderState(chapters, 0, "第1章 标题1",
                Arrays.asList("正文"), null, "https://example.com/toc");
    }

    @Test
    public void labelsAlignWithChapterIndexOneToOne() {
        ReaderState state = stateWith(3);

        List<String> items = ShowChapterListAction.labels(state.getChapters());

        assertEquals(3, items.size());
        // 序号从 1 开始，而下标从 0 开始——这是最容易出错的地方，显式断言
        assertEquals("1. 第1章 标题1", items.get(0));
        assertEquals("2. 第2章 标题2", items.get(1));
        assertEquals("3. 第3章 标题3", items.get(2));
        assertEquals("下标与章节下标必须对齐", "第2章 标题2", state.getChapterAt(items.indexOf("2. 第2章 标题2")).getTitle());
    }

    @Test
    public void labelsAreEmptyWhenThereIsNoChapterList() {
        ReaderState single = new ReaderState("单章", Arrays.asList("正文"));

        assertTrue(ShowChapterListAction.labels(single.getChapters()).isEmpty());
        assertTrue(ShowChapterListAction.labels(null).isEmpty());
    }

    @Test
    public void labelsPreserveLargeTocSize() {
        ReaderState state = stateWith(392);

        List<String> items = ShowChapterListAction.labels(state.getChapters());

        assertEquals(392, items.size());
        assertEquals("392. 第392章 标题392", items.get(391));
    }
}
