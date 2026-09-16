package com.novelreader.cache;

import com.novelreader.model.Chapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一本书的离线缓存清单（`book.json`）。
 *
 * <h3>为什么目录也要缓存</h3>
 * 「离线阅读」最大的坑不在正文，而在<b>打开</b>这一步：从阅读历史恢复时，
 * 插件会<b>重新解析目录页</b>（因为站点可能增删章节）。没网时这一步直接失败，
 * 于是缓存了再多正文也读不到。所以这里把章节目录、书名与命中的规则名一起存下来，
 * 目录页打不开时直接用它把会话建起来。
 *
 * <p>规则名只用于展示与排障：离线读缓存<b>不需要规则</b>（正文早已解析好），
 * 只有「继续缓存新章节」时才需要它 —— 那时本来也得联网。
 */
public class CachedBook {

    /** 目录页 URL：与历史、书签一致的书标识。 */
    public String tocUrl = "";

    /** 书名（取第 1 章标题），用于列表展示。 */
    public String bookTitle = "";

    /** 命中过的规则名，仅用于展示 / 排障。 */
    public String ruleName = "";

    /** 目录快照：章节标题 + URL，顺序与站点一致。 */
    public List<Chapter> chapters = new ArrayList<>();

    /** 本次写入时间（毫秒时间戳）。 */
    public long cachedAt;

    public CachedBook() {
    }

    public String getTocUrl() {
        return normalize(tocUrl);
    }

    public String getBookTitle() {
        return normalize(bookTitle);
    }

    public String getRuleName() {
        return normalize(ruleName);
    }

    public List<Chapter> getChapters() {
        return chapters == null ? Collections.emptyList()
                : Collections.unmodifiableList(chapters);
    }

    public int getChapterCount() {
        return getChapters().size();
    }

    public long getCachedAt() {
        return cachedAt;
    }

    /** 没有 URL 或没有章节的清单没用（建不出会话）。 */
    public boolean isValid() {
        return !getTocUrl().isEmpty() && !getChapters().isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "CachedBook{" + getBookTitle() + ", " + getChapterCount() + " 章}";
    }
}
