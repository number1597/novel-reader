package com.novelreader.bookmark;

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
import java.util.List;

/**
 * 书签的持久化（应用级服务）。
 *
 * <p>落盘位置：{@code {IDEA 配置目录}/novelReader/bookmarks.json}，与规则文件、阅读历史同目录，
 * 用户要备份 / 清理时只管这一个文件夹。
 *
 * <h3>为什么不复用 {@code @State}</h3>
 * 书签是<b>会增长的列表</b>，且用户可能想直接看 / 改 / 删。用独立 JSON 文件与
 * 现有规则文件、历史文件保持一致的心智模型，也便于排障
 * （{@code @State} 只适合设置类的小数据）。
 *
 * <h3>写入策略与容错</h3>
 * 每次变更整体重写，走「先写临时文件再原子替换」，避免写到一半崩溃留下半个 JSON。
 * 写入失败<b>不抛给调用方</b>，读取失败一律退化为空书签 ——
 * <b>书签丢失是小事，打断阅读是大事</b>。
 */
@Service(Service.Level.APP)
public final class BookmarkStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 书签文件的内存模型（Gson 顶层结构）。 */
    static class BookmarkFile {
        int version = 1;
        List<BookmarkEntry> entries = new java.util.ArrayList<>();
    }

    private final Object lock = new Object();

    /** 书签文件路径；测试可注入覆盖。 */
    private final Path file;

    /** 内存中的书签，避免每次读取都碰磁盘。 */
    private BookmarkList cache;

    public BookmarkStore() {
        this(defaultBookmarkFile());
    }

    BookmarkStore(Path file) {
        this.file = file;
    }

    public static BookmarkStore getInstance() {
        return ApplicationManager.getApplication().getService(BookmarkStore.class);
    }

    /** 书签文件的默认位置：{IDEA 配置目录}/novelReader/bookmarks.json。 */
    public static Path defaultBookmarkFile() {
        return Paths.get(PathManager.getConfigPath(), "novelReader", "bookmarks.json");
    }

    public Path getBookmarkFile() {
        return file;
    }

    /** 当前书签快照（按创建顺序）。读取失败视为空。 */
    public BookmarkList getBookmarks() {
        synchronized (lock) {
            if (cache == null) {
                cache = readOrEmpty();
            }
            return cache;
        }
    }

    /**
     * 添加 / 更新一条书签并立即落盘。
     *
     * @return 写入后的条目副本；参数非法或写入失败时为 null
     */
    public BookmarkEntry add(BookmarkEntry entry) {
        synchronized (lock) {
            BookmarkList bookmarks = getBookmarks();
            BookmarkEntry saved = bookmarks.add(entry);
            if (saved != null) {
                persist(bookmarks);
            }
            return saved;
        }
    }

    /**
     * 按位置标识删除一条书签并立即落盘。
     *
     * @return true 表示确实删掉了一条
     */
    public boolean remove(String key) {
        synchronized (lock) {
            BookmarkList bookmarks = getBookmarks();
            boolean removed = bookmarks.remove(key);
            if (removed) {
                persist(bookmarks);
            }
            return removed;
        }
    }

    /** 清空全部书签并立即落盘。 */
    public void clear() {
        synchronized (lock) {
            BookmarkList bookmarks = getBookmarks();
            bookmarks.clear();
            persist(bookmarks);
        }
    }

    /** 当前书签总数。 */
    public int size() {
        return getBookmarks().size();
    }

    /** 某本书的书签，按阅读顺序排列。 */
    public List<BookmarkEntry> forBook(String tocUrl) {
        return getBookmarks().forBook(tocUrl);
    }

    /** 丢弃内存缓存，下次读取重新从磁盘加载（供测试与「刷新」用）。 */
    public void invalidate() {
        synchronized (lock) {
            cache = null;
        }
    }

    // ---------- 磁盘读写 ----------

    private BookmarkList readOrEmpty() {
        if (file == null || !Files.exists(file)) {
            return BookmarkList.empty();
        }
        try {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (raw.trim().isEmpty()) {
                return BookmarkList.empty();
            }
            // 复用规则文件那套宽松解析：允许注释与尾随逗号，方便用户手改
            BookmarkFile parsed = GSON.fromJson(RuleLoader.sanitize(raw), BookmarkFile.class);
            if (parsed == null || parsed.entries == null) {
                return BookmarkList.empty();
            }
            return new BookmarkList(parsed.entries);
        } catch (IOException | JsonSyntaxException e) {
            // 文件损坏时不让插件起不来：退回空书签
            return BookmarkList.empty();
        }
    }

    private void persist(BookmarkList bookmarks) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BookmarkFile model = new BookmarkFile();
            model.entries = new java.util.ArrayList<>(bookmarks.snapshot());
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
            // 书签写不进去不该打断阅读，静默失败即可
        }
    }
}
