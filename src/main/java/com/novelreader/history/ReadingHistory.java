package com.novelreader.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 阅读历史的内存模型：一份按「最近阅读时间」倒序排列的书签列表。
 *
 * <p>本类刻意<b>不做持久化</b>（读写 JSON 交给 {@link ReadingHistoryStore}），
 * 只负责列表本身的增删改排序 —— 这部分逻辑最容易出错（重复条目、顺序错乱、
 * 上限裁剪），单独抽出来用普通单元测试覆盖最划算。
 *
 * <h3>去重规则</h3>
 * 以<b>目录页 URL</b> 为书的唯一标识。同一本书再次阅读时<b>不新增条目</b>，
 * 而是更新原有条目的进度并把它顶到最前面，避免列表里堆满同一本书。
 *
 * <h3>上限</h3>
 * 最多保留 {@link #MAX_ENTRIES} 条，超出时丢弃最久未读的，防止历史无限增长。
 */
public class ReadingHistory {

    /** 最多保留的历史条数。 */
    public static final int MAX_ENTRIES = 50;

    private final List<ReadingHistoryEntry> entries = new ArrayList<>();

    public ReadingHistory(List<ReadingHistoryEntry> initial) {
        if (initial != null) {
            for (ReadingHistoryEntry entry : initial) {
                if (entry != null && entry.isValid()) {
                    entries.add(entry);
                }
            }
        }
        sortByRecency();
    }

    public static ReadingHistory empty() {
        return new ReadingHistory(null);
    }

    /** 返回按最近阅读时间倒序排列的副本，外部改动不影响内部状态。 */
    public List<ReadingHistoryEntry> snapshot() {
        List<ReadingHistoryEntry> copy = new ArrayList<>(entries.size());
        for (ReadingHistoryEntry entry : entries) {
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

    /** 按目录页 URL 查条目；找不到返回 null。 */
    public ReadingHistoryEntry find(String tocUrl) {
        String key = normalize(tocUrl);
        if (key.isEmpty()) {
            return null;
        }
        for (ReadingHistoryEntry entry : entries) {
            if (key.equals(entry.getTocUrl())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 记录 / 更新一本小说的阅读进度。
     *
     * <p>同一本书（目录页 URL 相同）只保留一条：已有的更新进度，没有的新建。
     * 无论哪种情况，更新后都会被顶到列表最前面。
     *
     * @param tocUrl       目录页 URL
     * @param title        小说名
     * @param chapterIndex 当前章节下标
     * @param segmentIndex 当前段下标
     * @param chapterTitle 当前章节标题
     * @param chapterUrl   当前章节 URL
     * @param chapterCount 章节总数
     * @param ruleName     命中的规则名
     * @param now          当前时间戳
     * @return 被写入的那条记录（内部实例的副本）
     */
    public ReadingHistoryEntry record(String tocUrl, String title,
                                      int chapterIndex, int segmentIndex,
                                      String chapterTitle, String chapterUrl,
                                      int chapterCount, String ruleName, long now) {
        String key = normalize(tocUrl);
        if (key.isEmpty()) {
            return null;
        }
        ReadingHistoryEntry entry = find(key);
        if (entry == null) {
            entry = new ReadingHistoryEntry(key, title);
            entries.add(entry);
        } else if (entry.getTitle().isEmpty()) {
            entry.title = normalize(title);
        }
        if (!normalize(ruleName).isEmpty()) {
            entry.ruleName = normalize(ruleName);
        }
        entry.updateProgress(chapterIndex, segmentIndex, chapterTitle, chapterUrl, chapterCount, now);

        sortByRecency();
        trim();
        return entry.copy();
    }

    /**
     * 删除一条历史（按目录页 URL 定位）。
     *
     * @return true 表示确实删掉了一条
     */
    public boolean remove(String tocUrl) {
        String key = normalize(tocUrl);
        if (key.isEmpty()) {
            return false;
        }
        return entries.removeIf(entry -> key.equals(entry.getTocUrl()));
    }

    /** 按列表中的位置删除，便于弹窗按展示顺序操作。 */
    public boolean removeAt(int position) {
        if (position < 0 || position >= entries.size()) {
            return false;
        }
        entries.remove(position);
        return true;
    }

    public void clear() {
        entries.clear();
    }

    /** 最近阅读时间倒序；时间相同时按插入顺序稳定排列。 */
    private void sortByRecency() {
        entries.sort(Comparator.comparingLong(ReadingHistoryEntry::getLastReadAt).reversed());
    }

    /** 超出上限时丢弃最久未读的尾部条目。 */
    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
