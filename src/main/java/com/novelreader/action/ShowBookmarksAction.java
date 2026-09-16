package com.novelreader.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.bookmark.BookmarkEntry;
import com.novelreader.bookmark.BookmarkStore;
import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.ReaderManager;
import com.novelreader.reader.ReaderPresenter;
import com.novelreader.util.PositionResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 书签列表：列出<b>当前这本书</b>的书签，可跳过去、可删除。
 *
 * <h3>为什么只列当前这本书</h3>
 * 书签的用途是「在一本书里跳来跳去」（跳跃式阅读），跨书恢复本来就有
 * 「阅读历史」负责。只列当前书也让「跳转」永远是本会话内的操作 ——
 * 不需要重新解析目录、不产生网络请求，点一下就到。
 *
 * <h3>交互模型</h3>
 * 与阅读历史、章节目录一致：列表只负责<b>选择</b>，副作用全部由
 * {@link BookmarkDialog} 上的按钮显式触发。这是当初「点删除反而进入阅读」事故后
 * 定下的结构，三个对话框共用同一套交互，用户不必为每个窗口重新学一遍。
 */
public class ShowBookmarksAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        ReaderState state = ReaderManager.getInstance().getState(project);
        if (state == null || !state.hasChapterList() || state.getTocUrl().isEmpty()) {
            explainNoSession(project);
            return;
        }

        BookmarkStore store = BookmarkStore.getInstance();
        BookmarkDialog dialog = new BookmarkDialog(project, state, store);
        dialog.show();

        BookmarkEntry chosen = dialog.getChosenEntry();
        if (chosen != null) {
            ReaderManager.getInstance().jumpToPosition(project,
                    resolveTargetChapter(state, chosen),
                    resolveTargetSegment(state, chosen));
        }
    }

    /** 没有打开的书时给出可操作的说明，而不是静默什么都不发生。 */
    private static void explainNoSession(Project project) {
        int total = BookmarkStore.getInstance().size();
        if (total == 0) {
            com.intellij.openapi.ui.Messages.showInfoMessage(project,
                    "还没有任何书签。\n\n读到自己想标记的地方时，用「添加书签」存下当前段。",
                    ReaderPresenter.DEFAULT_TITLE);
            return;
        }
        com.intellij.openapi.ui.Messages.showInfoMessage(project,
                "书签是按书显示的，需要先打开一本书才能查看它的书签。\n\n"
                        + "当前共保存了 " + total + " 条书签，请先用「阅读历史」恢复一本书。",
                ReaderPresenter.DEFAULT_TITLE);
    }

    /**
     * 挑出要跳转的那条书签：取第一个有效条目。
     *
     * <p>没有任何有效选中项时返回 {@code null} —— 绝不能退化成「默认跳第一条」，
     * 否则用户没选就点跳转会被带到莫名其妙的位置。
     *
     * <p>public 是为了让测试直接断言这条不变量（与历史的 {@code pickForOpen} 同一套路）。
     */
    public static @Nullable BookmarkEntry pickForJump(@Nullable List<BookmarkEntry> selected) {
        if (selected == null) {
            return null;
        }
        for (BookmarkEntry entry : selected) {
            if (entry != null && entry.isValid()) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 删除选中的若干条书签（按位置标识定位，天然幂等）。
     *
     * <p>跳过 null、空 key 与重复项，保证「选中里混进了脏数据」也不会误删别的书签。
     *
     * <p>public 是为了让测试直接断言「只删选中的、不碰其余的」。
     *
     * @return 实际删除的条数
     */
    public static int deleteSelected(@Nullable BookmarkStore store,
                                     @Nullable List<BookmarkEntry> selected) {
        if (store == null || selected == null) {
            return 0;
        }
        int removed = 0;
        Set<String> handled = new LinkedHashSet<>();
        for (BookmarkEntry entry : selected) {
            if (entry == null) {
                continue;
            }
            String key = entry.key();
            if (key == null || key.trim().isEmpty() || !entry.isValid()) {
                continue;
            }
            if (!handled.add(key)) {
                continue; // 同一条被重复选中，只删一次
            }
            if (store.remove(key)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 把书签里的章节还原成当前目录里的下标（URL 优先，下标兜底并夹取）。
     *
     * <p>public 是为了让测试断言「站点改版后仍落在同一章」——这一条错了，
     * 跳转就会跳到别的章节，而现象只是「内容不对」，很难反查。
     */
    public static int resolveTargetChapter(@Nullable ReaderState state, @Nullable BookmarkEntry entry) {
        if (state == null || entry == null) {
            return 0;
        }
        List<String> urls = new ArrayList<>();
        for (Chapter chapter : state.getChapters()) {
            urls.add(chapter == null ? "" : chapter.getUrl());
        }
        return PositionResolver.resolveChapterIndex(urls, entry.getChapterUrl(),
                entry.getChapterIndex());
    }

    /**
     * 目标段号。
     *
     * <p>目标就是当前章时，本会话已经知道本章有多少段，可以立刻夹取；
     * 换章时正文还没抓，段数未知，只能把原值交给加载流程在抓完后再夹
     * （见 {@code ReaderManager.jumpToPosition}）。
     */
    public static int resolveTargetSegment(@Nullable ReaderState state, @Nullable BookmarkEntry entry) {
        if (state == null || entry == null) {
            return 0;
        }
        int chapterIndex = resolveTargetChapter(state, entry);
        if (chapterIndex == state.getChapterIndex()) {
            return PositionResolver.resolveSegmentIndex(entry.getSegmentIndex(), state.getTotal());
        }
        return Math.max(0, entry.getSegmentIndex());
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        // 书签是应用级数据，任何项目下都允许打开列表（没有打开的书时会给出说明）
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    /**
     * 生成列表展示文案，下标与 {@code entries} 一一对应。
     *
     * <p>public 是为了让测试断言「展示项与书签条目对齐」这一关键不变量
     * —— 一旦错位，点第 3 条会跳到第 2 条的位置，或删掉别的书签。
     */
    public static List<String> labels(@Nullable List<BookmarkEntry> entries) {
        List<String> labels = new ArrayList<>();
        if (entries == null) {
            return labels;
        }
        for (BookmarkEntry entry : entries) {
            labels.add(entry.displayLabel());
        }
        return labels;
    }
}
