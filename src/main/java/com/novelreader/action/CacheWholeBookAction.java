package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.novelreader.cache.ChapterCache;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.ChapterLoader;
import com.novelreader.reader.ReaderManager;
import com.novelreader.reader.ReaderPresenter;
import com.novelreader.settings.NovelReaderSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把当前这本书<b>整本缓存到本地</b>，之后断网也能接着读。
 *
 * <h3>为什么需要这个动作</h3>
 * 阅读时顺手缓存只能覆盖「已经读过的章节」；想在高铁上把一本书读完，
 * 就得提前把整本抓下来。这个动作就是那个「提前」。
 *
 * <h3>为什么安全</h3>
 * 逐章抓取放在后台任务里，进度条实时显示「第 N / M 章」，随时可取消；
 * 已有缓存的章节直接跳过（不会为了缓存把整本重抓一遍）。
 * 抓取失败只计入统计、不中断整轮 —— 一章失败不该让整本书白抓。
 */
public class CacheWholeBookAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        ReaderState state = ReaderManager.getInstance().getState(project);
        if (state == null || !state.hasChapterList()) {
            Messages.showInfoMessage(project,
                    "还没有开始阅读，没有可缓存的书。\n\n"
                            + "请先用「打开：粘贴目录URL阅读」开始读一本书。",
                    ReaderPresenter.DEFAULT_TITLE);
            return;
        }
        String tocUrl = state.getTocUrl();
        if (tocUrl == null || tocUrl.isEmpty()) {
            Messages.showInfoMessage(project,
                    "这是单章阅读，没有目录页 URL，无法整本缓存。\n\n"
                            + "请用「打开：粘贴目录URL阅读」按整本书阅读。",
                    ReaderPresenter.DEFAULT_TITLE);
            return;
        }
        NovelRule rule = state.getRule();
        if (rule == null) {
            Messages.showInfoMessage(project,
                    "当前会话没有可用的解析规则（多半是离线打开的书），无法抓取新章节。\n\n"
                            + "请联网后重新打开这本书，或从「阅读历史」恢复一次。",
                    ReaderPresenter.DEFAULT_TITLE);
            return;
        }
        NovelReaderSettings settings = NovelReaderSettings.getInstance();
        if (!settings.isCacheEnabled()) {
            Messages.showInfoMessage(project,
                    "章节缓存已在设置里关闭（Settings → Tools → Novel Reader）。\n\n"
                            + "启用后即可把整本书缓存到本地。",
                    ReaderPresenter.DEFAULT_TITLE);
            return;
        }

        List<Chapter> chapters = new ArrayList<>(state.getChapters());
        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Novel Reader：缓存《"
                        + bookName(state) + "》", true) {

                    private int saved;
                    private int skipped;
                    private int failed;

                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(false);
                        ChapterCache cache = ChapterCache.getInstanceOrNull();
                        if (cache == null) {
                            return;
                        }
                        int total = chapters.size();
                        for (int i = 0; i < total; i++) {
                            if (indicator.isCanceled()) {
                                return;
                            }
                            indicator.setFraction((double) i / total);
                            indicator.setText("第 " + (i + 1) + " / " + total + " 章");

                            Chapter chapter = chapters.get(i);
                            if (cache.hasChapter(tocUrl, chapter.getUrl())) {
                                skipped++;
                                continue;
                            }
                            try {
                                // 走正常的抓取链路（它会在成功后自己写缓存）
                                ChapterLoader.Loaded loaded = ChapterLoader.load(
                                        tocUrl, chapter, rule, settings, cache);
                                if (loaded == null || loaded.isEmpty()) {
                                    failed++;
                                } else {
                                    saved++;
                                }
                            } catch (IOException e) {
                                // 单章失败只记账：一章抓不到不该让整本书白抓
                                failed++;
                            }
                        }
                        indicator.setFraction(1.0);
                    }

                    @Override
                    public void onSuccess() {
                        Messages.showInfoMessage(project, summarize("缓存完成"), "缓存整本书");
                    }

                    @Override
                    public void onCancel() {
                        Messages.showInfoMessage(project,
                                summarize("已取消") + "\n\n已缓存的部分可以直接离线阅读。",
                                "缓存整本书");
                    }

                    private String summarize(String headline) {
                        StringBuilder sb = new StringBuilder(headline).append("：");
                        sb.append("新缓存 ").append(saved).append(" 章");
                        if (skipped > 0) {
                            sb.append("，跳过已有缓存 ").append(skipped).append(" 章");
                        }
                        if (failed > 0) {
                            sb.append("，").append(failed).append(" 章抓取失败（可稍后重试）");
                        }
                        sb.append("。\n\n缓存位置：").append(ChapterCache.defaultCacheDir())
                                .append("\n可在设置页「清空缓存」释放空间。");
                        return sb.toString();
                    }
                });
    }

    /** 展示用的书名：优先书名，退回目录第 1 章标题，再退回 URL。 */
    private static String bookName(ReaderState state) {
        Chapter first = state.getChapterAt(0);
        if (first != null && !first.getTitle().isEmpty()) {
            return first.getTitle();
        }
        return state.getTocUrl();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        // 与「章节目录」一致：没有整本书的上下文时置灰
        Project project = event.getProject();
        event.getPresentation().setEnabled(
                project != null && ReaderManager.getInstance().hasChapterList(project));
    }
}
