package com.novelreader.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.novelreader.model.Chapter;
import com.novelreader.parser.RuleLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 章节正文的离线缓存（应用级服务）。
 *
 * <h3>为什么需要它</h3>
 * 正文从网上抓一次就够读了：已读过的章节再读一遍还要重新请求，纯属浪费；
 * 站点挂掉或断网时更是完全读不了。缓存下来两件事一起解决。
 *
 * <h3>目录布局</h3>
 * <pre>
 * {IDEA 配置目录}/novelReader/cache/
 *   &lt;bookKey&gt;/
 *     book.json           一本书一份：目录快照 + 书名 + 规则名（离线也能建起会话）
 *     c-&lt;chapterKey&gt;.json  一章一份：标题 + 已切分好的分段 + 页数
 * </pre>
 * <b>一章一个文件</b>是刻意的：如果所有章节塞进一个 JSON，每读一章就要重写整份文件
 * （900 章的书每次翻页写几 MB），既慢又容易在写一半时损坏。
 * 一章一文件则每次只写几 KB，且坏一个文件只影响那一章。
 *
 * <h3>容错</h3>
 * 缓存是<b>锦上添花</b>：读不出来一律视为「未命中」去联网，写不进去静默忽略。
 * 任何情况下都不会因为缓存问题打断阅读，也不会让插件起不来。
 */
@Service(Service.Level.APP)
public final class ChapterCache {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 章节文件名前缀，用来和 `book.json` 区分开。 */
    private static final String CHAPTER_PREFIX = "c-";

    private static final String BOOK_FILE = "book.json";

    private static final String JSON_SUFFIX = ".json";

    /** 缓存根目录；测试可注入覆盖。 */
    private final Path root;

    public ChapterCache() {
        this(defaultCacheDir());
    }

    ChapterCache(Path root) {
        this.root = root;
    }

    public static ChapterCache getInstance() {
        return ApplicationManager.getApplication().getService(ChapterCache.class);
    }

    /**
     * 平台还没起来（例如在普通单元测试里）时返回 {@code null}。
     *
     * <p>缓存必须是可以「没有」的：拿到 null 的调用方就当缓存关闭，
     * 照常联网抓取。这样测试不必为了跑一段加载逻辑去起一个平台。
     */
    public static ChapterCache getInstanceOrNull() {
        Application application = ApplicationManager.getApplication();
        return application == null ? null : application.getService(ChapterCache.class);
    }

    /** 缓存根目录：{IDEA 配置目录}/novelReader/cache。 */
    public static Path defaultCacheDir() {
        return Paths.get(PathManager.getConfigPath(), "novelReader", "cache");
    }

    /**
     * 人类可读的字节大小（设置页与提示文案共用）。
     *
     * <p>纯静态、无副作用，可直接单测。
     */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.ROOT, "%.1f GB",
                bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public Path getRoot() {
        return root;
    }

    /** 某本书的缓存目录；URL 为空时返回 null。 */
    public Path dirFor(String tocUrl) {
        String key = CacheKeys.key(tocUrl);
        if (root == null || key.isEmpty()) {
            return null;
        }
        return root.resolve(key);
    }

    // ---------- 章节 ----------

    /**
     * 读一章的缓存。
     *
     * @return 命中且内容有效时返回条目；未命中 / 损坏 / 空正文返回 {@code null}
     */
    public CachedChapter read(String tocUrl, String chapterUrl) {
        Path file = chapterFile(tocUrl, chapterUrl);
        if (file == null || !Files.exists(file)) {
            return null;
        }
        CachedChapter cached = readJson(file, CachedChapter.class);
        return cached != null && cached.isValid() ? cached : null;
    }

    /** 这一章有没有缓存（不解析正文，仅判文件存在）。 */
    public boolean hasChapter(String tocUrl, String chapterUrl) {
        Path file = chapterFile(tocUrl, chapterUrl);
        return file != null && Files.exists(file);
    }

    /**
     * 写入一章的缓存；已存在则覆盖（正文重抓后应当刷新）。
     *
     * @return true 表示确实写下去了
     */
    public boolean save(CachedChapter chapter) {
        if (chapter == null || !chapter.isValid()) {
            return false;
        }
        Path file = chapterFile(chapter.getTocUrl(), chapter.getChapterUrl());
        return file != null && writeJson(file, chapter);
    }

    /** 删除一章的缓存。 */
    public boolean remove(String tocUrl, String chapterUrl) {
        Path file = chapterFile(tocUrl, chapterUrl);
        if (file == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException ignored) {
            return false;
        }
    }

    // ---------- 书目清单 ----------

    /** 读取某本书的目录清单（离线建会话用）；没有则返回 null。 */
    public CachedBook readBook(String tocUrl) {
        Path dir = dirFor(tocUrl);
        if (dir == null) {
            return null;
        }
        CachedBook book = readJson(dir.resolve(BOOK_FILE), CachedBook.class);
        return book != null && book.isValid() ? book : null;
    }

    /** 写入某本书的目录清单。 */
    public boolean saveBook(CachedBook book) {
        if (book == null || !book.isValid()) {
            return false;
        }
        Path dir = dirFor(book.getTocUrl());
        if (dir == null) {
            return false;
        }
        return writeJson(dir.resolve(BOOK_FILE), book);
    }

    /**
     * 便捷方法：把一份刚刚解析出来的目录写进缓存。
     *
     * <p>「打开一本书」和「从历史恢复」两条路径都要做这件事，与其在两处各拼一遍
     * {@link CachedBook}（漏一个字段就会让离线恢复少点东西），不如集中在这里。
     *
     * @param bookTitle 书名；为空时退回目录 URL（与阅读历史的展示口径一致）
     * @return true 表示确实写下去了
     */
    public boolean saveBookSnapshot(String tocUrl, String bookTitle, String ruleName,
                                    List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return false;
        }
        CachedBook book = new CachedBook();
        book.tocUrl = tocUrl == null ? "" : tocUrl;
        book.bookTitle = bookTitle == null || bookTitle.trim().isEmpty()
                ? (tocUrl == null ? "" : tocUrl.trim()) : bookTitle.trim();
        book.ruleName = ruleName == null ? "" : ruleName;
        book.chapters = new ArrayList<>(chapters);
        book.cachedAt = System.currentTimeMillis();
        return saveBook(book);
    }

    // ---------- 统计与清理 ----------

    /** 某本书已缓存的章节数。 */
    public int countChapters(String tocUrl) {
        Path dir = dirFor(tocUrl);
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, CHAPTER_PREFIX + "*")) {
            for (Path ignored : stream) {
                count++;
            }
        } catch (IOException ignored) {
            return 0;
        }
        return count;
    }

    /** 缓存总大小（字节）。 */
    public long totalSizeBytes() {
        if (root == null || !Files.isDirectory(root)) {
            return 0L;
        }
        long total = 0L;
        try (DirectoryStream<Path> books = Files.newDirectoryStream(root)) {
            for (Path bookDir : books) {
                total += sizeOf(bookDir);
            }
        } catch (IOException ignored) {
            return total;
        }
        return total;
    }

    /** 缓存了多少本书。 */
    public int bookCount() {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> books = Files.newDirectoryStream(root)) {
            for (Path bookDir : books) {
                if (Files.isDirectory(bookDir)) {
                    count++;
                }
            }
        } catch (IOException ignored) {
            return 0;
        }
        return count;
    }

    /**
     * 清空某本书的缓存。
     *
     * @return 是否确实删掉了东西
     */
    public boolean clearBook(String tocUrl) {
        return deleteRecursively(dirFor(tocUrl));
    }

    /** 清空全部缓存。 */
    public boolean clearAll() {
        if (root == null || !Files.exists(root)) {
            return false;
        }
        return deleteRecursively(root);
    }

    // ---------- 内部：路径与读写 ----------

    private Path chapterFile(String tocUrl, String chapterUrl) {
        Path dir = dirFor(tocUrl);
        String chapterKey = CacheKeys.key(chapterUrl);
        if (dir == null || chapterKey.isEmpty()) {
            return null;
        }
        return dir.resolve(CHAPTER_PREFIX + chapterKey + JSON_SUFFIX);
    }

    /** 读 JSON；文件缺失 / 内容损坏 / 类型不符一律返回 null（视为未命中）。 */
    private <T> T readJson(Path file, Class<T> type) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (raw.trim().isEmpty()) {
                return null;
            }
            // 复用规则文件那套宽松解析：允许注释与尾随逗号，方便用户手改
            return GSON.fromJson(RuleLoader.sanitize(raw), type);
        } catch (IOException | RuntimeException ignored) {
            // 缓存坏了就当没有，去联网即可 —— 绝不能因此抛给调用方
            return null;
        }
    }

    /** 原子写：先写 .tmp 再替换，避免写一半崩溃留下半个 JSON。 */
    private boolean writeJson(Path file, Object model) {
        if (file == null) {
            return false;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, GSON.toJson(model).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ignored) {
            // 缓存的写失败不该打断阅读，静默忽略
            return false;
        }
    }

    private long sizeOf(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return 0L;
        }
        if (Files.isRegularFile(dir)) {
            try {
                return Files.size(dir);
            } catch (IOException ignored) {
                return 0L;
            }
        }
        long total = 0L;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                total += sizeOf(child);
            }
        } catch (IOException ignored) {
            return total;
        }
        return total;
    }

    /** 递归删除；路径为 null 或不存在时返回 false。 */
    private boolean deleteRecursively(Path target) {
        if (target == null || !Files.exists(target)) {
            return false;
        }
        if (Files.isDirectory(target)) {
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                for (Path child : stream) {
                    children.add(child);
                }
            } catch (IOException ignored) {
                return false;
            }
            for (Path child : children) {
                deleteRecursively(child);
            }
        }
        try {
            return Files.deleteIfExists(target);
        } catch (IOException ignored) {
            return false;
        }
    }
}
