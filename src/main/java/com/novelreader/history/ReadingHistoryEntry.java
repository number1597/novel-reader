package com.novelreader.history;

import com.novelreader.util.PositionResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条阅读历史：一本小说的「书签」。
 *
 * <p>目标很明确 —— <b>下次打开 IDEA 时，能凭这条记录回到上次读到的确切位置</b>。
 * 因此这里存的不是正文，而只是「怎么找回这本书」所需的最小信息：
 * <ul>
 *   <li>{@link #getTocUrl()}：目录页 URL，用来重新解析章节目录；</li>
 *   <li>{@link #getChapterIndex()} + {@link #getSegmentIndex()}：上次读到的章 / 段；</li>
 *   <li>{@link #getChapterUrl()}：上次那一章的 URL。有了它，即使站点目录改版、
 *       章节顺序变了，也能凭 URL 直接定位到那一章（见 {@link #resolveChapterIndex});</li>
 *   <li>{@link #getRuleName()}：命中过的规则名，仅用于展示与排障。</li>
 * </ul>
 *
 * <p>本类<b>只做纯数据与纯逻辑</b>：不碰文件、不碰 IntelliJ API、不发网络请求，
 * 因此可以完全用普通单元测试覆盖。持久化交给 {@link ReadingHistoryStore}。
 *
 * <p>字段全部使用 public 可变风格，是为了让 Gson 能直接序列化，
 * 避免再维护一套 DTO 映射（与 {@code NovelRule} 保持一致的风格）。
 */
public class ReadingHistoryEntry {

    /** 目录页 URL：这本书的唯一标识，也是重新加载的入口。 */
    public String tocUrl = "";

    /** 小说名（通常取第 1 章的标题或站点书名，用于列表展示）。 */
    public String title = "";

    /** 上次读取的章节标题，用于列表展示。 */
    public String chapterTitle = "";

    /** 上次读取的章节下标（从 0 开始）。 */
    public int chapterIndex;

    /** 上次读取的段下标（从 0 开始）。 */
    public int segmentIndex;

    /** 上次那一章的 URL；用于目录改版后仍能定位到同一章。 */
    public String chapterUrl = "";

    /** 章节总数，仅用于展示。 */
    public int chapterCount;

    /** 命中过的规则名，便于排障。 */
    public String ruleName = "";

    /** 最后一次阅读时间（毫秒时间戳），用于列表倒序排列。 */
    public long lastReadAt;

    public ReadingHistoryEntry() {
    }

    public ReadingHistoryEntry(String tocUrl, String title) {
        this.tocUrl = normalize(tocUrl);
        this.title = normalize(title);
    }

    public String getTocUrl() {
        return normalize(tocUrl);
    }

    public String getTitle() {
        return normalize(title);
    }

    public String getChapterTitle() {
        return normalize(chapterTitle);
    }

    public int getChapterIndex() {
        return Math.max(0, chapterIndex);
    }

    public int getSegmentIndex() {
        return Math.max(0, segmentIndex);
    }

    public String getChapterUrl() {
        return normalize(chapterUrl);
    }

    public int getChapterCount() {
        return Math.max(0, chapterCount);
    }

    public String getRuleName() {
        return normalize(ruleName);
    }

    public long getLastReadAt() {
        return lastReadAt;
    }

    /**
     * 列表展示文案。
     *
     * <p>形如 {@code 书名 · 第 12 章 xxx · 第 3 段}，章节目录缺失时退化为只有书名。
     */
    public String displayLabel() {
        String name = getTitle();
        if (name.isEmpty()) {
            name = getTocUrl();
        }
        StringBuilder sb = new StringBuilder(name);
        String chapter = getChapterTitle();
        if (!chapter.isEmpty()) {
            sb.append("  ·  第 ").append(getChapterIndex() + 1).append(" 章 ").append(chapter);
        }
        if (getChapterCount() > 0) {
            sb.append("  ·  第 ").append(getSegmentIndex() + 1).append(" 段");
        }
        return sb.toString();
    }

    /** 是否与另一条记录指向同一本书（同一个目录页 URL）。 */
    public boolean sameBook(ReadingHistoryEntry other) {
        return other != null && !getTocUrl().isEmpty() && getTocUrl().equals(other.getTocUrl());
    }

    public boolean isValid() {
        return !getTocUrl().isEmpty();
    }

    /**
     * 用新会话的进度覆盖本书签的进度。
     *
     * @param chapterIndex     当前章节下标
     * @param segmentIndex     当前段下标
     * @param chapterTitle     当前章节标题
     * @param chapterUrl       当前章节 URL
     * @param chapterCount     章节总数
     * @param now              当前时间戳（由调用方注入，便于测试）
     */
    public void updateProgress(int chapterIndex, int segmentIndex, String chapterTitle,
                               String chapterUrl, int chapterCount, long now) {
        this.chapterIndex = Math.max(0, chapterIndex);
        this.segmentIndex = Math.max(0, segmentIndex);
        this.chapterTitle = normalize(chapterTitle);
        this.chapterUrl = normalize(chapterUrl);
        this.chapterCount = Math.max(0, chapterCount);
        this.lastReadAt = now;
    }

    /**
     * 把「上次读到的章节」解析为当前章节目录里的下标。
     *
     * <p>优先按 {@link #getChapterUrl()} 精确匹配 —— 站点目录改版增删章节后，
     * 单靠下标会跳错章，而 URL 是稳定的。URL 匹配不到时再退回用下标
     * （并夹到合法范围），最后兜底为第 0 章。
     *
     * @param chapterUrls 当前解析出的章节 URL 列表，顺序与章节一致
     * @return 合法的章节下标，保证落在 {@code [0, max(0, size-1)]}
     */
    public int resolveChapterIndex(List<String> chapterUrls) {
        return PositionResolver.resolveChapterIndex(chapterUrls, getChapterUrl(), getChapterIndex());
    }

    /**
     * 把段落游标夹到本章实际段数的合法范围。
     *
     * <p>站点正文长度可能变化，存下来的段号未必还在；越界时退到最后一段
     * （而不是第 1 段），这样「继续阅读」不会倒退。
     */
    public int resolveSegmentIndex(int segmentCount) {
        return PositionResolver.resolveSegmentIndex(getSegmentIndex(), segmentCount);
    }

    /** 深拷贝，避免 store 把内部实例直接暴露出去被外部改坏。 */
    public ReadingHistoryEntry copy() {
        ReadingHistoryEntry copy = new ReadingHistoryEntry();
        copy.tocUrl = getTocUrl();
        copy.title = getTitle();
        copy.chapterTitle = getChapterTitle();
        copy.chapterIndex = getChapterIndex();
        copy.segmentIndex = getSegmentIndex();
        copy.chapterUrl = getChapterUrl();
        copy.chapterCount = getChapterCount();
        copy.ruleName = getRuleName();
        copy.lastReadAt = lastReadAt;
        return copy;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /** 便于日志与测试阅读。 */
    @Override
    public String toString() {
        return "ReadingHistoryEntry{" + displayLabel() + "}";
    }

    /** 保证给 Gson 反序列化后 list 字段不为 null（本类暂无用，保留给将来扩展）。 */
    static <T> List<T> orEmpty(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
