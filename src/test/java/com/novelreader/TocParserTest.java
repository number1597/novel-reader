package com.novelreader;

import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.parser.TocParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 目录页解析：容器限定、相对链接补全、去重。 */
public class TocParserTest {

    private static final String BASE = "https://example.com/novel/1/";

    private static final String TOC_HTML = "<html><head><title>目录</title></head><body>"
            + "<div id='list'><dl>"
            + "  <dd><a href='/book/ch1'>第一章 起始</a></dd>"
            + "  <dd><a href='/book/ch2'>第二章 终章</a></dd>"
            + "  <dd><a href='/book/ch1'>第一章 起始</a></dd>"
            + "</dl></div>"
            + "<div id='recommend'><a href='/book/other'>无关推荐</a></div>"
            + "<a href='javascript:void(0)'>无效链接</a>"
            + "</body></html>";

    @Test
    public void parsesChaptersInsideContainerAndDeduplicates() {
        Document document = Jsoup.parse(TOC_HTML, BASE);
        NovelRule rule = new NovelRule();
        rule.toc.containerSelector = "#list dl";
        rule.toc.linkSelector = "dd a";

        List<Chapter> chapters = new TocParser().parse(document, rule);

        assertEquals("重复链接应被去重", 2, chapters.size());
        assertEquals("第一章 起始", chapters.get(0).getTitle());
        assertEquals("https://example.com/book/ch1", chapters.get(0).getUrl());
        assertEquals("第二章 终章", chapters.get(1).getTitle());
    }

    @Test
    public void restrictsToContainerSoRecommendLinksAreExcluded() {
        Document document = Jsoup.parse(TOC_HTML, BASE);
        NovelRule rule = new NovelRule();
        rule.toc.containerSelector = "#list dl";
        rule.toc.linkSelector = "a";

        List<Chapter> chapters = new TocParser().parse(document, rule);

        for (Chapter chapter : chapters) {
            assertTrue("不应包含推荐位链接：" + chapter.getUrl(),
                    !chapter.getUrl().contains("/book/other"));
        }
    }

    @Test
    public void fallsBackToWholeDocumentWhenContainerSelectorIsEmpty() {
        Document document = Jsoup.parse(TOC_HTML, BASE);
        NovelRule rule = new NovelRule();
        rule.toc.containerSelector = "";
        rule.toc.linkSelector = "div a";

        List<Chapter> chapters = new TocParser().parse(document, rule);

        assertEquals(3, chapters.size());
    }

    @Test
    public void skipsJavascriptLinks() {
        Document document = Jsoup.parse(TOC_HTML, BASE);
        NovelRule rule = new NovelRule();
        rule.toc.linkSelector = "a";

        List<Chapter> chapters = new TocParser().parse(document, rule);

        for (Chapter chapter : chapters) {
            assertTrue("不应包含 javascript 链接", !chapter.getUrl().startsWith("javascript:"));
        }
    }
}
