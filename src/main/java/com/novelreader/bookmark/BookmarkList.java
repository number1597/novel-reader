package com.novelreader.bookmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 书签集合的内存模型。
 *
 * <p>刻意<b>不做持久化</b>（读写 JSON 交给 {@link BookmarkStore}），只负责列表本身的
 * 去重、排序、上限与删除 —— 这部分最容易写错（重复条目、顺序错乱、越界裁剪），
 * 单独抽出来用普通单元测试覆盖最划算。
 *
 * <h3>去重</h3>
 * 以 {@link BookmarkEntry#key()}（书 + 章 + 段）为位置标识。在同一个位置再次添加
 * 不会新增条目，而是更新那一条 —— 否则用户想「补个备注」就会留下两条一样的记录。
 *
 * <h3>两种顺序，别混用</h3>
 * <ul>
 *   <li>{@link #snapshot()} 保持<b>创建顺序</b>，用于落盘 —— 文件内容稳定，
 *       用户手看 / 手改 JSON 时不会因为顺序乱跳而困惑；</li>
 *   <li>{@link #forBook(String)} 按<b>位置</b>排序（章 → 段），用于展示 ——
 *       跳转列表里按阅读顺序排列才符合直觉。</li>
 * </ul>
 */
public class BookmarkList {

    /** 最多保留的书签数，超出时丢弃最早创建的。 */
    public static final int MAX_ENTRIES = 200;

    private final List<BookmarkEntry> entries = new ArrayList<>();

    public BookmarkList(List<BookmarkEntry> initial) {
        if (initial != null) {
            for (BookmarkEntry entry : initial) {
                if (entry != null && entry.isValid()) {
                    add(entry);
                }
            }
        }
    }

    public static BookmarkList empty() {
        return new BookmarkList(null);
    }

    /** 按创建顺序返回副本，外部改动不影响内部状态（落盘用）。 */
    public List<BookmarkEntry> snapshot() {
        List<BookmarkEntry> copy = new ArrayList<>(entries.size());
        for (BookmarkEntry entry : entries) {
            copy.add(entry.copy());
        }
        return Collections.unmodifiableList(copy);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 按位置标识查书签；找不到返回 null。 */
    public BookmarkEntry find(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (BookmarkEntry entry : entries) {
            if (key.equals(entry.key())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 添加一条书签。
     *
     * <p>同一位置已存在时<b>就地更新</b>：章节标题、书名、摘录一律刷新；
     * 备注则「非空才覆盖」—— 用户重复点添加时多半没打算清掉已写好的备注。
     * 创建时间保持不变，保留「第一次标记」的语义。
     *
     * @return 存下来的那条（内部实例的副本）；参数非法时为 null
     */
    public BookmarkEntry add(BookmarkEntry entry) {
        if (entry == null || !entry.isValid()) {
            return null;
        }
        BookmarkEntry existing = find(entry.key());
        if (existing != null) {
            existing.bookTitle = entry.getBookTitle();
            existing.chapterTitle = entry.getChapterTitle();
            existing.snippet = entry.getSnippet();
            existing.chapterIndex = entry.getChapterIndex();
            existing.segmentIndex = entry.getSegmentIndex();
            if (!entry.getNote().isEmpty()) {
                existing.note = entry.getNote();
            }
            return existing.copy();
        }
        BookmarkEntry stored = entry.copy();
        entries.add(stored);
        trim();
        return stored.copy();
    }

    /** 按位置标识删除。 */
    public boolean remove(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        return entries.removeIf(entry -> key.equals(entry.key()));
    }

    /** 某本书的全部书签，按阅读顺序（章 → 段）排列。 */
    public List<BookmarkEntry> forBook(String tocUrl) {
        String key = tocUrl == null ? "" : tocUrl.trim();
        List<BookmarkEntry> picked = new ArrayList<>();
        if (key.isEmpty()) {
            return picked;
        }
        for (BookmarkEntry entry : entries) {
            if (key.equals(entry.getTocUrl())) {
                picked.add(entry.copy());
            }
        }
        picked.sort(Comparator.comparingInt(BookmarkEntry::getChapterIndex)
                .thenComparingInt(BookmarkEntry::getSegmentIndex));
        return picked;
    }

    /** 这本书有几条书签。 */
    public int countForBook(String tocUrl) {
        String key = tocUrl == null ? "" : tocUrl.trim();
        if (key.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (BookmarkEntry entry : entries) {
            if (key.equals(entry.getTocUrl())) {
                count++;
            }
        }
        return count;
    }

    public void clear() {
        entries.clear();
    }

    /** 超出上限时丢弃最早创建的（按 createdAt，同时间按原有顺序稳定淘汰）。 */
    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            BookmarkEntry oldest = entries.get(0);
            for (BookmarkEntry entry : entries) {
                if (entry.getCreatedAt() < oldest.getCreatedAt()) {
                    oldest = entry;
                }
            }
            entries.remove(oldest);
        }
    }
}
