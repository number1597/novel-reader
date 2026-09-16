package com.novelreader.parser;

import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 阅读流程编排：抓目录、抓正文（含多页章节拼接）。 */
public class ChapterReader {

    /** 一章的完整正文。 */
    public static class ChapterText {
        private final String title;
        private final String body;
        private final int pages;

        ChapterText(String title, String body, int pages) {
            this.title = title;
            this.body = body;
            this.pages = pages;
        }

        public String getTitle() {
            return title;
        }

        public String getBody() {
            return body;
        }

        /** 实际抓取并拼接的页数。 */
        public int getPages() {
            return pages;
        }

        public boolean isEmpty() {
            return body == null || body.trim().isEmpty();
        }
    }

    private final HtmlFetcher fetcher;
    private final TocParser tocParser = new TocParser();
    private final ContentParser contentParser = new ContentParser();

    public ChapterReader(HtmlFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** 目录解析结果：哪条规则最终生效 + 解析出的章节。 */
    public static class TocResult {
        private final NovelRule rule;
        private final List<Chapter> chapters;

        TocResult(NovelRule rule, List<Chapter> chapters) {
            this.rule = rule;
            this.chapters = chapters;
        }

        /** 真正解析成功的规则；后续抓正文必须继续用它。 */
        public NovelRule getRule() {
            return rule;
        }

        public List<Chapter> getChapters() {
            return chapters;
        }
    }

    /** 抓取并解析目录页。 */
    public List<Chapter> loadToc(String tocUrl, NovelRule rule) throws IOException {
        Document document = fetcher.fetch(tocUrl, rule);
        List<Chapter> chapters = tocParser.parse(document, rule);
        if (chapters.isEmpty()) {
            throw new IOException("未能从目录页解析出任何章节，请检查规则中的 toc.linkSelector / toc.containerSelector 是否正确");
        }
        return chapters;
    }

    /**
     * 依次尝试多条候选规则解析目录，返回<b>第一个能解析出章节</b>的结果。
     *
     * <p>这就是「站点改版自愈」：站点改版后，旧规则往往还能匹配上域名，
     * 但选择器已经失效、解析结果为空。此时换下一条候选规则常常还能work。
     * 全部失败时抛出<b>最后一条</b>规则的错误（最接近真实原因）。
     *
     * @param candidates 候选规则（按优先级，通常来自 {@code RuleLoader.matchAll}）；
     *                   最多尝试 {@code RuleLoader.MAX_FALLBACK_CANDIDATES} 条
     */
    public TocResult loadTocWithFallback(String tocUrl, List<NovelRule> candidates) throws IOException {
        if (candidates == null || candidates.isEmpty()) {
            throw new IOException("没有可用的规则，无法解析目录页：" + tocUrl);
        }
        int limit = Math.min(candidates.size(), RuleLoader.MAX_FALLBACK_CANDIDATES);
        IOException lastError = null;
        for (int i = 0; i < limit; i++) {
            NovelRule candidate = candidates.get(i);
            if (candidate == null) {
                continue;
            }
            try {
                return new TocResult(candidate, loadToc(tocUrl, candidate));
            } catch (IOException e) {
                // 这条规则不行：记下原因，换下一条继续试
                lastError = e;
            }
        }
        throw lastError == null
                ? new IOException("候选规则都不可用，无法解析目录页：" + tocUrl)
                : lastError;
    }

    /**
     * 读取一章完整正文，自动拼接「下一页」。
     *
     * <p>双重保护防止站点互链导致的死循环：{@code visited} 去重 + {@code maxPages} 上限。
     */
    public ChapterText read(String chapterUrl, NovelRule rule) throws IOException {
        StringBuilder body = new StringBuilder();
        Set<String> visited = new LinkedHashSet<>();
        List<String> pageTitles = new ArrayList<>();

        String url = chapterUrl;
        int pages = 0;
        int maxPages = Math.max(1, rule.getMaxPages());

        while (url != null && pages < maxPages && visited.add(url)) {
            Document document = fetcher.fetch(url, rule);
            ContentParser.ParsedContent parsed = contentParser.parse(document, rule);

            if (parsed.getTitle() != null && !parsed.getTitle().isEmpty()) {
                pageTitles.add(parsed.getTitle());
            }
            String pageBody = parsed.getBody();
            if (pageBody != null && !pageBody.isEmpty()) {
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                body.append(pageBody);
            }
            url = parsed.getNextPageUrl();
            pages++;
        }

        String title = pageTitles.isEmpty() ? "" : pageTitles.get(0);
        return new ChapterText(title, body.toString(), pages);
    }
}
