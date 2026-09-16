package com.novelreader.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一章正文的离线缓存条目。
 *
 * <p>存的就是「展示一章所需的全部东西」：标题 + 已经切好的分段 + 页数。
 * 分段也一并存下来（而不是存原始正文再重新切），是为了让缓存命中时的结果
 * 与在线抓取<b>逐字段一致</b> —— 否则用户改过「每段最大字数」后，
 * 同一章在线读和离线读的段落划分会不一样，看起来像 bug。
 *
 * <p>只做纯数据，字段用 public 可变风格以便 Gson 直接序列化
 * （与 {@code ReadingHistoryEntry} / {@code BookmarkEntry} 一致）。
 */
public class CachedChapter {

    /** 所属书的目录页 URL。 */
    public String tocUrl = "";

    /** 章节 URL：缓存的真正键（章节 URL 全局唯一）。 */
    public String chapterUrl = "";

    /** 章节标题（正文页解析出的，回退为目录里的名字）。 */
    public String chapterTitle = "";

    /** 已切分好的正文分段。 */
    public List<String> segments = new ArrayList<>();

    /** 抓取时实际拼接的页数，0 表示来源异常（仅用于展示提示）。 */
    public int pages;

    /**
     * 切分这一段正文时用的「每段最大字数」。
     *
     * <p>分段是<b>在写缓存时就切好的</b>，所以用户后来改了「每段最大字数」，
     * 缓存里的段落划分就和新设置对不上了。因此读缓存前会拿它跟当前设置比一次，
     * 不一致就当作未命中、重新抓取（顺带按新设置重新切）。
     * {@code 0} 表示未知（手改的缓存文件），此时不校验、直接采信。
     */
    public int maxChars;

    /** 写入时间（毫秒时间戳），便于将来做「过期」策略与排障。 */
    public long fetchedAt;

    public CachedChapter() {
    }

    public String getTocUrl() {
        return normalize(tocUrl);
    }

    public String getChapterUrl() {
        return normalize(chapterUrl);
    }

    public String getChapterTitle() {
        return normalize(chapterTitle);
    }

    public List<String> getSegments() {
        return segments == null ? Collections.emptyList() : Collections.unmodifiableList(segments);
    }

    public int getPages() {
        return Math.max(0, pages);
    }

    public int getMaxChars() {
        return Math.max(0, maxChars);
    }

    /** 是否能用当前设置直接采信：切分参数一致，或缓存里没记（手改的文件）。 */
    public boolean matchesSplit(int currentMaxChars) {
        return maxChars <= 0 || maxChars == currentMaxChars;
    }

    public long getFetchedAt() {
        return fetchedAt;
    }

    /** 正文为空（或没 URL）的缓存没有意义：读出来只会是空白页。 */
    public boolean isValid() {
        return !getChapterUrl().isEmpty() && !getSegments().isEmpty();
    }

    public boolean isEmpty() {
        return getSegments().isEmpty();
    }

    /** 便于阅读与测试：分段数与首段摘要。 */
    @Override
    public String toString() {
        return "CachedChapter{" + getChapterTitle() + ", " + getSegments().size() + " 段}";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
