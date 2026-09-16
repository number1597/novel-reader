package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.novelreader.bookmark.BookmarkEntry;
import com.novelreader.bookmark.BookmarkStore;
import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.ReaderManager;
import com.novelreader.reader.ReaderPresenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 把当前阅读位置存为书签。
 *
 * <h3>与阅读历史的分工</h3>
 * 历史是自动的、每本书只有一条（「上次读到哪」）；书签是手动的、一本书可以有很多条
 * （「这段留着回头看」）。所以这里让用户写一句备注，并在保存时抓一段正文摘录 ——
 * 没有这两样，同章内的几条书签在列表里长得一模一样，根本分不出来。
 *
 * <h3>为什么单章模式不允许加书签</h3>
 * 单章模式没有目录页 URL，也就没有「这本书」的标识，重启后无法定位回来 ——
 * 存了也只会在列表里变成一个跳不过去的死条目。与 {@code ReaderManager.saveHistory}
 * 拒绝记录单章模式是同一个理由。
 */
public class AddBookmarkAction extends AnAction {

    /** 摘录保留的最大字符数：够认出「是这一段」，又不至于把列表撑爆。 */
    public static final int SNIPPET_LENGTH = 40;

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        ReaderState state = ReaderManager.getInstance().getState(project);
        if (state == null || state.isEmpty()) {
            Messages.showInfoMessage(project,
                    "还没有开始阅读，没有可添加的位置。\n\n"
                            + "请先用「打开：粘贴目录URL阅读」开始读一本书。",
                    ReaderPresenter.DEFAULT_TITLE);
            return;
        }
        if (!state.hasChapterList()) {
            Messages.showInfoMessage(project,
                    "当前是单章阅读（没有目录页 URL），重启后无法定位回这一章，因此不支持书签。\n\n"
                            + "请用「打开：粘贴目录URL阅读」按整本书阅读，再添加书签。",
                    ReaderPresenter.DEFAULT_TITLE);
            return;
        }

        String note = Messages.showInputDialog(project,
                "给这个位置写个备注（可以留空）：\n\n" + positionHint(state)
                        + "\n\n留空时会用章节位置作为书签名称。",
                "添加书签", Messages.getQuestionIcon());
        if (note == null) {
            // 用户取消：什么都不做。注意「留空」与「取消」必须区分开 ——
            // 留空是允许的，取消则不该产生书签
            return;
        }

        BookmarkEntry entry = newEntry(state, note, System.currentTimeMillis());
        BookmarkEntry saved = entry == null ? null : BookmarkStore.getInstance().add(entry);
        if (saved == null) {
            Messages.showErrorDialog(project,
                    "书签没能保存（位置信息不完整或文件不可写）。", "添加书签失败");
            return;
        }
        Messages.showInfoMessage(project,
                "已添加书签：" + saved.displayLabel(), ReaderPresenter.DEFAULT_TITLE);
    }

    /**
     * 用当前会话位置构造一条书签。
     *
     * <p>public 是为了让测试直接断言「会话字段 → 书签字段」的映射：
     * 章节 URL 漏了会让站点改版后跳错章，段号漏了会永远跳第 1 段，
     * 这两个错误在界面上都看不出来。
     *
     * @return 位置信息不完整（无目录 URL / 单章模式）时返回 null
     */
    public static @Nullable BookmarkEntry newEntry(@Nullable ReaderState state, @Nullable String note,
                                                   long now) {
        if (state == null || !state.hasChapterList()) {
            return null;
        }
        String tocUrl = state.getTocUrl();
        if (tocUrl == null || tocUrl.isEmpty()) {
            return null;
        }
        Chapter chapter = state.getCurrentChapter();
        BookmarkEntry entry = new BookmarkEntry();
        entry.tocUrl = tocUrl;
        entry.bookTitle = bookTitle(state);
        entry.chapterIndex = state.getChapterIndex();
        entry.segmentIndex = state.getIndex();
        entry.chapterTitle = state.getChapterTitle();
        entry.chapterUrl = chapter == null ? "" : chapter.getUrl();
        entry.snippet = BookmarkEntry.snippet(state.currentSegment(), SNIPPET_LENGTH);
        entry.note = note == null ? "" : note.trim();
        entry.createdAt = now;
        return entry;
    }

    /** 列表展示用的小说名：取第 1 章标题；没有则退回目录 URL（与历史口径一致）。 */
    private static String bookTitle(ReaderState state) {
        Chapter first = state.getChapterAt(0);
        if (first != null && !first.getTitle().isEmpty()) {
            return first.getTitle();
        }
        return state.getTocUrl();
    }

    /** 对话框里给用户看的「你现在在哪」。 */
    static String positionHint(ReaderState state) {
        StringBuilder sb = new StringBuilder();
        if (state.hasChapterList()) {
            sb.append("第 ").append(state.getChapterIndex() + 1).append(" / ")
                    .append(state.getChapterCount()).append(" 章");
            String title = state.getChapterTitle();
            if (title != null && !title.isEmpty()) {
                sb.append("  ").append(title);
            }
            sb.append("\n");
        }
        return sb.append("第 ").append(state.getIndex() + 1).append(" / ")
                .append(Math.max(state.getTotal(), 1)).append(" 段").toString();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        // 与「章节目录」一致：没有整本书的上下文时置灰（单章模式也算没有）
        Project project = event.getProject();
        event.getPresentation().setEnabled(
                project != null && ReaderManager.getInstance().hasChapterList(project));
    }
}
