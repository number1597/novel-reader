package com.novelreader;

import com.novelreader.model.NovelRule;
import com.novelreader.parser.ContentParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 正文解析：标题、正文抽取、广告剔除、下一页定位。 */
public class ContentParserTest {

    private static final String BASE = "https://example.com/book/ch1";

    private static NovelRule rule() {
        NovelRule rule = new NovelRule();
        rule.content.titleSelector = ".bookname";
        rule.content.bodySelector = "#content";
        rule.content.removeSelectors = List.of("#content .ad");
        return rule;
    }

    @Test
    public void extractsTitleAndBodyAndRemovesAds() {
        String html = "<html><body>"
                + "<h1 class='bookname'>第一章 起始</h1>"
                + "<div id='content'>"
                + "  <p>正文第一段。</p>"
                + "  <div class='ad'>广告广告广告</div>"
                + "  <p>正文第二段。</p>"
                + "  <script>var x = 1;</script>"
                + "</div>"
                + "<div id='footer'>页脚无关内容</div>"
                + "</body></html>";
        Document document = Jsoup.parse(html, BASE);

        ContentParser.ParsedContent parsed = new ContentParser().parse(document, rule());

        assertEquals("第一章 起始", parsed.getTitle());
        assertTrue(parsed.getBody().contains("正文第一段"));
        assertTrue(parsed.getBody().contains("正文第二段"));
        assertFalse("广告节点应被剔除", parsed.getBody().contains("广告广告广告"));
        assertFalse("script 内容应被剔除", parsed.getBody().contains("var x"));
        assertFalse("容器外内容不应出现", parsed.getBody().contains("页脚无关内容"));
        assertNull("没有下一页链接", parsed.getNextPageUrl());
    }

    @Test
    public void preservesParagraphLineBreaks() {
        String html = "<html><body><div id='content'>"
                + "<p>第一段。</p><p>第二段。</p><p>第三段。</p>"
                + "</div></body></html>";
        Document document = Jsoup.parse(html, BASE);

        String body = new ContentParser().parse(document, rule()).getBody();

        assertEquals("第一段。\n第二段。\n第三段。", body);
    }

    @Test
    public void findsNextPageByText() {
        String html = "<html><body><div id='content'><p>第一页正文。</p></div>"
                + "<a href='/book/ch1?page=2'>下一页</a></body></html>";
        Document document = Jsoup.parse(html, BASE);

        ContentParser.ParsedContent parsed = new ContentParser().parse(document, rule());

        assertEquals("https://example.com/book/ch1?page=2", parsed.getNextPageUrl());
    }

    @Test
    public void skipsNextChapterLinkWhenLookingForNextPage() {
        String html = "<html><body><div id='content'><p>末页正文。</p></div>"
                + "<a href='/book/ch2'>下一章</a></body></html>";
        Document document = Jsoup.parse(html, BASE);

        ContentParser.ParsedContent parsed = new ContentParser().parse(document, rule());

        assertNull("「下一章」不应被当成「下一页」", parsed.getNextPageUrl());
    }

    @Test
    public void nextSelectorTakesPrecedenceOverText() {
        String html = "<html><body><div id='content'><p>正文。</p></div>"
                + "<a href='/book/ch1?page=2' class='page-next'>翻页</a>"
                + "<a href='/book/ch9'>下一页</a></body></html>";
        Document document = Jsoup.parse(html, BASE);
        NovelRule rule = rule();
        rule.paging.nextSelector = "a.page-next";

        ContentParser.ParsedContent parsed = new ContentParser().parse(document, rule);

        assertEquals("https://example.com/book/ch1?page=2", parsed.getNextPageUrl());
    }

    @Test
    public void fallsBackToBodyWhenBodySelectorMisses() {
        String html = "<html><body><p>没有匹配容器的正文。</p></body></html>";
        Document document = Jsoup.parse(html, BASE);
        NovelRule rule = rule();
        rule.content.bodySelector = "#not-exist";

        String body = new ContentParser().parse(document, rule).getBody();

        assertTrue(body.contains("没有匹配容器的正文"));
    }

    @Test
    public void escapeHtmlHandlesSpecialCharacters() {
        assertEquals("&lt;a&gt;&amp;&quot;&#39;", com.novelreader.reader.ReaderNotifier
                .escapeHtml("<a>&\"'"));
    }
}
