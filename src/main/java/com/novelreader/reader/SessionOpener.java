package com.novelreader.reader;

import com.novelreader.cache.CachedBook;
import com.novelreader.cache.ChapterCache;
import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.model.ReaderState;
import com.novelreader.parser.ChapterReader;
import com.novelreader.parser.RuleLoader;
import com.novelreader.settings.NovelReaderSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从「目录页 URL」重建一个可阅读的会话 —— 阅读历史「继续阅读」的核心。
 *
 * <p>流程与 {@code PasteTocUrlAction} 一致，但<b>不询问用户</b>任何东西：
 * 目录、规则、起始章节全部已经记在历史里，这里只负责把它们重新拿回来。
 * 抽成独立类而不是塞进 Action，是为了让「恢复阅读」这条链路能脱离 UI 测试。
 *
 * <p>关键点：<b>章节目录每次都要重新解析</b>（站点可能改版、增删章节），
 * 因此不能用历史里存的下标直接跳 —— 见
 * {@link com.novelreader.history.ReadingHistoryEntry#resolveChapterIndex(List)}。
 *
 * <h3>离线也能打开</h3>
 * 「重新解析目录」这一步需要联网，断网时就卡在这里了 —— 缓存了再多正文也进不去。
 * 因此目录页（或规则文件）拿不到时会退回 {@link com.novelreader.cache.ChapterCache}
 * 里那份目录快照继续建会话（此时规则为 null，正文全从缓存取）。
 * 历史记的那一章若刚好没缓存，还会就近退到最近的已缓存章节，而不是让整本书都打不开。
 */
public final class SessionOpener {

    private SessionOpener() {
    }

    /** 一次「打开」的结果。 */
    public static final class Opened {
        private final ReaderState state;
        private final int segmentIndex;
        private final String warning;

        Opened(ReaderState state, int segmentIndex, String warning) {
            this.state = state;
            this.segmentIndex = segmentIndex;
            this.warning = warning;
        }

        public ReaderState getState() {
            return state;
        }

        public int getSegmentIndex() {
            return segmentIndex;
        }

        /** 非致命提示（例如找不到原规则、只能退回第 1 章）；无则为 null。 */
        public String getWarning() {
            return warning;
        }
    }

    /**
     * 打开一本书并定位到指定位置。
     *
     * @param tocUrl       目录页 URL
     * @param wantChapter  期望的章节下标（历史里存的值）
     * @param wantSegment  期望的段下标（历史里存的值）
     * @param chapterUrl   期望的章节 URL；优先用它精确定位章节
     * @param settings     设置（超时、每段字数）
     * @return 打开结果；目录或正文为空时返回 null
     */
    public static Opened open(String tocUrl, int wantChapter, int wantSegment,
                              String chapterUrl, NovelReaderSettings settings) throws IOException {
        if (tocUrl == null || tocUrl.trim().isEmpty()) {
            throw new IOException("历史记录里没有目录页 URL，无法打开。");
        }
        ChapterCache cache = ReaderManager.cacheFor(settings);

        // 联网这条路可能断在两处：读不到规则、或目录页打不开。
        // 两处都不直接失败 —— 只要有离线缓存的目录，离线也应当能继续读。
        List<NovelRule> candidates = null;
        IOException ruleFailure = null;
        try {
            candidates = matchCandidates(tocUrl);
        } catch (IOException e) {
            ruleFailure = e;
        }

        ChapterReader.TocResult toc = null;
        IOException tocFailure = null;
        if (candidates != null) {
            try {
                HtmlFetcher fetcher = ChapterLoader.newFetcher(settings);
                toc = new ChapterReader(fetcher).loadTocWithFallback(tocUrl, candidates);
                if (toc.getChapters() == null || toc.getChapters().isEmpty()) {
                    tocFailure = new IOException("目录页没有解析出章节，站点可能已改版。");
                    toc = null;
                }
            } catch (IOException e) {
                tocFailure = e;
            }
        }

        if (toc != null) {
            saveBookSnapshot(cache, tocUrl, toc.getRule(), toc.getChapters());
            return openWith(tocUrl, toc.getChapters(), toc.getRule(), wantChapter, wantSegment,
                    chapterUrl, settings, cache, null);
        }

        // 走到这里说明联网拿目录失败了。没有缓存就照原样抛出（保持既有报错语义）。
        CachedBook cached = cache == null ? null : cache.readBook(tocUrl);
        if (cached == null) {
            throw tocFailure != null ? tocFailure : ruleFailure;
        }
        IOException failure = tocFailure != null ? tocFailure : ruleFailure;
        String warning = "目录页暂时拿不到（" + brief(failure)
                + "），已改用离线缓存的目录（共 " + cached.getChapterCount() + " 章）。";
        return openWith(tocUrl, new ArrayList<>(cached.getChapters()), null, wantChapter,
                wantSegment, chapterUrl, settings, cache, warning);
    }

    /**
     * 用一份已知的目录建起会话并定位。
     *
     * @param rule   解析规则；<b>离线时为 null</b>（正文全从缓存取，不需要规则）
     * @param warning 已知的非致命提示；无则 null
     */
    private static Opened openWith(String tocUrl, List<Chapter> chapters, NovelRule rule,
                                   int wantChapter, int wantSegment, String chapterUrl,
                                   NovelReaderSettings settings, ChapterCache cache,
                                   String warning) throws IOException {
        int chapterIndex = resolveChapterIndex(chapters, chapterUrl, wantChapter);
        int segmentIndex = wantSegment;
        String warn = warning;

        if (rule == null) {
            // 离线打开：历史记的那一章可能刚好没缓存，就近退到已缓存的一章，
            // 否则用户会因为「上次那一章没缓存」而整本书都打不开。
            if (!hasCached(cache, tocUrl, chapters, chapterIndex)) {
                int fallback = nearestCachedChapter(tocUrl, chapters, chapterIndex, cache);
                if (fallback >= 0) {
                    warn = join(warn, "上次读到的第 " + (chapterIndex + 1)
                            + " 章没有离线缓存，已跳到最近的缓存章节（第 " + (fallback + 1) + " 章）。");
                    chapterIndex = fallback;
                    segmentIndex = 0;
                }
            }
        }

        ChapterLoader.Loaded loaded = ChapterLoader.load(tocUrl, chapters.get(chapterIndex),
                rule, settings, cache);
        if (loaded == null || loaded.isEmpty()) {
            throw new IOException("没有解析出正文，请检查规则中的 content.bodySelector 是否正确。");
        }
        if (warn == null && loaded.getPages() == 0) {
            warn = "正文页解析异常（0 页）。";
        }

        int resolvedSegment = clampSegment(segmentIndex, loaded.getSegments().size());
        ReaderState state = new ReaderState(chapters, chapterIndex, loaded.getTitle(),
                loaded.getSegments(), rule, tocUrl);
        return new Opened(state, resolvedSegment, warn);
    }

    /** 把目录快照写进缓存 —— 没有它，离线时连「打开」这一步都过不去。 */
    private static void saveBookSnapshot(ChapterCache cache, String tocUrl, NovelRule rule,
                                         List<Chapter> chapters) {
        if (cache == null || chapters == null || chapters.isEmpty()) {
            return;
        }
        String bookTitle = chapters.get(0).getTitle();
        cache.saveBookSnapshot(tocUrl, bookTitle, rule == null ? "" : rule.getRuleName(), chapters);
    }

    private static boolean hasCached(ChapterCache cache, String tocUrl, List<Chapter> chapters,
                                     int index) {
        return cache != null && index >= 0 && index < chapters.size()
                && cache.hasChapter(tocUrl, chapters.get(index).getUrl());
    }

    /**
     * 找离 {@code startIndex} 最近的已缓存章节：<b>先后找、再往前找</b>
     * （往后更贴近「继续读下去」的意图）。
     *
     * @return 找到的下标；一个都没缓存时返回 -1
     */
    private static int nearestCachedChapter(String tocUrl, List<Chapter> chapters, int startIndex,
                                            ChapterCache cache) {
        if (cache == null) {
            return -1;
        }
        for (int i = Math.max(startIndex, 0); i < chapters.size(); i++) {
            if (cache.hasChapter(tocUrl, chapters.get(i).getUrl())) {
                return i;
            }
        }
        for (int i = Math.min(startIndex - 1, chapters.size() - 1); i >= 0; i--) {
            if (cache.hasChapter(tocUrl, chapters.get(i).getUrl())) {
                return i;
            }
        }
        return -1;
    }

    private static String join(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        return first + "\n" + second;
    }

    /** 把多行的 IO 错误压成一句能在提示里显示的短句。 */
    static String brief(Exception failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "网络异常";
        }
        String oneLine = message.trim().split("\\r?\\n")[0].trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }

    /**
     * 解析命中该 URL 的<b>全部</b>候选规则（按规则文件顺序）。
     *
     * <p>规则文件可能在历史记录产生之后被用户改过甚至删掉，因此这里做得比较宽容：
     * 规则文件缺失时自动写出默认规则再读，读不出来就抛清晰错误而不是 NPE。
     *
     * <p>返回列表而非单条，是为了让站点改版后能自动换下一条候选规则重试
     * （见 {@link ChapterReader#loadTocWithFallback}）。
     */
    public static List<NovelRule> matchCandidates(String tocUrl) throws IOException {
        NovelReaderSettings settings = NovelReaderSettings.getInstance();
        Path rulesFile = settings.getRulesFile();
        if (!Files.exists(rulesFile)) {
            RuleLoader.writeTemplateIfAbsent(rulesFile);
        }
        RuleLoader.RuleSet ruleSet = RuleLoader.load(rulesFile);
        List<NovelRule> candidates = RuleLoader.matchAll(ruleSet.getRules(), tocUrl);
        if (candidates.isEmpty()) {
            throw new IOException("没有匹配 " + com.novelreader.util.UrlUtil.host(tocUrl)
                    + " 的规则，无法重新解析目录。\n规则文件：" + rulesFile);
        }
        return candidates;
    }

    /**
     * 解析命中该 URL 的第一条规则（保留给只需要单条规则的调用方）。
     */
    public static NovelRule matchRule(String tocUrl) throws IOException {
        return matchCandidates(tocUrl).get(0);
    }

    /**
     * 用 URL 优先、下标兜底的策略算出章节下标。
     *
     * <p>URL 命中说明「上次那一章」还在，即使站点在它前面插了章节也依然精确；
     * URL 找不到才退回存下来的下标，并夹到合法范围。
     *
     * <p>public 是为了让测试直接断言这套定位策略（测试在 {@code com.novelreader} 包下）。
     */
    public static int resolveChapterIndex(List<Chapter> chapters, String chapterUrl, int wantChapter) {
        if (chapters == null || chapters.isEmpty()) {
            return 0;
        }
        String url = chapterUrl == null ? "" : chapterUrl.trim();
        if (!url.isEmpty()) {
            for (int i = 0; i < chapters.size(); i++) {
                if (url.equals(chapters.get(i).getUrl())) {
                    return i;
                }
            }
        }
        return Math.max(0, Math.min(wantChapter, chapters.size() - 1));
    }

    /**
     * 段游标夹取：越界时退到最后一段而不是第 1 段，避免「继续阅读」反而倒退。
     *
     * <p>public 供测试断言。
     */
    public static int clampSegment(int wantSegment, int segmentCount) {
        if (segmentCount <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(wantSegment, segmentCount - 1));
    }

    /** 供展示：章节 URL 列表。 */
    static List<String> urls(List<Chapter> chapters) {
        List<String> urls = new ArrayList<>();
        if (chapters != null) {
            for (Chapter chapter : chapters) {
                urls.add(chapter.getUrl());
            }
        }
        return urls;
    }
}
