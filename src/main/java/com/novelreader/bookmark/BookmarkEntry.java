package com.novelreader.bookmark;

/**
 * 一条书签：书里的一个「可跳回的位置」。
 *
 * <h3>与阅读历史的分工</h3>
 * 阅读历史每本书只有<b>一条</b>（自动记录「上次读到哪」），是「继续读」用的；
 * 书签是每本书<b>若干条</b>（用户主动标注），是「跳回去」用的 —— 比如
 * 「这段伏笔留着回头看」「这里有个有意思的设定」。
 *
 * <p>因此书签额外多了 {@link #note}（备注）与 {@link #snippet}（当时那段的摘录），
 * 让列表里一眼能分辨出两条书签的区别。
 *
 * <p>本类<b>只做纯数据与纯逻辑</b>：不碰文件、不碰 IntelliJ API、不发网络请求，
 * 因此可以完全用普通单元测试覆盖。持久化交给 {@link BookmarkStore}。
 * 字段用 public 可变风格，与 {@code ReadingHistoryEntry} / {@code NovelRule} 一致，
 * 便于 Gson 直接序列化、不必再维护一套 DTO。
 */
public class BookmarkEntry {

    /** 目录页 URL：这本书的唯一标识，也是「这条书签属于哪本书」的依据。 */
    public String tocUrl = "";

    /** 小说名（取第 1 章标题，与历史一致），用于跨书展示时区分。 */
    public String bookTitle = "";

    /** 书签所在章节标题。 */
    public String chapterTitle = "";

    /** 书签所在章节下标（从 0 开始）。 */
    public int chapterIndex;

    /** 书签所在段下标（从 0 开始）。 */
    public int segmentIndex;

    /** 书签所在章节的 URL；用于目录改版后仍能定位到同一章。 */
    public String chapterUrl = "";

    /** 当时那一段的开头摘录，帮助区分同章内的多条书签；可为空。 */
    public String snippet = "";

    /** 用户备注；可为空（留空时按章 / 段自动描述）。 */
    public String note = "";

    /** 创建时间（毫秒时间戳），用于超限时淘汰最旧的。 */
    public long createdAt;

    public BookmarkEntry() {
    }

    public String getTocUrl() {
        return normalize(tocUrl);
    }

    public String getBookTitle() {
        return normalize(bookTitle);
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

    public String getSnippet() {
        return normalize(snippet);
    }

    public String getNote() {
        return normalize(note);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 唯一标识：同一本书的同一章同一段，视为同一个位置。
     *
     * <p>章节用 <b>URL 优先</b>（站点改版后同一位置的下标会漂移）；没有 URL 时才退回下标。
     * 这两个字段一起构成 key，所以「在同一个地方再按一次添加书签」只会更新那一条，
     * 而不会堆出重复项。
     */
    public String key() {
        String chapterKey = getChapterUrl().isEmpty()
                ? "#" + getChapterIndex()
                : getChapterUrl();
        return getTocUrl() + "\u0001" + chapterKey + "\u0001" + getSegmentIndex();
    }

    /** 是否与另一条书签指向同一个位置。 */
    public boolean samePosition(BookmarkEntry other) {
        return other != null && !getTocUrl().isEmpty() && key().equals(other.key());
    }

    /** 没有目录页 URL 就无法确定属于哪本书，不能作为书签。 */
    public boolean isValid() {
        return !getTocUrl().isEmpty();
    }

    /**
     * 列表展示文案。
     *
     * <p>形如 {@code 备注  ·  第 12 章 中举  ·  第 3 段  ·  摘录}；
     * 备注与摘录都是可选的，缺失时自动省略对应片段。
     */
    public String displayLabel() {
        StringBuilder sb = new StringBuilder();
        String trimmedNote = getNote();
        if (!trimmedNote.isEmpty()) {
            sb.append(trimmedNote).append("  ·  ");
        }
        sb.append("第 ").append(getChapterIndex() + 1).append(" 章");
        String chapter = getChapterTitle();
        if (!chapter.isEmpty()) {
            sb.append(" ").append(chapter);
        }
        sb.append("  ·  第 ").append(getSegmentIndex() + 1).append(" 段");

        String text = getSnippet();
        if (!text.isEmpty()) {
            sb.append("  ·  ").append(text);
        }
        return sb.toString();
    }

    /** 深拷贝，避免 store 把内部实例直接暴露出去被外部改坏。 */
    public BookmarkEntry copy() {
        BookmarkEntry copy = new BookmarkEntry();
        copy.tocUrl = getTocUrl();
        copy.bookTitle = getBookTitle();
        copy.chapterTitle = getChapterTitle();
        copy.chapterIndex = getChapterIndex();
        copy.segmentIndex = getSegmentIndex();
        copy.chapterUrl = getChapterUrl();
        copy.snippet = getSnippet();
        copy.note = getNote();
        copy.createdAt = createdAt;
        return copy;
    }

    /**
     * 取一段正文的开头做摘录。
     *
     * <p>会把换行 / 连续空白压成单个空格 —— 列表是单行展示，留换行只会显示成方块。
     *
     * <p>public 是为了让测试直接断言截断与空白压缩这两个容易写错的地方。
     *
     * @param text     原始文本；null / 空返回空串
     * @param maxChars 最多保留的字符数（按 UTF-16 code unit，与 Swing 口径一致）；<=0 视为不截断
     */
    public static String snippet(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (maxChars <= 0 || collapsed.length() <= maxChars) {
            return collapsed;
        }
        return collapsed.substring(0, maxChars) + "…";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "BookmarkEntry{" + displayLabel() + "}";
    }
}
