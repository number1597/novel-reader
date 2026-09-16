package com.novelreader.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.novelreader.history.ReadingHistoryEntry;
import com.novelreader.history.ReadingHistoryStore;
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
 * 阅读历史对话框：<b>先选中，再操作</b>。
 *
 * <h3>为什么不用 JBPopupFactory 的 chooser 弹窗</h3>
 * 最初用 {@code createPopupChooserBuilder} + 每行一个「×」按钮实现删除，结果出现
 * <b>点删除图标反而进入阅读</b>：chooser 弹窗把「整行被点击」一律视为
 * <b>选中该项并立即回调</b>（{@code setItemChosenCallback}），行内的按钮点击
 * 同样会命中这个回调，两个动作抢在一起 —— 弹窗模型本身无法区分
 * 「点了行内的删除按钮」和「点了这一行」。
 *
 * <p>因此改为标准 {@link DialogWrapper}：列表只负责<b>选择</b>，任何有副作用的
 * 动作都必须由按钮显式触发。这样「点删除不会顺手打开书」在结构上就成立了，
 * 不再依赖事件拦截的小技巧。
 *
 * <h3>按钮排布</h3>
 * 四个按钮全部由 {@link #createActions()} 返回，由平台统一布局在<b>同一行</b>里；
 * 没有把删除按钮塞进 {@code createCenterPanel()}，否则会跟确定/取消分成两行。
 * 数组顺序即按钮顺序，且<b>最后一个必须是取消动作</b>（平台按约定把它绑定到 Esc）。
 *
 * <p>顺序：{@code 继续阅读 | 删除选中 | 删除全部 | 取消}。
 *
 * <p>注意：官方文档里推荐的 {@code DialogWrapperExitAction} 在 2026.1 已经不存在
 * （对 {@code D:\java\idea\lib} 全量扫描不到），所以关闭按钮一律复用平台的
 * {@code myOKAction} / {@code myCancelAction}，其余按钮用普通
 * {@link AbstractAction}（只做事、不关窗）。
 *
 * <h3>删除后为什么清空选择</h3>
 * 删除会让列表下标整体前移，若保留原来的选中位置，紧接着敲回车就可能打开
 * 一本<b>用户没打算打开</b>的书。删除后一律清空选择，逼一次显式点击 ——
 * 宁可多点一下，也不要打开错书。
 */
public class HistoryDialog extends DialogWrapper {

    private final Project project;
    private final ReadingHistoryStore store;

    /** 列表展示项（文案），下标与 {@link #entries} 一一对应。 */
    private final CollectionListModel<String> model = new CollectionListModel<>();

    private final JBList<String> list = new JBList<>(model);

    /** 当前列表对应的历史条目快照，与 model 同步刷新。 */
    private List<ReadingHistoryEntry> entries = new ArrayList<>();

    /** 用户确认要打开的书；没确认则为 null。 */
    private ReadingHistoryEntry chosenEntry;

    /** 「删除选中」：只删选中的若干条，不关闭对话框。 */
    private final Action deleteSelectedAction = new AbstractAction("删除选中") {
        @Override
        public void actionPerformed(ActionEvent e) {
            deleteSelected();
        }
    };

    /** 「删除全部」：二次确认后清空，不关闭对话框。 */
    private final Action deleteAllAction = new AbstractAction("删除全部") {
        @Override
        public void actionPerformed(ActionEvent e) {
            deleteAll();
        }
    };

    public HistoryDialog(@Nullable Project project, @NotNull ReadingHistoryStore store) {
        super(project, true);
        this.project = project;
        this.store = store;

        setTitle("阅读历史");

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new SimpleListCellRenderer<String>() {
            @Override
            public void customize(JList<? extends String> l, String value, int index,
                                  boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value);
            }
        });
        // 双击 = 继续阅读（符合「列表里双击打开」的通用直觉）
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });

        reloadFromStore();
        init();
    }

    /**
     * 自定义按钮行：四个按钮排在<b>同一行</b>。
     *
     * <p>时序说明（已反编译核实）：{@code myOKAction} / {@code myCancelAction}
     * 由 {@code createDefaultActions()} 在 <b>DialogWrapper 构造器</b>里创建；
     * 而本方法由 {@code createSouthPanel()} 在 {@code init()} 中被调用。
     * 因此在改名前它们必然非 null，且改名发生在按钮构建<b>之前</b>，
     * 按钮文案一定是最新的。
     */
    @Override
    protected Action[] createActions() {
        myOKAction.putValue(Action.NAME, "继续阅读");
        myCancelAction.putValue(Action.NAME, "取消");
        // 显式声明默认按钮：回车 / 双击列表项走的都是它
        myOKAction.putValue(DialogWrapper.DEFAULT_ACTION, Boolean.TRUE);

        return new Action[]{myOKAction, deleteSelectedAction, deleteAllAction, myCancelAction};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));

        JBLabel hint = new JBLabel(
                "选中一本书后点「继续阅读」（或双击）；删除用下方按钮，可 Ctrl / Shift 多选。");
        root.add(hint, BorderLayout.NORTH);

        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 320));
        root.add(scroll, BorderLayout.CENTER);

        return root;
    }

    /**
     * 「继续阅读」：必须有选中项才允许关闭并返回选择。
     * 未选中时给出提示并<b>保持对话框打开</b>，避免「点了没反应」的困惑。
     */
    @Override
    protected void doOKAction() {
        ReadingHistoryEntry entry = ShowHistoryAction.pickForOpen(selectedEntries());
        if (entry == null) {
            Messages.showInfoMessage(project,
                    "请先在列表里选中一本书，再点「继续阅读」。", "阅读历史");
            return;
        }
        chosenEntry = entry;
        super.doOKAction();
    }

    /** 用户确认要打开的书；点了取消或只做了删除则为 null。 */
    public @Nullable ReadingHistoryEntry getChosenEntry() {
        return chosenEntry;
    }

    // ---------- 内部 ----------

    private void deleteSelected() {
        List<ReadingHistoryEntry> selected = selectedEntries();
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project,
                    "请先在列表里选中要删除的书（可多选）。", "阅读历史");
            return;
        }
        int removed = ShowHistoryAction.deleteSelected(store, selected);
        if (removed > 0) {
            afterDeletion();
        }
    }

    private void deleteAll() {
        if (entries.isEmpty()) {
            return;
        }
        int answer = Messages.showYesNoDialog(project,
                "确定要清空全部 " + entries.size() + " 条阅读历史吗？此操作不可撤销。",
                "删除全部阅读历史", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        store.clear();
        afterDeletion();
    }

    /** 删除后刷新列表；删空了就直接关掉对话框。 */
    private void afterDeletion() {
        reloadFromStore();
        if (entries.isEmpty()) {
            close(CANCEL_EXIT_CODE);
            return;
        }
        // 主动清空选择：下标已因删除而前移，保留旧选中会让回车打开一本错书
        list.clearSelection();
        list.requestFocusInWindow();
    }

    private void reloadFromStore() {
        entries = new ArrayList<>(store.getHistory().snapshot());
        model.replaceAll(ShowHistoryAction.labels(entries));
        if (!entries.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    /** 当前选中的条目；下标越界的忽略。 */
    private List<ReadingHistoryEntry> selectedEntries() {
        int[] indices = list.getSelectedIndices();
        List<ReadingHistoryEntry> picked = new ArrayList<>(indices.length);
        for (int index : indices) {
            if (index >= 0 && index < entries.size()) {
                picked.add(entries.get(index));
            }
        }
        return picked;
    }
}
