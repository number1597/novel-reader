package com.novelreader.parser;

import com.novelreader.model.NovelRule;
import com.novelreader.util.UrlUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 章节正文解析：提取标题与正文（保留段落换行），并定位「下一页」链接。 */
public class ContentParser {

    /** 视为块级、需要在文本抽取时插入换行的标签。 */
    private static final Set<String> BLOCK_TAGS = new HashSet<>(Arrays.asList(
            "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "ul", "ol", "tr", "td", "th", "section", "article",
            "blockquote", "pre", "hr", "table", "dl", "dt", "dd"));

    /** 解析结果。 */
    public static class ParsedContent {
        private final String title;
        private final String body;
        private final String nextPageUrl;

        ParsedContent(String title, String body, String nextPageUrl) {
            this.title = title;
            this.body = body;
            this.nextPageUrl = nextPageUrl;
        }

        public String getTitle() {
            return title;
        }

        public String getBody() {
            return body;
        }

        /** 下一页 URL；没有则为 null。 */
        public String getNextPageUrl() {
            return nextPageUrl;
        }
    }

    /**
     * 解析一章内容。
     *
     * <p>注意顺序：先在**未清洗**的文档上定位「下一页」链接，再剔除广告节点。
     * 否则规则里常见的 {@code #content a:contains(下一页)} 这类 removeSelectors
     * 会把翻页链接一起删掉，导致多页章节只读到第一页。
     */
    public ParsedContent parse(Document doc, NovelRule rule) {
        if (doc == null || rule == null) {
            return new ParsedContent("", "", null);
        }
        String nextPageUrl = findNextPageUrl(doc, rule);
        String title = parseTitle(doc, rule);
        String body = parseBody(doc, rule);
        return new ParsedContent(title, body, nextPageUrl);
    }

    /** 章节标题；titleSelector 为空或未命中时返回空串。 */
    public String parseTitle(Document doc, NovelRule rule) {
        String selector = rule.getTitleSelector();
        if (selector.isEmpty()) {
            return "";
        }
        Element element = doc.selectFirst(selector);
        return element == null ? "" : element.text().trim();
    }

    /** 正文文本（已剔除 removeSelectors，并保留段落换行）。 */
    public String parseBody(Document doc, NovelRule rule) {
        Element body = locateBody(doc, rule);
        if (body == null) {
            return "";
        }
        clean(body, rule);
        return extractText(body);
    }

    /** 定位正文容器；bodySelector 未命中时退化为整个 body。 */
    public Element locateBody(Document doc, NovelRule rule) {
        String selector = rule.getBodySelector();
        if (!selector.isEmpty()) {
            Element element = doc.selectFirst(selector);
            if (element != null) {
                return element;
            }
        }
        return doc.body();
    }

    /** 剔除脚本、样式与规则中声明的广告/推荐/分页节点。 */
    private void clean(Element body, NovelRule rule) {
        body.select("script, style, noscript, iframe").remove();
        List<String> removeSelectors = rule.getRemoveSelectors();
        for (String selector : removeSelectors) {
            try {
                Elements matched = body.select(selector);
                matched.remove();
            } catch (Exception ignored) {
                // 用户写的选择器不合法时忽略，不影响整体阅读
            }
        }
    }

    /**
     * 定位「下一页」链接。
     *
     * <p>优先用 {@code paging.nextSelector}；否则按 {@code paging.nextTextContains} 文本匹配。
     * 文本匹配时优先精确相等，其次前缀匹配，最后包含匹配；并跳过明显是「下一章」的链接，
     * 避免末页误抓下一章内容。
     */
    public String findNextPageUrl(Document doc, NovelRule rule) {
        String selector = rule.getNextSelector();
        if (!selector.isEmpty()) {
            Element element = doc.selectFirst(selector);
            String url = absoluteUrl(element, doc);
            if (url != null) {
                return url;
            }
        }

        String keyword = rule.getNextTextContains();
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        boolean keywordIsChapter = keyword.contains("章");

        String prefixHit = null;
        String containsHit = null;
        for (Element link : doc.select("a")) {
            String text = link.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (!keywordIsChapter && text.contains("章")) {
                // 「下一章」不是「下一页」
                continue;
            }
            String url = absoluteUrl(link, doc);
            if (url == null) {
                continue;
            }
            if (text.equals(keyword)) {
                return url;
            }
            if (prefixHit == null && text.startsWith(keyword)) {
                prefixHit = url;
            } else if (containsHit == null && text.contains(keyword)) {
                containsHit = url;
            }
        }
        return prefixHit != null ? prefixHit : containsHit;
    }

    private String absoluteUrl(Element element, Document doc) {
        if (element == null) {
            return null;
        }
        String href = element.hasAttr("abs:href") ? element.attr("abs:href") : element.attr("href");
        String absolute = UrlUtil.toAbsolute(href, doc.baseUri());
        if (absolute == null || absolute.isEmpty()
                || absolute.startsWith("javascript:") || absolute.startsWith("#")) {
            return null;
        }
        return absolute;
    }

    // ---------- 文本抽取 ----------

    /** 按块级标签边界插入换行地抽取纯文本，并规整多余空行。 */
    public static String extractText(Element root) {
        StringBuilder sb = new StringBuilder();
        appendText(root, sb);
        return normalize(sb.toString());
    }

    private static void appendText(Node node, StringBuilder sb) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode) {
                sb.append(((TextNode) child).getWholeText());
                continue;
            }
            if (!(child instanceof Element)) {
                continue;
            }
            Element element = (Element) child;
            String tag = element.tagName().toLowerCase();
            if ("br".equals(tag)) {
                sb.append('\n');
                continue;
            }
            boolean block = BLOCK_TAGS.contains(tag);
            if (block) {
                newLine(sb);
            }
            appendText(element, sb);
            if (block) {
                newLine(sb);
            }
        }
    }

    private static void newLine(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    /** 合并 3 个以上连续换行为 2 个，去掉行首尾空白与全角空格缩进。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replace("\r\n", "\n").replace('\r', '\n');
        result = result.replace('\u00A0', ' ').replace("\u3000", " ");
        result = result.replaceAll("[ \\t]+\n", "\n");
        result = result.replaceAll("\n{3,}", "\n\n");
        StringBuilder sb = new StringBuilder();
        for (String line : result.split("\n", -1)) {
            sb.append(line.trim()).append('\n');
        }
        return sb.toString().trim();
    }
}
