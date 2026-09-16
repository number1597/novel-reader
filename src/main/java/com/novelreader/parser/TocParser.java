package com.novelreader.parser;

import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.util.UrlUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 目录页解析：从目录页 HTML 中提取「章节标题 + 章节绝对 URL」列表。 */
public class TocParser {

    /**
     * 解析目录页。
     *
     * <p>规则中 {@code toc.containerSelector} 为空时在整个文档中按 {@code toc.linkSelector} 查找；
     * 否则先在容器内查找，容器可匹配多个（例如分卷目录）。
     */
    public List<Chapter> parse(Document doc, NovelRule rule) {
        List<Chapter> chapters = new ArrayList<>();
        if (doc == null || rule == null) {
            return chapters;
        }
        String linkSelector = rule.getLinkSelector();
        if (linkSelector.isEmpty()) {
            return chapters;
        }

        Elements links = new Elements();
        String containerSelector = rule.getContainerSelector();
        if (containerSelector.isEmpty()) {
            links.addAll(doc.select(linkSelector));
        } else {
            for (Element container : doc.select(containerSelector)) {
                links.addAll(container.select(linkSelector));
            }
        }

        // 同时兼容「容器本身即列表」的写法
        if (links.isEmpty()) {
            links.addAll(doc.select(linkSelector));
        }

        Set<String> seenUrls = new LinkedHashSet<>();
        for (Element link : links) {
            String href = link.hasAttr("abs:href") ? link.attr("abs:href") : link.attr("href");
            String absolute = UrlUtil.toAbsolute(href, doc.baseUri());
            String title = link.text().trim();
            if (absolute == null || absolute.isEmpty() || title.isEmpty()) {
                continue;
            }
            if (absolute.startsWith("javascript:") || absolute.startsWith("#")) {
                continue;
            }
            if (seenUrls.add(absolute)) {
                chapters.add(new Chapter(title, absolute));
            }
        }
        return chapters;
    }

    /** 供调试：返回命中的链接数量，便于判断选择器是否正确。 */
    public int countCandidates(Document doc, NovelRule rule) {
        return parse(doc, rule).size();
    }
}
