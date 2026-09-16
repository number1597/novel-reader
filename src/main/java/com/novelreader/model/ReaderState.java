package com.novelreader.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次阅读会话的状态：整本书的章节目录 + 当前章节的分段与游标。
 *
 * <p>会话开始时先拿到完整章节目录（{@link #getChapters()}），随后逐章抓取正文。
 * 每章正文一次性切分为 {@link #getSegments()}，段内翻页只移动 {@link #getIndex()}；
 * 换章则由 {@link #applyChapter(int, String, List)} 整体替换当前章节内容并把游标复位。
 *
 * <p>本类<b>只保存纯内存状态</b>，不发起网络请求、不触碰 IntelliJ 平台 API，
 * 因此跨章导航逻辑可以直接用普通单元测试覆盖（见 {@code ReaderStateTest}）。
 * 真正抓取下一章的 I/O 由 {@code ReaderManager} 负责。
 */
public class ReaderState {

    /** 章节目录；为空表示「单章模式」（直接阅读某一章，没有整本书上下文）。 */
    private final List<Chapter> chapters;

    /** 命中该站点的解析规则，抓取后续章节时复用；单章模式下为 null。 */
    private final NovelRule rule;

    /** 目录页 URL，仅用于展示与排查。 */
    private final String tocUrl;

    /** 当前章节在 {@link #chapters} 中的下标；单章模式下恒为 0。 */
    private int chapterIndex;

    private String chapterTitle;

    private List<String> segments;

    /** 当前段游标。 */
    private int index;

    /** 单章模式：没有章节目录。 */
    public ReaderState(String chapterTitle, List<String> segments) {
        this(Collections.emptyList(), 0, chapterTitle, segments, null, "");
    }

    /**
     * 完整会话。
     *
     * @param chapters     章节目录；为空表示单章模式
     * @param chapterIndex 当前章节在 chapters 中的下标（会被规整到合法范围）
     * @param chapterTitle 当前章节标题
     * @param segments     当前章节切分后的正文分段
     * @param rule         命中的解析规则，用于抓取后续章节；可为 null
     * @param tocUrl       目录页 URL
     */
    public ReaderState(List<Chapter> chapters, int chapterIndex, String chapterTitle,
                       List<String> segments, NovelRule rule, String tocUrl) {
        this.chapters = chapters == null ? new ArrayList<>() : new ArrayList<>(chapters);
        this.chapterIndex = clampIndex(chapterIndex);
        this.chapterTitle = chapterTitle == null ? "" : chapterTitle;
        this.segments = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
        this.rule = rule;
        this.tocUrl = tocUrl == null ? "" : tocUrl;
        this.index = 0;
    }

    private int clampIndex(int value) {
        if (chapters.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(value, chapters.size() - 1));
    }

    // ---------- 当前章节内容 ----------

    public String getChapterTitle() {
        return chapterTitle;
    }

    public List<String> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    /**
     * 整章正文：各段原样拼回。
     *
     * <p>{@code PaginationSplitter} 的切分是<b>无损</b>的（不丢字、也不加分隔符），
     * 所以这里拼出来就是原文，可以放心整段复制。阅读面板显示整章走的正是这里。
     *
     * <p>用 {@code StringBuilder} 而不是 {@code String.join}：分段理论上可能含 {@code null}
     * （外部经 {@code applyChapter} 塞进来的），{@code String.join} 遇到它会直接 NPE，
     * 而这里只跳过即可 —— 与切分器的口径一致。
     */
    public String getFullText() {
        StringBuilder text = new StringBuilder();
        for (String segment : segments) {
            if (segment != null) {
                text.append(segment);
            }
        }
        return text.toString();
    }

    public int getIndex() {
        return index;
    }

    public int getTotal() {
        return segments.size();
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public String currentSegment() {
        return segments.isEmpty() ? "" : segments.get(index);
    }

    public boolean hasNext() {
        return index < segments.size() - 1;
    }

    public boolean hasPrev() {
        return index > 0;
    }

    /** @return true 表示游标确实前移了。 */
    public boolean next() {
        if (!hasNext()) {
            return false;
        }
        index++;
        return true;
    }

    /** @return true 表示游标确实后退了。 */
    public boolean prev() {
        if (!hasPrev()) {
            return false;
        }
        index--;
        return true;
    }

    // ---------- 章节目录 ----------

    public List<Chapter> getChapters() {
        return Collections.unmodifiableList(chapters);
    }

    /** 章节总数；单章模式下为 0。 */
    public int getChapterCount() {
        return chapters.size();
    }

    /** 当前章节下标（从 0 开始）。 */
    public int getChapterIndex() {
        return chapterIndex;
    }

    /** 是否持有章节目录（区别于单章模式）。 */
    public boolean hasChapterList() {
        return !chapters.isEmpty();
    }

    /** 指定下标的章节；越界返回 null。 */
    public Chapter getChapterAt(int position) {
        if (position < 0 || position >= chapters.size()) {
            return null;
        }
        return chapters.get(position);
    }

    /** 当前章节对象；单章模式或越界时为 null。 */
    public Chapter getCurrentChapter() {
        return getChapterAt(chapterIndex);
    }

    /** 是否还有下一章。 */
    public boolean hasNextChapter() {
        return hasChapterList() && chapterIndex < chapters.size() - 1;
    }

    /** 是否还有上一章。 */
    public boolean hasPrevChapter() {
        return hasChapterList() && chapterIndex > 0;
    }

    public NovelRule getRule() {
        return rule;
    }

    public String getTocUrl() {
        return tocUrl;
    }

    // ---------- 换章 ----------

    /**
     * 用新抓取到的章节内容替换当前章节，并把段游标复位到第 1 段。
     *
     * <p>供 {@code ReaderManager} 在抓取完成后调用。{@code newIndex} 越界时忽略本次调用，
     * 返回 false，避免把会话置于非法状态。
     *
     * @return true 表示确实切换了章节
     */
    public boolean applyChapter(int newIndex, String newTitle, List<String> newSegments) {
        if (!chapters.isEmpty() && (newIndex < 0 || newIndex >= chapters.size())) {
            return false;
        }
        if (newSegments == null || newSegments.isEmpty()) {
            return false;
        }
        this.chapterIndex = chapters.isEmpty() ? 0 : newIndex;
        this.chapterTitle = newTitle == null ? "" : newTitle;
        this.segments = new ArrayList<>(newSegments);
        this.index = 0;
        return true;
    }

    /** 把段游标移到本章最后一段；本章为空时不做任何事。 */
    public void moveToLastSegment() {
        if (!segments.isEmpty()) {
            index = segments.size() - 1;
        }
    }

    /** 把段游标移回本章第 1 段。 */
    public void moveToFirstSegment() {
        index = 0;
    }

    /**
     * 把段游标恢复到保存过的位置（用于「阅读历史」继续阅读）。
     *
     * @param target 目标段下标；越界时夹到合法范围
     * @return true 表示游标确实落在 target 上（未被夹取）
     */
    public boolean moveToSegment(int target) {
        if (segments.isEmpty()) {
            index = 0;
            return false;
        }
        int clamped = Math.max(0, Math.min(target, segments.size() - 1));
        index = clamped;
        return clamped == target;
    }

    /**
     * 直接设定当前章节的正文（用于恢复历史：不走网络抓取，直接填入已抓到的内容）。
     *
     * <p>与 {@link #applyChapter(int, String, List)} 的区别是这里<b>不</b>要求
     * {@code newIndex} 必须是「下一章」，用于跳回历史章节；游标保持不动，
     * 由调用方随后用 {@link #moveToSegment(int)} 精确定位。
     *
     * @return true 表示设定成功
     */
    public boolean restoreChapter(int newIndex, String newTitle, List<String> newSegments) {
        if (!chapters.isEmpty() && (newIndex < 0 || newIndex >= chapters.size())) {
            return false;
        }
        if (newSegments == null || newSegments.isEmpty()) {
            return false;
        }
        this.chapterIndex = chapters.isEmpty() ? 0 : newIndex;
        this.chapterTitle = newTitle == null ? "" : newTitle;
        this.segments = new ArrayList<>(newSegments);
        this.index = 0;
        return true;
    }

    /** 便于提示文案：当前章节在目录中的展示序号（从 1 开始）；无目录时返回 0。 */
    public int getChapterNumber() {
        return hasChapterList() ? chapterIndex + 1 : 0;
    }
}
