package com.novelreader.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 章节目录对话框：顶部筛选框 + 章节列表 + 单行按钮（跳转 / 取消）。
 *
 * <h3>打开即定位到当前章</h3>
 * 目录动辄几百上千章，每次从第 1 章开始滚非常折磨。构造时会
 * <b>选中并滚动到当前正在阅读的章节</b>，当前章那一行还会显示「← 当前」标记。
 *
 * <h3>样式对齐</h3>
 * 与「阅读历史」用同一套结构（{@link DialogWrapper} + {@code JBList} + 按钮从
 * {@link #createActions()} 返回），所以按钮同样排在<b>同一行</b>，
 * 不会出现「一行列表按钮 + 一行确定取消」的割裂感。
 *
 * <h3>下标对齐</h3>
 * 列表里显示的是过滤后的<b>可见项</b>，而跳转需要<b>真实章节下标</b>。
 * 两者靠 {@link #visibleChapterIndices} 这张映射表对应 —— 这是本类唯一的
 * 状态耦合点，一旦错位就会跳错章，所以映射逻辑全部放在
 * {@link ShowChapterListAction#filterChapterIndices} 里用测试覆盖。
 */
public class ChapterListDialog extends DialogWrapper {

    private final Project project;
    private final List<Chapter> chapters;

    /** 可见项 → 真实章节下标。过滤后重建，与 model 严格一一对应。 */
    private final List<Integer> visibleChapterIndices = new ArrayList<>();

    private final CollectionListModel<String> model = new CollectionListModel<>();
    private final JBList<String> list = new JBList<>(model);
    private final JBTextField searchField = new JBTextField();

    /** 打开对话框时正在阅读的章节；-1 表示没有（例如列表为空）。 */
    private final int currentChapterIndex;

    /** 用户确认跳转到的章节下标；没确认则为 -1。 */
    private int chosenChapterIndex = -1;

    public ChapterListDialog(@Nullable Project project, @NotNull ReaderState state) {
        super(project, true);
        this.project = project;
        this.chapters = new ArrayList<>(state.getChapters());
        this.currentChapterIndex =
                ShowChapterListAction.clampToRange(state.getChapterIndex(), chapters.size());

        int shown = Math.max(currentChapterIndex + 1, 0);
        setTitle("章节目录（共 " + chapters.size() + " 章 · 当前第 " + shown + " 章）");

        searchField.getEmptyText().setText("输入章节名或序号筛选，回车跳转");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshList();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshList();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshList();
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SimpleListCellRenderer<String>() {
            @Override
            public void customize(JList<? extends String> l, String value, int index,
                                  boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value);
                // 当前章加个标记，一眼能认出"我读到哪儿了"
                if (index >= 0 && index < visibleChapterIndices.size()
                        && visibleChapterIndices.get(index) == currentChapterIndex) {
                    setText((value == null ? "" : value) + "    ← 当前");
                }
            }
        });
        // 双击 = 跳转，和阅读历史的手感一致
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });
        // 保险起见：列表真正可见时再滚一次。与 show() 里那次延迟调用互为兜底，
        // 不管"先布局后显示"还是"先显示后布局"都能滚到位。
        list.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                selectCurrentChapter();
            }
        });

        refreshList();
        init();
    }

    /**
     * 窗口显示之后再滚一次。
     *
     * <p>构造阶段的 {@link #refreshList()} 也会选中当前章，但那时对话框还没布局
     * （列表尺寸是 0×0），{@code ensureIndexIsVisible} / {@code scrollRectToVisible}
     * 都算不出目标位置，滚动会静默落空 —— 现象就是
     * <b>「打开时确实选中了当前章，但列表还停在开头」</b>。
     *
     * <p>这里把滚动推迟到窗口真正显示之后：{@code invokeLater} 排的任务会在模态
     * 对话框的嵌套事件循环里执行，那时组件已经完成布局，单元格高度是真实值。
     */
    @Override
    public void show() {
        SwingUtilities.invokeLater(this::selectCurrentChapter);
        super.show();
    }

    /**
     * 只保留「跳转 / 取消」两个按钮，由平台排在同一行。
     *
     * <p>{@code myOKAction} / {@code myCancelAction} 由 {@code createDefaultActions()}
     * 在 DialogWrapper 构造器里创建，本方法由 {@code createSouthPanel()} 在
     * {@code init()} 中调用，因此这里改名是安全的（且发生在按钮构建之前）。
     */
    @Override
    protected Action[] createActions() {
        myOKAction.putValue(Action.NAME, "跳转");
        myCancelAction.putValue(Action.NAME, "取消");
        // 回车 / 双击走的都是它
        myOKAction.putValue(DialogWrapper.DEFAULT_ACTION, Boolean.TRUE);
        return new Action[]{myOKAction, myCancelAction};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.add(searchField, BorderLayout.NORTH);

        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 380));
        root.add(scroll, BorderLayout.CENTER);

        return root;
    }

    /** 「跳转」：必须有选中项才关闭并返回；未选中时提示并保持打开。 */
    @Override
    protected void doOKAction() {
        int position = list.getSelectedIndex();
        if (position < 0 || position >= visibleChapterIndices.size()) {
            Messages.showInfoMessage(project, "请先选中要跳转的章节。", "章节目录");
            return;
        }
        chosenChapterIndex = visibleChapterIndices.get(position);
        super.doOKAction();
    }

    /** 用户确认跳转到的章节下标；取消时为 -1。 */
    public int getChosenChapterIndex() {
        return chosenChapterIndex;
    }

    // ---------- 内部 ----------

    /** 按筛选框内容重建列表，并把选中位置落在当前章上。 */
    private void refreshList() {
        List<Integer> indices =
                ShowChapterListAction.filterChapterIndices(chapters, searchField.getText());
        visibleChapterIndices.clear();
        visibleChapterIndices.addAll(indices);

        List<String> labels = new ArrayList<>(indices.size());
        for (int chapterIndex : indices) {
            labels.add(ShowChapterListAction.label(chapterIndex, chapters.get(chapterIndex)));
        }
        model.replaceAll(labels);

        selectCurrentChapter();
    }

    /**
     * 选中当前章，并让它真正滚进可见区域。
     *
     * <p>{@code setSelectedIndex} 只改模型状态，用户看到的仍然是列表开头，
     * 所以必须<b>显式滚动</b>。
     *
     * <p>注意本方法<b>只在窗口已经显示之后才有效</b>：{@code ensureIndexIsVisible}
     * 依赖 viewport 的真实尺寸，窗口没显示时列表是 0×0，这个调用会静默失效
     * （这正是「选中了但没滚过去」的原因）。因此构造/过滤时调用它负责选中，
     * <b>首屏滚动则统一交给 {@link #show()} 里那次延迟调用</b>。
     */
    private void selectCurrentChapter() {
        int target = ShowChapterListAction.selectionPositionFor(
                visibleChapterIndices, currentChapterIndex);
        if (target < 0) {
            return;
        }
        list.setSelectedIndex(target);
        list.ensureIndexIsVisible(target);
    }
}
