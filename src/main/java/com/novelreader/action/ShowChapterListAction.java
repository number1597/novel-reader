package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.ReaderManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 章节目录：打开 {@link ChapterListDialog}，选中章节后跳转。
 *
 * <h3>样式与交互</h3>
 * 与「阅读历史」保持同一套观感：{@link com.intellij.openapi.ui.DialogWrapper}
 * + 列表 + 单行按钮（跳转 / 取消）。顶部有一个筛选框，输入章节名或序号即可过滤。
 *
 * <h3>打开时定位到当前章</h3>
 * 目录可能有几百上千章，每次都从第 1 章开始滚很折磨人。对话框打开时会
 * <b>自动选中并滚动到当前正在阅读的章节</b>（见
 * {@link #selectionPositionFor(List, int)}），当前章在列表里还会有「← 当前」标记。
 *
 * <p>本类同时承担「可脱平台单测的纯逻辑」：展示文案、过滤、定位，都是静态方法，
 * 由 {@code ChapterListFilterTest} 覆盖。原因是这几个不变量一旦错位就会
 * <b>跳错章</b>（用户很难描述清楚现象），必须用测试钉死。
 */
public class ShowChapterListAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        ReaderManager manager = ReaderManager.getInstance();
        ReaderState state = manager.getState(project);
        if (state == null || !state.hasChapterList()) {
            return;
        }

        ChapterListDialog dialog = new ChapterListDialog(project, state);
        dialog.show();

        int target = dialog.getChosenChapterIndex();
        if (target >= 0) {
            manager.jumpToChapter(project, target);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(
                project != null && ReaderManager.getInstance().hasChapterList(project));
    }

    // ---------- 可单测的纯逻辑 ----------

    /**
     * 单条章节的展示文案：{@code 序号. 标题}，序号从 1 开始。
     *
     * <p>序号从 1 开始而下标从 0 开始，是最容易出错的地方，因此集中在这里生成，
     * 调用方一律用「章节下标」而不是「显示出来的序号」。
     */
    public static String label(int chapterIndex, @Nullable Chapter chapter) {
        String title = chapter == null || chapter.getTitle() == null ? "" : chapter.getTitle();
        return (chapterIndex + 1) + ". " + title;
    }

    /**
     * 生成「序号. 标题」形式的展示项，下标与章节下标一一对应。
     *
     * <p>只留 {@code List<Chapter>} 这一个重载：早先还有一个接受 {@code ReaderState}
     * 的同名方法，结果 {@code labels(null)} 在两个重载之间产生编译期歧义。
     * 参数类型越少歧义越少，调用方写 {@code labels(state.getChapters())} 即可。
     *
     * <p>public 是为了让测试能直接断言「展示项与章节下标对齐」这一关键不变量
     * —— 一旦错位，跳章就会跳错。
     */
    public static List<String> labels(@Nullable List<Chapter> chapters) {
        List<String> items = new ArrayList<>();
        if (chapters == null) {
            return items;
        }
        for (int i = 0; i < chapters.size(); i++) {
            items.add(label(i, chapters.get(i)));
        }
        return items;
    }

    /**
     * 按关键字过滤，返回命中章节的<b>真实下标</b>（不是过滤后的位置）。
     *
     * <p>匹配规则与之前的弹窗一致：对 {@code 序号. 标题} 整体做<b>忽略大小写的子串匹配</b>，
     * 所以输入章节名或序号都能找到。
     *
     * <p>额外做了一点排序优化：当查询是纯数字时，<b>章号正好相等的那一章排在最前</b>。
     * 否则搜「50」会把「150」「250」这些先列出来，想跳到第 50 章反而要多找一步。
     *
     * @return 命中的章节下标；空关键字返回全部
     */
    public static List<Integer> filterChapterIndices(@Nullable List<Chapter> chapters,
                                                     @Nullable String query) {
        List<Integer> matched = new ArrayList<>();
        if (chapters == null || chapters.isEmpty()) {
            return matched;
        }
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (keyword.isEmpty()) {
            for (int i = 0; i < chapters.size(); i++) {
                matched.add(i);
            }
            return matched;
        }
        boolean numeric = keyword.chars().allMatch(Character::isDigit);
        List<Integer> exact = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            String text = label(i, chapters.get(i)).toLowerCase(Locale.ROOT);
            if (!text.contains(keyword)) {
                continue;
            }
            if (numeric && String.valueOf(i + 1).equals(keyword)) {
                exact.add(i);
            } else {
                matched.add(i);
            }
        }
        exact.addAll(matched);
        return exact;
    }

    /**
     * 把章节下标夹到合法范围。
     *
     * @return 合法下标；完全没有章节（{@code size <= 0}）时返回 {@code -1}
     */
    public static int clampToRange(int index, int size) {
        if (size <= 0) {
            return -1;
        }
        if (index < 0) {
            return 0;
        }
        return Math.min(index, size - 1);
    }

    /**
     * 打开目录时该选中哪一项：<b>当前章在可见列表里的位置</b>。
     *
     * <p>当前章被过滤掉时退回第 1 项；列表为空返回 {@code -1}（无可选）。
     *
     * @param visibleChapterIndices 过滤后可见项对应的真实章节下标
     * @param currentChapterIndex   当前阅读到的章
     */
    public static int selectionPositionFor(@Nullable List<Integer> visibleChapterIndices,
                                           int currentChapterIndex) {
        if (visibleChapterIndices == null || visibleChapterIndices.isEmpty()) {
            return -1;
        }
        int position = visibleChapterIndices.indexOf(currentChapterIndex);
        return position >= 0 ? position : 0;
    }
}
