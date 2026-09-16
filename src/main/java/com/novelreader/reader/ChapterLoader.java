package com.novelreader.reader;

import com.novelreader.cache.CachedChapter;
import com.novelreader.cache.ChapterCache;
import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.parser.ChapterReader;
import com.novelreader.settings.NovelReaderSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 章节抓取：把「章节链接」变成「可直接展示的正文分段」。
 *
 * <p>原先这段逻辑写在 {@code PasteTocUrlAction} 里，加入「自动加载下一章」后
 * {@code ReaderManager} 也需要同样的处理，于是抽到这里统一维护，
 * 避免两处行为漂移（例如标题回退规则、分段字数不一致）。
 *
 * <p>只做 I/O 与纯计算，不依赖通知与 UI，便于在测试中用本地 mock HTTP 服务覆盖。
 */
public final class ChapterLoader {

    private ChapterLoader() {
    }

    /**
     * 按设置构造抓取器（超时 + 重试次数）。
     *
     * <p>集中在这里，避免三处调用点（打开目录、抓正文、恢复历史）各自算一遍
     * 而出现行为漂移。设置里的重试次数是「额外尝试次数」，构造器要的是「总尝试次数」，
     * 因此 +1。
     */
    public static HtmlFetcher newFetcher(NovelReaderSettings settings) {
        if (settings == null) {
            return new HtmlFetcher(NovelReaderSettings.DEFAULT_TIMEOUT_MS);
        }
        return new HtmlFetcher(settings.getRequestTimeoutMs(),
                HtmlFetcher.DEFAULT_MAX_REDIRECTS, settings.getMaxRetries() + 1);
    }

    /** 一次章节抓取的结果。 */
    public static final class Loaded {
        private final String title;
        private final List<String> segments;
        private final int pages;

        Loaded(String title, List<String> segments, int pages) {
            this.title = title;
            this.segments = segments;
            this.pages = pages;
        }

        /** 章节标题；正文页解析不到标题时回退为目录里的章节名。 */
        public String getTitle() {
            return title;
        }

        /** 已按「每段最大字数」切分好的正文分段。 */
        public List<String> getSegments() {
            return Collections.unmodifiableList(segments);
        }

        /** 实际抓取并拼接的页数（一章可能由多页组成）。 */
        public int getPages() {
            return pages;
        }

        public boolean isEmpty() {
            return segments.isEmpty();
        }
    }

    /**
     * 抓取一章并切分为分段（不读缓存、不写缓存）。
     *
     * @param chapter  目录中的章节条目，提供标题与 URL
     * @param rule     解析规则
     * @param settings 提供请求超时与每段字数
     * @return 抓取结果；正文为空时 {@link Loaded#isEmpty()} 为 true
     */
    public static Loaded load(Chapter chapter, NovelRule rule, NovelReaderSettings settings) throws IOException {
        return load("", chapter, rule, settings, null);
    }

    /**
     * 抓取一章，<b>优先使用离线缓存</b>。
     *
     * <h3>为什么是「缓存优先」而不是「失败才用缓存」</h3>
     * 章节正文是静态内容：已经抓过一次的章节再请求一遍纯属浪费，而且一旦断网或站点挂掉，
     * 「网络优先」还得先等超时与重试（默认 3 次）才轮到缓存 —— 离线读一章要等十几秒，
     * 那不叫离线阅读。所以：<b>命中缓存就直接返回，完全不发请求</b>。
     * 需要拿最新内容时，用设置页的「清空缓存」即可。
     *
     * <p>缓存的段落划分是在写入时切好的，因此会校验它当时的「每段最大字数」与当前设置
     * 是否一致（见 {@link CachedChapter#matchesSplit(int)}）：用户改过设置就当作未命中，
     * 重新抓取并按新设置重切，避免同一本书里对新旧章节的切法不一致。
     *
     * @param tocUrl 目录页 URL —— 缓存按书分目录，所以即使有缓存也必须说清是哪本书
     * @param cache  缓存；为 null 表示缓存关闭（此时行为与 {@link #load(Chapter, NovelRule, NovelReaderSettings)} 完全一致）
     * @throws IOException 网络失败；或没有缓存且没有规则可用（离线读未缓存的章节）
     */
    public static Loaded load(String tocUrl, Chapter chapter, NovelRule rule,
                              NovelReaderSettings settings, ChapterCache cache) throws IOException {
        if (chapter == null) {
            throw new IOException("章节为空");
        }
        int maxChars = settings == null
                ? NovelReaderSettings.DEFAULT_MAX_CHARS : settings.getMaxCharsPerPage();

        CachedChapter cached = cache == null ? null : cache.read(tocUrl, chapter.getUrl());
        if (cached != null && cached.matchesSplit(maxChars)) {
            return new Loaded(cached.getChapterTitle(), new ArrayList<>(cached.getSegments()),
                    cached.getPages());
        }
        if (rule == null) {
            // 只有「离线打开的书」会走到这里：目录来自缓存，规则未知
            throw new IOException("这一章还没有离线缓存，而且当前会话没有可用的解析规则。\n\n"
                    + "请联网后重新打开这本书，或用「缓存整本书」先把章节存到本地。");
        }

        HtmlFetcher fetcher = newFetcher(settings);
        ChapterReader reader = new ChapterReader(fetcher);
        ChapterReader.ChapterText text = reader.read(chapter.getUrl(), rule);

        // 正文页里解析出的标题优先；解析不到则回退到目录中的章节名，保证提示可读
        String title = text.getTitle();
        if (title == null || title.isEmpty()) {
            title = chapter.getTitle();
        }
        String body = text.getBody() == null ? "" : text.getBody();
        List<String> segments = PaginationSplitter.split(body, maxChars);

        Loaded loaded = new Loaded(title, segments == null ? new ArrayList<>() : segments,
                text.getPages());
        saveToCache(cache, tocUrl, chapter, loaded, maxChars);
        return loaded;
    }

    /** 抓取成功后写入缓存；只缓存有正文的结果（空正文缓存下来只会是空白页）。 */
    private static void saveToCache(ChapterCache cache, String tocUrl, Chapter chapter,
                                    Loaded loaded, int maxChars) {
        if (cache == null || loaded == null || loaded.isEmpty()) {
            return;
        }
        CachedChapter entry = new CachedChapter();
        entry.tocUrl = tocUrl;
        entry.chapterUrl = chapter.getUrl();
        entry.chapterTitle = loaded.getTitle();
        entry.segments = new ArrayList<>(loaded.getSegments());
        entry.pages = loaded.getPages();
        entry.maxChars = maxChars;
        entry.fetchedAt = System.currentTimeMillis();
        cache.save(entry);
    }
}
