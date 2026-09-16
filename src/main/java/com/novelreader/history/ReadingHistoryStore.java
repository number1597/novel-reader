package com.novelreader.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.novelreader.parser.RuleLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 阅读历史的持久化（应用级服务）。
 *
 * <p>落盘位置：{@code {IDEA 配置目录}/novelReader/history.json}，与规则文件同目录，
 * 这样一个插件的数据集中在一处，用户要备份 / 清理时只管这一个文件夹。
 *
 * <h3>为什么用文件而不是 PersistentStateComponent</h3>
 * 设置类的小数据用 {@code @State} 存 XML 很合适（见 {@code NovelReaderSettings}），
 * 但历史是<b>可增长的列表</b>且用户可能想直接看 / 改 / 删。用独立 JSON 文件
 * 与现有规则文件保持一致的心智模型，也便于排障。
 *
 * <h3>写入策略</h3>
 * 每次记录进度都整体重写，但走「先写临时文件再原子替换」，避免写到一半崩溃
 * 导致历史文件损坏、下次启动读不出来。写入失败不抛给调用方 ——
 * <b>历史丢失是小事，打断阅读是大事</b>。
 */
@Service(Service.Level.APP)
public final class ReadingHistoryStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 历史文件的内存模型（Gson 顶层结构）。 */
    static class HistoryFile {
        int version = 1;
        java.util.List<ReadingHistoryEntry> entries = new java.util.ArrayList<>();
    }

    private final Object lock = new Object();

    /** 历史文件路径；测试可注入覆盖。 */
    private final Path file;

    /** 内存中的历史，避免每次读取都碰磁盘。 */
    private ReadingHistory cache;

    public ReadingHistoryStore() {
        this(defaultHistoryFile());
    }

    ReadingHistoryStore(Path file) {
        this.file = file;
    }

    public static ReadingHistoryStore getInstance() {
        return ApplicationManager.getApplication().getService(ReadingHistoryStore.class);
    }

    /** 历史文件的默认位置：{IDEA 配置目录}/novelReader/history.json。 */
    public static Path defaultHistoryFile() {
        return Paths.get(PathManager.getConfigPath(), "novelReader", "history.json");
    }

    public Path getHistoryFile() {
        return file;
    }

    /** 当前历史快照（按最近阅读倒序）。读取失败视为空历史。 */
    public ReadingHistory getHistory() {
        synchronized (lock) {
            if (cache == null) {
                cache = readOrEmpty();
            }
            return cache;
        }
    }

    /**
     * 记录 / 更新一本书的阅读进度并立即落盘。
     *
     * @return 写入后的条目副本；参数非法或写入失败时为 null
     */
    public ReadingHistoryEntry record(String tocUrl, String title,
                                      int chapterIndex, int segmentIndex,
                                      String chapterTitle, String chapterUrl,
                                      int chapterCount, String ruleName, long now) {
        synchronized (lock) {
            ReadingHistory history = getHistory();
            ReadingHistoryEntry saved = history.record(tocUrl, title, chapterIndex, segmentIndex,
                    chapterTitle, chapterUrl, chapterCount, ruleName, now);
            persist(history);
            return saved;
        }
    }

    /**
     * 删除一条历史并立即落盘。
     *
     * @return true 表示确实删掉了一条
     */
    public boolean remove(String tocUrl) {
        synchronized (lock) {
            ReadingHistory history = getHistory();
            boolean removed = history.remove(tocUrl);
            if (removed) {
                persist(history);
            }
            return removed;
        }
    }

    /** 清空全部历史并立即落盘。 */
    public void clear() {
        synchronized (lock) {
            ReadingHistory history = getHistory();
            history.clear();
            persist(history);
        }
    }

    /** 当前历史条数。 */
    public int size() {
        return getHistory().size();
    }

    /** 丢弃内存缓存，下次读取重新从磁盘加载（供测试与「刷新」用）。 */
    public void invalidate() {
        synchronized (lock) {
            cache = null;
        }
    }

    // ---------- 磁盘读写 ----------

    private ReadingHistory readOrEmpty() {
        if (file == null || !Files.exists(file)) {
            return ReadingHistory.empty();
        }
        try {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (raw.trim().isEmpty()) {
                return ReadingHistory.empty();
            }
            // 复用规则文件那套宽松解析：允许注释与尾随逗号，方便用户手改
            HistoryFile parsed = GSON.fromJson(RuleLoader.sanitize(raw), HistoryFile.class);
            if (parsed == null || parsed.entries == null) {
                return ReadingHistory.empty();
            }
            return new ReadingHistory(parsed.entries);
        } catch (IOException | JsonSyntaxException e) {
            // 文件损坏时不让插件起不来：退回空历史
            return ReadingHistory.empty();
        }
    }

    private void persist(ReadingHistory history) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            HistoryFile model = new HistoryFile();
            model.entries = new java.util.ArrayList<>(history.snapshot());
            String json = GSON.toJson(model);

            // 先写临时文件再原子替换：避免写到一半失败留下半个 JSON
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // 某些文件系统不支持原子移动，退回普通替换
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // 历史写不进去不该打断阅读，静默失败即可
        }
    }
}
