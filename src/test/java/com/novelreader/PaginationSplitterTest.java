package com.novelreader;

import com.novelreader.reader.PaginationSplitter;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 分页切分的核心不变量：不丢字、每段不超长、尽量在标点处断开。 */
public class PaginationSplitterTest {

    private static final String CHINESE_BREAKS = "。！？…；";

    @Test
    public void splitsLongChineseTextWithoutLosingCharacters() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("这是第").append(i).append("句中文测试文本内容。");
        }
        String text = sb.toString();

        List<String> segments = PaginationSplitter.split(text, 200);

        assertTrue("应切出多个段落，实际 " + segments.size(), segments.size() >= 3);
        for (String segment : segments) {
            assertTrue("每段长度应 <= 200，实际 " + segment.length(), segment.length() <= 200);
        }
        assertEquals("拼接后必须与原文完全一致", text, PaginationSplitter.join(segments));
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            char last = segment.charAt(segment.length() - 1);
            assertTrue("非末段应在中文标点处结束，实际结尾字符：" + last,
                    CHINESE_BREAKS.indexOf(last) >= 0);
        }
    }

    @Test
    public void hardSplitsWhenThereIsNoPunctuation() {
        String text = "a".repeat(1000);

        List<String> segments = PaginationSplitter.split(text, 100);

        assertEquals(10, segments.size());
        assertEquals(text, PaginationSplitter.join(segments));
    }

    @Test
    public void handlesEmptyAndNullInput() {
        assertTrue(PaginationSplitter.split(null, 100).isEmpty());
        assertTrue(PaginationSplitter.split("", 100).isEmpty());
    }

    @Test
    public void clampsNonPositiveMaxChars() {
        List<String> segments = PaginationSplitter.split("abc", 0);

        assertEquals("等于 0 时按 1 处理，逐字符切分", 3, segments.size());
        assertEquals("abc", PaginationSplitter.join(segments));
    }

    @Test
    public void keepsShortTextAsSingleSegment() {
        List<String> segments = PaginationSplitter.split("很短的一段话。", 200);

        assertEquals(1, segments.size());
        assertEquals("很短的一段话。", segments.get(0));
    }
}
