package com.novelreader;

import com.novelreader.action.ShowHistoryAction;
import com.novelreader.history.ReadingHistoryEntry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 阅读历史弹窗的「展示项 ↔ 条目」对齐。
 *
 * <p>这类错位最容易悄悄发生：列表下标一旦与真实条目错开，
 * 用户点第 3 本会打开第 2 本，点删除会删掉别人 —— 现象诡异且难查。
 * 因此在这里把对齐关系钉死。
 */
public class HistoryLabelsTest {

    private static ReadingHistoryEntry entry(String tocUrl, String title, int chapter, int segment) {
        ReadingHistoryEntry e = new ReadingHistoryEntry(tocUrl, title);
        e.updateProgress(chapter, segment, "第" + (chapter + 1) + "章", tocUrl + "c.html", 100, 1000L);
        return e;
    }

    @Test
    public void labelsAlignOneToOneWithEntries() {
        List<ReadingHistoryEntry> entries = List.of(
                entry("https://a.com/1/", "A书", 0, 0),
                entry("https://b.com/1/", "B书", 5, 2),
                entry("https://c.com/1/", "C书", 9, 1));

        List<String> labels = ShowHistoryAction.labels(entries);

        assertEquals("展示项数量必须与历史条目一致", entries.size(), labels.size());
        for (int i = 0; i < entries.size(); i++) {
            assertTrue("第 " + i + " 项应含对应书名",
                    labels.get(i).contains(entries.get(i).getTitle()));
        }
    }

    @Test
    public void labelsAreUniqueSoChoiceMapsBackToTheRightBook() {
        // 弹窗回调按「文案反查下标」，文案重复会导致打开错书
        List<String> labels = ShowHistoryAction.labels(List.of(
                entry("https://a.com/1/", "A书", 3, 1),
                entry("https://b.com/1/", "B书", 7, 0)));

        assertEquals("文案不应重复", labels.size(), new java.util.HashSet<>(labels).size());
    }

    @Test
    public void labelsHandleNullAndEmptyInput() {
        assertTrue(ShowHistoryAction.labels(null).isEmpty());
        assertTrue(ShowHistoryAction.labels(new ArrayList<>()).isEmpty());
    }

    @Test
    public void labelIncludesResumePosition() {
        List<String> labels = ShowHistoryAction.labels(List.of(
                entry("https://a.com/1/", "天籁之书", 11, 2)));

        String label = labels.get(0);
        assertTrue("应能看出读到第几章: " + label, label.contains("第 12 章"));
        assertTrue("应能看出读到第几段: " + label, label.contains("第 3 段"));
    }
}
