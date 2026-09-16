package com.novelreader;

import com.novelreader.model.ReaderState;
import com.novelreader.reader.ReaderNotifier;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 通知渲染规则：正文进标题、内容为空、不含章节名/段号、不带翻页按钮。
 *
 * <p>这里只测纯函数 {@code buildTitle}，不初始化 IntelliJ 平台。
 */
public class ReaderNotifierTest {

    private static ReaderState state(String chapterTitle, String... segments) {
        return new ReaderState(chapterTitle, Arrays.asList(segments));
    }

    /** 内容栏必须是空串——已实测 API 接受空串，不需要零宽字符占位。 */
    @Test
    public void contentIsEmptyStringNotZeroWidthPlaceholder() {
        assertEquals("内容栏应为空串", "", ReaderNotifier.EMPTY_CONTENT);
        assertFalse("不应再保留零宽空格占位符",
                ReaderNotifier.EMPTY_CONTENT.contains("\u200B"));
    }

    /** 标题里只应出现正文，不应出现章节名或「第 n/m 段」。 */
    @Test
    public void titleContainsOnlyBodyWithoutChapterNameOrSegmentCounter() {
        ReaderState state = state("第1章 没爹没娘", "周宁野被一阵哭声吵醒。");

        String title = ReaderNotifier.buildTitle(state);

        assertTrue("标题应包含正文", title.contains("周宁野被一阵哭声吵醒。"));
        assertFalse("标题不应包含章节名", title.contains("没爹没娘"));
        assertFalse("标题不应包含段号", title.contains("段"));
        assertFalse("标题不应包含书名号", title.contains("《"));
    }

    /** 换行应转成 <br>，正文里的尖括号必须被转义，避免污染通知 HTML。 */
    @Test
    public void titleEscapesHtmlAndConvertsNewlines() {
        ReaderState state = state("", "第一行\n第二行 <script>alert(1)</script> & 结束");

        String title = ReaderNotifier.buildTitle(state);

        assertTrue("换行应转为 <br>", title.contains("第一行<br>第二行"));
        assertFalse("原始 <script> 不应出现", title.contains("<script>"));
        assertTrue("尖括号应被转义", title.contains("&lt;script&gt;"));
        assertTrue("& 应被转义", title.contains("&amp;"));
    }

    /** 标题应带显式样式，把默认偏大的标题字号压小。 */
    @Test
    public void titleCarriesExplicitSmallerFontStyle() {
        ReaderState state = state("", "正文");

        String title = ReaderNotifier.buildTitle(state);

        assertTrue("应显式设置字号", title.contains("font-size:"));
        assertTrue("应显式去掉粗体", title.contains("font-weight:normal"));
        assertTrue("应显式设置行高", title.contains("line-height:"));
    }

    /** 标题只渲染当前段，不含其他段内容。 */
    @Test
    public void titleRendersCurrentSegmentOnlyAndTracksCursor() {
        ReaderState state = state("", "第一段内容。", "第二段内容。", "第三段内容。");

        assertTrue(ReaderNotifier.buildTitle(state).contains("第一段内容。"));
        assertFalse(ReaderNotifier.buildTitle(state).contains("第二段内容。"));

        state.next();
        String title = ReaderNotifier.buildTitle(state);
        assertTrue("翻页后应渲染第二段", title.contains("第二段内容。"));
        assertFalse("翻页后不应再出现第一段", title.contains("第一段内容。"));
    }

    /** 空分段退化为空串标题，不抛异常。 */
    @Test
    public void emptySegmentProducesEmptyTitle() {
        assertEquals("", ReaderNotifier.buildTitle(state("", "")));
        assertEquals("", ReaderNotifier.buildTitle(state("", (String) null)));
        assertEquals("", ReaderNotifier.buildTitle(state("", new String[0])));
    }

    /** 标题不应包含任何翻页按钮文案（按钮已整体移除）。 */
    @Test
    public void titleDoesNotContainPagingButtonLabels() {
        List<String> segments = Arrays.asList("甲", "乙");
        ReaderState state = new ReaderState("章节", segments);

        String title = ReaderNotifier.buildTitle(state);

        assertFalse(title.contains("下一页"));
        assertFalse(title.contains("上一页"));
    }
}
