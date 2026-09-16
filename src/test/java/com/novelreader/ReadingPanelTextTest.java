package com.novelreader;

import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.ReadingPanel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 阅读面板顶部的两处文案：进度与状态行。
 *
 * <p>这类逻辑写错不会有任何报错，只会"显示得不对"，所以要断言死。
 * 状态行尤其重要 —— 它是「点了按钮到底有没有反应」的唯一可见载体。
 */
public class ReadingPanelTextTest {

    @Test
    public void progressShowsChapterPositionWhenThereIsATableOfContents() {
        ReaderState state = new ReaderState(toc(927), 499, "第500章", Arrays.asList("正文"), null, "u");

        assertEquals("第 500 / 927 章", ReadingPanel.buildProgressText(state));
    }

    @Test
    public void progressUsesOneBasedNumbers() {
        ReaderState state = new ReaderState(toc(3), 0, "第1章", Arrays.asList("正文"), null, "u");

        assertEquals("第一章不该显示成「第 0 章」", "第 1 / 3 章",
                ReadingPanel.buildProgressText(state));
    }

    @Test
    public void progressDoesNotShowSegmentPosition() {
        // 面板整章展示又没有高亮，段号在这里没有可对应的位置 ——
        // 但段游标仍按段推进（通知模式、历史、书签都用它），只是不显示
        ReaderState state = new ReaderState(toc(3), 1, "第2章", Arrays.asList("一", "二", "三"), null, "u");
        state.moveToSegment(2);

        assertEquals("不该把段号写进面板进度", "第 2 / 3 章",
                ReadingPanel.buildProgressText(state));
    }

    @Test
    public void progressFallsBackToSegmentCountInSingleChapterMode() {
        ReaderState state = new ReaderState("单章", Arrays.asList("一", "二", "三三"));

        assertEquals("没有目录时没有章号可显示", "3 段", ReadingPanel.buildProgressText(state));
    }

    @Test
    public void statusLineCollapsesWhitespaceSoMultiLineErrorsFit() {
        // 连续空白压成一个空格、首尾去掉。标点后留下的那一个空格是压缩的自然结果，保留即可
        assertEquals("加载失败： 连接超时", ReadingPanel.oneLine("加载失败：\n\n  连接超时  "));
    }

    @Test
    public void statusLineTruncatesVeryLongMessages() {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longMessage.append('字');
        }

        String flat = ReadingPanel.oneLine(longMessage.toString());

        assertTrue("超长消息要截断，否则会把面板顶部挤变形",
                flat.length() < longMessage.length());
        assertTrue("截断处要有省略号，让人知道后面还有内容", flat.endsWith("…"));
    }

    @Test
    public void statusLineKeepsShortMessagesIntact() {
        assertEquals("已经是最后一章了。", ReadingPanel.oneLine("已经是最后一章了。"));
    }

    private static List<Chapter> toc(int size) {
        List<Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            chapters.add(new Chapter("第" + (i + 1) + "章", "https://example.com/" + i + ".html"));
        }
        return chapters;
    }
}
