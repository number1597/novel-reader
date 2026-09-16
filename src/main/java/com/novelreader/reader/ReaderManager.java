package com.novelreader.reader;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.novelreader.cache.ChapterCache;
import com.novelreader.history.ReadingHistoryStore;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.model.ReaderState;
import com.novelreader.settings.NovelReaderSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阅读会话管理（应用级服务）。
 *
 * <p>以 Project 为维度保存当前阅读进度。段游标与章节下标都只存在
 * {@link ReaderState} 一处，所有菜单/快捷键动作都走本类的方法，不会出现状态漂移。
 *
 * <h3>跨章阅读</h3>
 * 会话持有完整章节目录，因此支持两种跨章方式：
 * <ul>
 *   <li><b>自动续读</b>：{@link #next} 在本章最后一段时会自动抓取并展示下一章的第 1 段；
 *       {@link #prev} 在本章第 1 段时会回到上一章的<b>最后一段</b>，保证连续阅读不断档。</li>
 *   <li><b>显式跳章</b>：{@link #nextChapter}/{@link #prevChapter}/{@link #jumpToChapter}
 *       用于按章翻阅或从章节目录里直接跳转。</li>
 * </ul>
 *
 * <p>抓取章节属于耗时 I/O，统一放在后台任务里执行；同一 Project 同时只允许一个加载任务在跑
 * （见 {@link #loading}），避免连续按快捷键导致重复请求与状态错乱。
 *
 * <h3>渲染与状态解耦</h3>
 * 本类<b>不直接</b>碰通知或 UI，而是把「呈现」交给 {@link ReaderPresenter}：
 * 翻页/跳章/恢复统一经 {@link #showCurrent} 调 {@code present}，
 * 与阅读位置无关的提示（到头了、加载失败）走 {@code info} / {@code error}。
 * 这样将来加「专用阅读面板」时，本类一行都不用改。
 */
public class ReaderManager {

    private final Map<String, ReaderState> states = new ConcurrentHashMap<>();

    /** 正在加载章节的 Project，用于防重入。 */
    private final Set<String> loading = ConcurrentHashMap.newKeySet();

    /** 渲染目标；默认走通知，注入点留给测试与将来的阅读面板。 */
    private final ReaderPresenter presenter;

    /** 平台服务用的无参构造：按设置分发到「通知」或「专用面板」（默认通知，与历史行为一致）。 */
    public ReaderManager() {
        this(new ConfiguredPresenter());
    }

    /**
     * 注入展示器。
     *
     * <p>public 是为了让测试注入一个「记录调用」的假实现，直接断言「翻一次页只渲染一次」。
     * 平台按无参构造创建本服务，所以这个重载只会被测试与将来的组合展示器用到。
     */
    public ReaderManager(ReaderPresenter presenter) {
        this.presenter = presenter == null ? new ConfiguredPresenter() : presenter;
    }

    public static ReaderManager getInstance() {
        return ApplicationManager.getApplication().getService(ReaderManager.class);
    }

    /** 开始阅读：重置会话并立即展示第 1 段。 */
    public void start(Project project, ReaderState state) {
        if (project == null || state == null) {
            return;
        }
        states.put(key(project), state);
        showCurrent(project);
    }

    /**
     * 从阅读历史恢复会话：直接展示传进来的起始段，不做网络请求。
     *
     * <p>调用方（{@code ShowHistoryAction}）已负责抓取目标章节的正文；
     * 这里只接管状态、渲染并更新历史时间戳。
     */
    public void resume(Project project, ReaderState state, int segmentIndex) {
        if (project == null || state == null) {
            return;
        }
        if (segmentIndex > 0) {
            state.moveToSegment(segmentIndex);
        }
        states.put(key(project), state);
        // showCurrent 内部会写历史，因此「恢复」也自动把这本书顶到最前面
        showCurrent(project);
    }

    /**
     * 把当前进度写入阅读历史。
     *
     * <p>每次翻页 / 换章都会调用，因此这里<b>不能</b>抛异常也不能阻塞 EDT：
     * 写盘由 {@link com.novelreader.history.ReadingHistoryStore} 内部吞掉失败，
     * 最坏情况只是丢一条历史，绝不影响阅读。
     */
    private void saveHistory(Project project, ReaderState state) {
        if (state == null || !state.hasChapterList()) {
            // 单章模式没有目录页 URL，无法作为书签重新打开，不记历史
            return;
        }
        String tocUrl = state.getTocUrl();
        if (tocUrl == null || tocUrl.isEmpty()) {
            return;
        }
        Chapter chapter = state.getCurrentChapter();
        NovelRule rule = state.getRule();
        ReadingHistoryStore.getInstance().record(
                tocUrl,
                // 小说名优先用第 1 章的标题做兜底展示（站点书名解析不稳定）
                fallbackTitle(state),
                state.getChapterIndex(),
                state.getIndex(),
                state.getChapterTitle(),
                chapter == null ? "" : chapter.getUrl(),
                state.getChapterCount(),
                rule == null ? "" : rule.getRuleName(),
                System.currentTimeMillis());
    }

    /** 列表展示用的小说名：取第 1 章标题；没有则退回目录 URL。 */
    private static String fallbackTitle(ReaderState state) {
        Chapter first = state.getChapterAt(0);
        if (first != null && !first.getTitle().isEmpty()) {
            return first.getTitle();
        }
        return state.getTocUrl();
    }

    /** 当前项目是否已有阅读会话。 */
    public boolean hasState(Project project) {
        ReaderState state = getState(project);
        return state != null && !state.isEmpty();
    }

    public ReaderState getState(Project project) {
        return project == null ? null : states.get(key(project));
    }

    public void clear(Project project) {
        if (project != null) {
            states.remove(key(project));
            loading.remove(key(project));
        }
    }

    /** 结束阅读：丢弃会话并关闭该会话产生的正文通知。 */
    public void stop(Project project) {
        if (project == null) {
            return;
        }
        boolean had = states.remove(key(project)) != null;
        loading.remove(key(project));
        if (had) {
            presenter.close(project);
        }
    }

    /** 当前会话是否持有章节目录。 */
    public boolean hasChapterList(Project project) {
        ReaderState state = getState(project);
        return state != null && state.hasChapterList();
    }

    public boolean canNextChapter(Project project) {
        ReaderState state = getState(project);
        return state != null && state.hasNextChapter();
    }

    public boolean canPrevChapter(Project project) {
        ReaderState state = getState(project);
        return state != null && state.hasPrevChapter();
    }

    /** 渲染当前分段。<b>渲染后同步更新阅读历史</b>，因此所有翻页/跳章路径都会自动记录进度。 */
    public void showCurrent(Project project) {
        ReaderState state = getState(project);
        if (state == null || state.isEmpty()) {
            return;
        }
        presenter.present(project, state);
        saveHistory(project, state);
    }

    /**
     * 只重绘当前内容：不改状态、不写历史。
     *
     * <p>给「改了显示设置」（字号、行距）用。这类改动立刻影响观感，但阅读位置一点没变，
     * 不该被当成一次翻页去刷新历史时间戳。
     */
    public void refresh(Project project) {
        ReaderState state = getState(project);
        if (state == null || state.isEmpty()) {
            return;
        }
        presenter.present(project, state);
    }

    // ---------- 段内翻页（含自动续读下一章） ----------

    /**
     * 下一页。已到本章最后一段时：有下一章则<b>自动加载下一章</b>，否则提示到头。
     */
    public void next(Project project) {
        ReaderState state = getState(project);
        if (state == null || state.isEmpty()) {
            return;
        }
        if (state.next()) {
            showCurrent(project);
            return;
        }
        if (state.hasNextChapter()) {
            loadChapter(project, state, state.getChapterIndex() + 1, Landing.FIRST, true);
            return;
        }
        presenter.info(project, ReaderPresenter.DEFAULT_TITLE, state.hasChapterList()
                ? "已经是最后一章的最后一段了。"
                : "已经是本章最后一段了。");
    }

    /**
     * 上一页。已到本章第 1 段时：有上一章则回到上一章的<b>最后一段</b>，否则提示到头。
     */
    public void prev(Project project) {
        ReaderState state = getState(project);
        if (state == null || state.isEmpty()) {
            return;
        }
        if (state.prev()) {
            showCurrent(project);
            return;
        }
        if (state.hasPrevChapter()) {
            loadChapter(project, state, state.getChapterIndex() - 1, Landing.LAST, true);
            return;
        }
        presenter.info(project, ReaderPresenter.DEFAULT_TITLE, state.hasChapterList()
                ? "已经是第一章的第一段了。"
                : "已经是本章第一段了。");
    }

    // ---------- 按章翻阅 ----------

    /** 直接进入下一章，落在该章第 1 段。 */
    public void nextChapter(Project project) {
        ReaderState state = getState(project);
        if (state == null || !state.hasChapterList()) {
            presenter.info(project, ReaderPresenter.DEFAULT_TITLE, "还没有章节目录，请先粘贴小说目录 URL 开始阅读。");
            return;
        }
        if (!state.hasNextChapter()) {
            presenter.info(project, ReaderPresenter.DEFAULT_TITLE, "已经是最后一章了。");
            return;
        }
        loadChapter(project, state, state.getChapterIndex() + 1, Landing.FIRST, false);
    }

    /** 直接回到上一章，落在该章第 1 段。 */
    public void prevChapter(Project project) {
        ReaderState state = getState(project);
        if (state == null || !state.hasChapterList()) {
            presenter.info(project, ReaderPresenter.DEFAULT_TITLE, "还没有章节目录，请先粘贴小说目录 URL 开始阅读。");
            return;
        }
        if (!state.hasPrevChapter()) {
            presenter.info(project, ReaderPresenter.DEFAULT_TITLE, "已经是第一章了。");
            return;
        }
        loadChapter(project, state, state.getChapterIndex() - 1, Landing.FIRST, false);
    }

    /**
     * 跳转到章节目录中的指定章节（下标从 0 开始）。
     *
     * <p>目标就是当前章时只重绘，不重新抓取。
     */
    public void jumpToChapter(Project project, int targetIndex) {
        ReaderState state = getState(project);
        if (state == null || !state.hasChapterList()) {
            return;
        }
        Chapter target = state.getChapterAt(targetIndex);
        if (target == null) {
            return;
        }
        if (targetIndex == state.getChapterIndex()) {
            showCurrent(project);
            return;
        }
        loadChapter(project, state, targetIndex, Landing.FIRST, false);
    }

    /**
     * 跳到指定章节的指定段（书签跳转用）。
     *
     * <p>与 {@link #jumpToChapter} 的区别只在落点：这里落在<b>保存过的段</b>而不是第 1 段。
     * 目标就是当前章时只移动段游标并重绘 —— 省掉一次网络请求；
     * 换章时目标段号先原样传给加载流程，抓完正文后由
     * {@link ReaderState#moveToSegment(int)} 夹到本章实际段数范围内
     * （抓取前不知道新章有多少段）。
     */
    public void jumpToPosition(Project project, int targetChapter, int targetSegment) {
        ReaderState state = getState(project);
        if (state == null || !state.hasChapterList()) {
            return;
        }
        if (state.getChapterAt(targetChapter) == null) {
            return;
        }
        if (targetChapter == state.getChapterIndex()) {
            state.moveToSegment(targetSegment);
            showCurrent(project);
            return;
        }
        loadChapter(project, state, targetChapter, Landing.SEGMENT, false, targetSegment);
    }

    // ---------- 章节加载 ----------

    /** 加载完成后段游标的落点。 */
    private enum Landing {
        /** 第 1 段。 */
        FIRST,
        /** 最后一段（往回读时用，保证连续）。 */
        LAST,
        /** 指定的第 N 段（书签跳转用）。 */
        SEGMENT
    }

    /** 落点在第 1 段的加载；供翻页 / 跳章等不需要指定段号的路径使用。 */
    private void loadChapter(Project project, ReaderState state, int targetIndex,
                             Landing landing, boolean auto) {
        loadChapter(project, state, targetIndex, landing, auto, -1);
    }

    /**
     * 后台抓取目标章节，成功后替换会话内容并展示。
     *
     * @param auto          true 表示由段内翻页自动触发，仅影响进度条文案
     * @param segmentTarget {@code landing == SEGMENT} 时的目标段号；其余落点忽略
     */
    private void loadChapter(Project project, ReaderState state, int targetIndex,
                             Landing landing, boolean auto, int segmentTarget) {
        if (project == null || state == null) {
            return;
        }
        Chapter target = state.getChapterAt(targetIndex);
        if (target == null) {
            return;
        }
        NovelRule rule = state.getRule();
        // 用 getInstanceOrNull：本方法在普通单测里也会被调到（那时没有平台）。
        // 设置缺失时下面各处都有默认值兜底，不会影响判定。
        NovelReaderSettings settings = NovelReaderSettings.getInstanceOrNull();
        ChapterCache cache = cacheFor(settings);
        // 没有规则也能读：只要缓存里有这一章。离线打开的书正是这种情形
        // （目录来自缓存的 book.json，规则是未知的）。
        if (rule == null && !hasCached(cache, state.getTocUrl(), target.getUrl())) {
            presenter.error(project, "Novel Reader 缺少规则",
                    "当前会话没有可用的解析规则，这一章也没有离线缓存，无法加载章节。\n\n"
                            + "请联网后重新执行「粘贴小说目录URL并阅读」，"
                            + "或用「缓存整本书」先把章节存到本地。");
            return;
        }

        String taskKey = key(project);
        if (!loading.add(taskKey)) {
            // 已经有一个章节在加载：这次触发要忽略，但必须**说出来** ——
            // 静默丢弃的表现就是「点了下一章，什么都没发生」。
            presenter.status(project, "上一章还在加载，请稍候…");
            return;
        }

        // 抓取是异步的，先给一个即时反馈：按下按钮就该有动静
        presenter.status(project, "正在加载《" + target.getTitle() + "》…");

        String progressTitle = (auto ? "Novel Reader：自动加载《" : "Novel Reader：加载《")
                + target.getTitle() + "》";

        ProgressManager.getInstance().run(new Task.Backgroundable(project, progressTitle, true) {

            private ChapterLoader.Loaded result;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    result = ChapterLoader.load(state.getTocUrl(), target, rule, settings, cache);
                } catch (IOException e) {
                    error = e.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (error != null) {
                    presenter.error(project, "Novel Reader 加载章节失败", error);
                    return;
                }
                if (result == null || result.isEmpty()) {
                    presenter.error(project, "Novel Reader 解析失败",
                            "《" + target.getTitle() + "》没有解析出正文，"
                                    + "请检查规则中的 content.bodySelector 是否正确。");
                    return;
                }
                // 加载期间用户可能重新粘贴了目录，此时旧会话应被丢弃
                if (getState(project) != state) {
                    return;
                }
                if (!state.applyChapter(targetIndex, result.getTitle(), result.getSegments())) {
                    // 正常路径上不该发生（下标在前面已校验过）。真发生了说明会话已经变了，
                    // 但也得说一声 —— 静默 return 就是「点了没反应」。
                    presenter.error(project, ReaderPresenter.DEFAULT_TITLE,
                            "这一章的内容无法应用，请重新打开这本书。");
                    return;
                }
                if (landing == Landing.LAST) {
                    state.moveToLastSegment();
                } else if (landing == Landing.SEGMENT) {
                    // moveToSegment 自带夹取：站点正文长度可能变了，存的段号未必还在
                    state.moveToSegment(segmentTarget);
                }
                showCurrent(project);

                if (result.getPages() > 1) {
                    presenter.info(project, ReaderPresenter.DEFAULT_TITLE,
                            "《" + result.getTitle() + "》由 " + result.getPages()
                                    + " 页拼接而成，共 " + state.getTotal() + " 段。");
                }
            }

            @Override
            public void onFinished() {
                // onSuccess / onThrowable / onCancel 之后必定执行，统一在这里释放防重入标记
                loading.remove(taskKey);
            }
        });
    }

    private String key(Project project) {
        String location = project.getLocationHash();
        return location == null || location.isEmpty() ? project.getName() : location;
    }

    /**
     * 取离线缓存。
     *
     * <p>两种「没有缓存」都返回 null，调用方据此退化为纯联网行为：
     * 用户在设置里关掉了缓存；或平台尚未就绪（普通单元测试里跑本类）。
     * <b>缓存是锦上添花，绝不能因为它让阅读链路上抛异常。</b>
     *
     * <p>public 是为了让测试断言「关掉缓存后不再使用缓存」这一分支
     * （开启分支需要平台，测试里拿不到）。
     */
    public static ChapterCache cacheFor(NovelReaderSettings settings) {
        if (settings != null && !settings.isCacheEnabled()) {
            return null;
        }
        return ChapterCache.getInstanceOrNull();
    }

    private static boolean hasCached(ChapterCache cache, String tocUrl, String chapterUrl) {
        return cache != null && cache.hasChapter(tocUrl, chapterUrl);
    }
}
