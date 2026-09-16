package com.novelreader.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.history.ReadingHistoryEntry;
import com.novelreader.history.ReadingHistoryStore;
import com.novelreader.reader.ReaderNotifier;
import com.novelreader.reader.SessionOpener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 阅读历史：列出读过的书，可继续阅读、可删除。
 *
 * <h3>交互模型：先选中，再动手</h3>
 * 列表本身只负责<b>选择</b>，所有副作用（打开 / 删除）都由 {@link HistoryDialog}
 * 上的按钮显式触发。曾经用 {@code JBPopupFactory} 的 chooser 弹窗配合「每行一个 ×」
 * 实现删除，结果 <b>点 × 会顺手打开那本书</b> —— 因为 chooser 把整行点击统一视为
 * 「选中并回调」，行内按钮的点击也落在同一个回调上，两者无法区分。
 * 换成对话框后，这个歧义在结构上消失了。
 *
 * <h3>点击后的「跳回上次位置」</h3>
 * 历史里存的是目录 URL + 章 / 段下标。恢复时必须<b>重新解析目录</b>，
 * 因为站点可能已经增删章节；具体定位策略见 {@link SessionOpener}。
 * 整个过程是网络请求，放到后台任务里跑。
 */
public class ShowHistoryAction extends AnAction {

    private final ReaderNotifier notifier = new ReaderNotifier();

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        showHistoryList(project);
    }

    /** 弹出历史对话框；用户确认了某本书才去恢复阅读。 */
    private void showHistoryList(Project project) {
        ReadingHistoryStore store = ReadingHistoryStore.getInstance();
        if (store.getHistory().isEmpty()) {
            notifier.info(project, ReaderNotifier.MESSAGE_TITLE,
                    "还没有阅读历史。先用「打开：粘贴目录URL阅读」开始读一本书吧。");
            return;
        }

        HistoryDialog dialog = new HistoryDialog(project, store);
        dialog.show();

        ReadingHistoryEntry chosen = dialog.getChosenEntry();
        if (chosen != null) {
            openEntry(project, chosen);
        }
    }

    /**
     * 从选中项里挑出要打开的那本书：取列表中<b>第一个</b>有效条目。
     *
     * <p>没有任何有效选中项时返回 {@code null} —— 绝不能退化成「默认打开第一本」，
     * 那样用户在没选中的情况下回车就会打开一本他没打算看的书。
     *
     * <p>public 是为了让测试直接断言这条不变量。
     */
    public static @Nullable ReadingHistoryEntry pickForOpen(
            @Nullable List<ReadingHistoryEntry> selected) {
        if (selected == null) {
            return null;
        }
        for (ReadingHistoryEntry entry : selected) {
            if (entry != null && entry.isValid()) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 删除选中的若干本书（按目录 URL 定位，天然幂等）。
     *
     * <p>跳过 null、空 URL 与重复项，保证「选中里混进了脏数据」也不会误删别的书。
     *
     * <p>public 是为了让测试直接断言「只删选中的、不碰其余的」。
     *
     * @return 实际删除的条数
     */
    public static int deleteSelected(@Nullable ReadingHistoryStore store,
                                     @Nullable List<ReadingHistoryEntry> selected) {
        if (store == null || selected == null) {
            return 0;
        }
        int removed = 0;
        Set<String> handled = new LinkedHashSet<>();
        for (ReadingHistoryEntry entry : selected) {
            if (entry == null) {
                continue;
            }
            String tocUrl = entry.getTocUrl();
            if (tocUrl == null || tocUrl.trim().isEmpty()) {
                continue;
            }
            if (!handled.add(tocUrl.trim())) {
                continue; // 同一本书被重复选中，只删一次
            }
            if (store.remove(tocUrl)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 打开一条历史：后台重新解析目录 + 抓取目标章节，然后定位到上次的段。
     */
    private void openEntry(Project project, ReadingHistoryEntry entry) {
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
                new com.intellij.openapi.progress.Task.Backgroundable(
                        project, "Novel Reader：恢复《" + entry.getTitle() + "》", true) {

                    private SessionOpener.Opened opened;
                    private String error;

                    @Override
                    public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        try {
                            opened = SessionOpener.open(
                                    entry.getTocUrl(),
                                    entry.getChapterIndex(),
                                    entry.getSegmentIndex(),
                                    entry.getChapterUrl(),
                                    com.novelreader.settings.NovelReaderSettings.getInstance());
                        } catch (Exception e) {
                            error = e.getMessage();
                        }
                    }

                    @Override
                    public void onSuccess() {
                        if (error != null) {
                            notifier.error(project, "Novel Reader 恢复失败",
                                    error + "\n\n历史记录仍保留，可稍后重试或删除它。");
                            return;
                        }
                        if (opened == null || opened.getState() == null) {
                            notifier.error(project, "Novel Reader 恢复失败", "没有解析出可阅读的内容。");
                            return;
                        }
                        com.novelreader.reader.ReaderManager.getInstance()
                                .resume(project, opened.getState(), opened.getSegmentIndex());

                        String tip = "已回到《" + opened.getState().getChapterTitle() + "》第 "
                                + (opened.getSegmentIndex() + 1) + " 段。";
                        if (opened.getWarning() != null) {
                            tip += "\n" + opened.getWarning();
                        }
                        notifier.info(project, ReaderNotifier.MESSAGE_TITLE, tip);
                    }
                });
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        // 历史是应用级数据，即使当前没有打开的项目也应当可用（故不置灰）
        event.getPresentation().setEnabled(true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    /**
     * 生成列表展示文案，下标与 {@code entries} 一一对应。
     *
     * <p>public 是为了让测试断言「展示项与历史条目对齐」这一关键不变量
     * —— 一旦错位，点第 3 本会打开第 2 本，或删除错条目。
     */
    public static List<String> labels(List<ReadingHistoryEntry> entries) {
        List<String> labels = new ArrayList<>();
        if (entries == null) {
            return labels;
        }
        for (ReadingHistoryEntry entry : entries) {
            labels.add(entry.displayLabel());
        }
        return labels;
    }
}
