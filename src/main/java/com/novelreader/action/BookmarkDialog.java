package com.novelreader.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.novelreader.bookmark.BookmarkEntry;
import com.novelreader.bookmark.BookmarkStore;
import com.novelreader.model.ReaderState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 书签对话框：<b>先选中，再操作</b>。
 *
 * <p>结构与「阅读历史」「章节目录」刻意保持一致（{@link DialogWrapper} + 列表 + 一行按钮），
 * 三个窗口用同一套心智模型：<b>列表只负责选择，副作用一律由按钮触发</b>。
 * 这是当初「点删除图标反而进入阅读」事故后定下的结构 ——
 * {@code JBPopupFactory} 的 chooser 弹窗把整行点击统一当作「选中并回调」，
 * 行内按钮的点击也落在同一个回调上，两者无法区分。
 *
 * <h3>按钮排布</h3>
 * 五个按钮全部由 {@link #createActions()} 返回，平台会把它们排在<b>同一行</b>；
 * 塞进 {@code createCenterPanel()} 就会跟确定/取消分成两行。
 * 数组顺序即按钮顺序，<b>最后一个必须是取消动作</b>（平台按约定绑到 Esc）。
 * 顺序：{@code 跳转 | 添加当前位置 | 删除选中 | 删除全部 | 取消}。
 *
 * <h3>与历史对话框的一处不同：删空后不自动关窗</h3>
 * 历史删空后关窗是合理的（没有内容可展示）；但书签窗口里就有「添加当前位置」，
 * 关掉等于在用户最想标记的时候把入口收走 —— 所以这里删空后只是把提示文案换成
 * 「这本书还没有书签」，窗口留着。
 */
public class BookmarkDialog extends DialogWrapper {

    private final Project project;
    private final ReaderState state;
    private final BookmarkStore store;

    /** 列表展示项（文案），下标与 {@link #entries} 一一对应。 */
    private final CollectionListModel<String> model = new CollectionListModel<>();

    private final JBList<String> list = new JBList<>(model);
    private final JBLabel hintLabel = new JBLabel();

    /** 当前列表对应的书签快照，与 model 同步刷新。 */
    private List<BookmarkEntry> entries = new ArrayList<>();

    /** 用户确认要跳转的书签；没确认则为 null。 */
    private BookmarkEntry chosenEntry;

    /** 「添加当前位置」：把当前段存为书签，不关闭对话框。 */
    private final Action addCurrentAction = new AbstractAction("添加当前位置") {
        @Override
        public void actionPerformed(ActionEvent e) {
            addCurrent();
        }
    };

    /** 「删除选中」：只删选中的若干条，不关闭对话框。 */
    private final Action deleteSelectedAction = new AbstractAction("删除选中") {
        @Override
        public void actionPerformed(ActionEvent e) {
            deleteSelected();
        }
    };

    /** 「删除全部」：二次确认后清空本书书签，不关闭对话框。 */
    private final Action deleteAllAction = new AbstractAction("删除全部") {
        @Override
        public void actionPerformed(ActionEvent e) {
            deleteAll();
        }
    };

    public BookmarkDialog(@Nullable Project project, @NotNull ReaderState state,
                          @NotNull BookmarkStore store) {
        super(project, true);
        this.project = project;
        this.state = state;
        this.store = store;

        setTitle("书签");

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new SimpleListCellRenderer<String>() {
            @Override
            public void customize(JList<? extends String> l, String value, int index,
                                  boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value);
            }
        });
        // 双击 = 跳转（与其它两个对话框的「双击即确认」手感一致）
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });

        reload();
        init();
    }

    /**
     * 自定义按钮行：五个按钮排在<b>同一行</b>。
     *
     * <p>时序说明（已反编译核实）：{@code myOKAction} / {@code myCancelAction}
     * 由 {@code createDefaultActions()} 在 <b>DialogWrapper 构造器</b>里创建；
     * 而本方法由 {@code createSouthPanel()} 在 {@code init()} 中被调用。
     * 因此在改名前它们必然非 null。
     */
    @Override
    protected Action[] createActions() {
        myOKAction.putValue(Action.NAME, "跳转");
        myCancelAction.putValue(Action.NAME, "取消");
        myOKAction.putValue(DialogWrapper.DEFAULT_ACTION, Boolean.TRUE);

        return new Action[]{myOKAction, addCurrentAction, deleteSelectedAction,
                deleteAllAction, myCancelAction};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));

        root.add(hintLabel, BorderLayout.NORTH);

        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 320));
        root.add(scroll, BorderLayout.CENTER);

        return root;
    }

    /**
     * 「跳转」：必须有选中项才允许关闭并返回选择。
     * 未选中时给出提示并<b>保持对话框打开</b>，避免「点了没反应」的困惑。
     */
    @Override
    protected void doOKAction() {
        BookmarkEntry entry = ShowBookmarksAction.pickForJump(selectedEntries());
        if (entry == null) {
            Messages.showInfoMessage(project,
                    "请先在列表里选中一条书签，再点「跳转」。", "书签");
            return;
        }
        chosenEntry = entry;
        super.doOKAction();
    }

    /** 用户确认要跳转的书签；点了取消或只做了增删则为 null。 */
    public @Nullable BookmarkEntry getChosenEntry() {
        return chosenEntry;
    }

    // ---------- 内部 ----------

    private void addCurrent() {
        String note = Messages.showInputDialog(project,
                "给这个位置写个备注（可以留空）：\n\n" + AddBookmarkAction.positionHint(state),
                "添加书签", Messages.getQuestionIcon());
        if (note == null) {
            return; // 取消：不产生书签（与「留空」区分开）
        }
        BookmarkEntry entry = AddBookmarkAction.newEntry(state, note, System.currentTimeMillis());
        BookmarkEntry saved = entry == null ? null : store.add(entry);
        if (saved == null) {
            Messages.showErrorDialog(project,
                    "书签没能保存（位置信息不完整或文件不可写）。", "添加书签失败");
            return;
        }
        // 列表按位置排序，新书签不一定在最后 —— 按 key 精确选中它
        reload();
        selectKey(saved.key());
    }

    private void deleteSelected() {
        List<BookmarkEntry> selected = selectedEntries();
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "请先在列表里选中要删除的书签（可多选）。", "书签");
            return;
        }
        int removed = ShowBookmarksAction.deleteSelected(store, selected);
        if (removed > 0) {
            afterDeletion();
        }
    }

    private void deleteAll() {
        if (entries.isEmpty()) {
            return;
        }
        int answer = Messages.showYesNoDialog(project,
                "确定要删除《" + bookName() + "》的全部 " + entries.size()
                        + " 条书签吗？此操作不可撤销。",
                "删除全部书签", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        // 逐条删除而非 clear()：clear() 会清掉所有书的书签，而这里只该清当前这本书
        for (BookmarkEntry entry : new ArrayList<>(entries)) {
            store.remove(entry.key());
        }
        afterDeletion();
    }

    /** 删除后刷新列表并清空选择（下标已前移，留着旧选中会误跳）。 */
    private void afterDeletion() {
        reload();
        list.clearSelection();
        list.requestFocusInWindow();
    }

    private void reload() {
        entries = new ArrayList<>(store.forBook(state.getTocUrl()));
        model.replaceAll(ShowBookmarksAction.labels(entries));
        updateHint();
        if (!entries.isEmpty() && list.getSelectedIndex() < 0) {
            list.setSelectedIndex(0);
        }
    }

    private void updateHint() {
        if (entries.isEmpty()) {
            hintLabel.setText("这本书还没有书签。点「添加当前位置」把现在读到的这一段存下来。");
            return;
        }
        hintLabel.setText("共 " + entries.size()
                + " 条书签，按阅读顺序排列。选中后点「跳转」（或双击）即可回到那个位置。");
    }

    /** 按位置标识选中列表中的某一条；找不到则不动。 */
    private void selectKey(String key) {
        if (key == null) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (key.equals(entries.get(i).key())) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    /** 当前选中的书签；下标越界的忽略。 */
    private List<BookmarkEntry> selectedEntries() {
        int[] indices = list.getSelectedIndices();
        List<BookmarkEntry> picked = new ArrayList<>(indices.length);
        for (int index : indices) {
            if (index >= 0 && index < entries.size()) {
                picked.add(entries.get(index));
            }
        }
        return picked;
    }

    private String bookName() {
        for (BookmarkEntry entry : entries) {
            if (!entry.getBookTitle().isEmpty()) {
                return entry.getBookTitle();
            }
        }
        return state.getTocUrl();
    }
}
