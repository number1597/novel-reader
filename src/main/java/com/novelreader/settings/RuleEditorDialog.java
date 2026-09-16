package com.novelreader.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.novelreader.model.NovelRule;
import com.novelreader.parser.RuleLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则编辑器：上方表格（一行一条规则，放常用字段），下方表单（选中规则的全部字段）。
 *
 * <h3>为什么表格 + 表单，而不是一张大表</h3>
 * 嵌套字段（{@code toc} / {@code content} / {@code paging}）摊平进表格会变成
 * {@code content.removeSelectors} 这种超长列名，既难扫也难编辑。
 * 表格只放「一眼要横向对比」的字段，长字段交给下方的详情表单。
 *
 * <h3>保存会重写整个文件</h3>
 * 规则是用户手写的 JSON，图形化保存<b>一定会丢掉 {@code //} 注释与排版</b>。
 * 因此保存前会先把原文件备份成 {@code rules.json.bak}（见 {@link RuleLoader#write}），
 * 让用户后悔了还能捞回来。这个事实在界面上也写明了。
 *
 * <h3>改动不会丢</h3>
 * 表单只在「切换行」和「点保存」两个时机提交到规则对象上。
 * 两个时机都覆盖到，所以不会出现「改完直接点保存，最后那次编辑没生效」。
 */
public class RuleEditorDialog extends DialogWrapper {

    private static final String HINT = "改动点「保存」后才写回文件；"
            + "保存会重写整个文件（手写注释与排版会丢失），原文件会自动备份为同名 .bak。";

    private final Path rulesFile;
    /** 保留原文件的版本号，不要因为编辑一次就退回默认值。 */
    private final int version;

    private final RuleTableModel model;
    private final JBTable table = new JBTable();

    private final JBTextField nameField = new JBTextField();
    private final JComboBox<String> matchTypeBox =
            new JComboBox<>(RuleTextUtils.matchTypes().toArray(new String[0]));
    private final JBTextField matchPatternField = new JBTextField();
    private final JBTextField exampleUrlField = new JBTextField();
    private final JBTextField encodingField = new JBTextField();
    private final JBTextField userAgentField = new JBTextField();
    private final JBTextField tocContainerField = new JBTextField();
    private final JBTextField tocLinkField = new JBTextField();
    private final JBTextField titleSelectorField = new JBTextField();
    private final JBTextField bodySelectorField = new JBTextField();
    private final JBTextArea removeSelectorsArea = new JBTextArea(4, 40);
    private final JBTextField nextSelectorField = new JBTextField();
    private final JBTextField nextTextContainsField = new JBTextField();
    private final JSpinner maxPagesSpinner =
            new JSpinner(new SpinnerNumberModel(20, 1, 200, 1));
    private final JBTextArea headersArea = new JBTextArea(3, 40);
    private final JBLabel problemsLabel = new JBLabel();
    private final List<JComponent> formInputs = new ArrayList<>();

    /** 当前在表单里编辑的行；-1 表示没有选中任何规则。 */
    private int currentRow = -1;

    /** 保存后的备份文件路径；没有原文件可备份时为 null。 */
    private Path backupPath;

    public RuleEditorDialog(@Nullable Project project, @NotNull Path rulesFile,
                            @NotNull RuleLoader.RuleSet ruleSet) {
        super(project, true);
        this.rulesFile = rulesFile;
        this.version = ruleSet.version;

        // 深拷贝一份：这样「取消」就真的什么都没改，不会污染调用方手里的对象
        List<NovelRule> drafts = new ArrayList<>();
        for (NovelRule rule : ruleSet.getRules()) {
            NovelRule copy = RuleLoader.copyRule(rule);
            if (copy != null) {
                drafts.add(copy);
            }
        }
        this.model = new RuleTableModel(drafts);

        setTitle("编辑解析规则");
        table.setModel(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onRowSelected();
            }
        });

        init();

        if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        } else {
            loadForm(null);
        }
    }

    /** 保存后的备份文件路径；未保存或没有原文件时为 null。 */
    public @Nullable Path getBackupPath() {
        return backupPath;
    }

    // ---------- DialogWrapper ----------

    @Override
    protected Action[] createActions() {
        myOKAction.putValue(Action.NAME, "保存");
        myCancelAction.putValue(Action.NAME, "取消");
        myOKAction.putValue(DialogWrapper.DEFAULT_ACTION, Boolean.TRUE);
        return new Action[]{myOKAction, myCancelAction};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setPreferredSize(new Dimension(880, 640));

        JBLabel hint = new JBLabel(HINT);
        root.add(hint, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                createTablePanel(), createFormPanel());
        split.setResizeWeight(0.38);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    @Override
    protected void doOKAction() {
        commitForm();
        List<NovelRule> rules = model.getRules();

        if (rules.isEmpty()) {
            if (Messages.showYesNoDialog(
                    "规则列表是空的，保存后插件将无法解析任何站点。\n仍要保存吗？",
                    "编辑解析规则", Messages.getWarningIcon()) != Messages.YES) {
                return;
            }
        } else {
            List<String> problems = RuleTextUtils.describeProblems(rules);
            if (!problems.isEmpty()) {
                String message = "以下问题可能导致规则无法正常工作：\n\n"
                        + String.join("\n", problems) + "\n\n仍要保存吗？";
                if (Messages.showYesNoDialog(message, "编辑解析规则",
                        Messages.getWarningIcon()) != Messages.YES) {
                    return;
                }
            }
        }

        try {
            backupPath = RuleLoader.write(buildRuleSet(rules), rulesFile);
        } catch (IOException e) {
            // 不关窗：让用户改路径或处理文件权限后重试，别把编辑内容一起丢掉
            Messages.showErrorDialog("保存规则失败：" + e.getMessage(), "编辑解析规则");
            return;
        }
        super.doOKAction();
    }

    private RuleLoader.RuleSet buildRuleSet(List<NovelRule> rules) {
        RuleLoader.RuleSet set = new RuleLoader.RuleSet();
        set.version = version;
        set.rules = new ArrayList<>(rules);
        return set;
    }

    // ---------- 上半部分：表格 ----------

    private JComponent createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JBScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(button("新增规则", () -> {
            commitForm();
            selectRow(model.addNewRule());
        }));
        buttons.add(button("复制", () -> {
            commitForm();
            int row = model.copyRule(table.getSelectedRow());
            if (row >= 0) {
                selectRow(row);
            }
        }));
        buttons.add(button("删除", () -> {
            int row = table.getSelectedRow();
            if (model.removeRule(row)) {
                currentRow = -1;
                int next = Math.min(row, model.getRowCount() - 1);
                if (next >= 0) {
                    selectRow(next);
                } else {
                    table.clearSelection();
                    loadForm(null);
                }
            }
        }));
        buttons.add(button("上移", () -> {
            int row = table.getSelectedRow();
            if (model.moveUp(row)) {
                selectRow(row - 1);
            }
        }));
        buttons.add(button("下移", () -> {
            int row = table.getSelectedRow();
            if (model.moveDown(row)) {
                selectRow(row + 1);
            }
        }));
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    private void selectRow(int row) {
        if (row < 0 || row >= model.getRowCount()) {
            return;
        }
        table.setRowSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
    }

    // ---------- 下半部分：表单 ----------

    private JComponent createFormPanel() {
        formInputs.add(nameField);
        formInputs.add(matchTypeBox);
        formInputs.add(matchPatternField);
        formInputs.add(exampleUrlField);
        formInputs.add(encodingField);
        formInputs.add(userAgentField);
        formInputs.add(tocContainerField);
        formInputs.add(tocLinkField);
        formInputs.add(titleSelectorField);
        formInputs.add(bodySelectorField);
        formInputs.add(removeSelectorsArea);
        formInputs.add(nextSelectorField);
        formInputs.add(nextTextContainsField);
        formInputs.add(maxPagesSpinner);
        formInputs.add(headersArea);

        removeSelectorsArea.setLineWrap(false);
        headersArea.setLineWrap(false);

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("规则名：", nameField)
                .addLabeledComponent("匹配方式：", matchTypeBox)
                .addLabeledComponent("匹配内容：", matchPatternField)
                .addLabeledComponent("示例目录页 URL：", exampleUrlField)
                .addLabeledComponent("编码：", encodingField)
                .addLabeledComponent("User-Agent：", userAgentField)
                .addLabeledComponent("目录容器选择器：", tocContainerField)
                .addLabeledComponent("章节链接选择器：", tocLinkField)
                .addLabeledComponent("正文章节标题选择器：", titleSelectorField)
                .addLabeledComponent("正文容器选择器：", bodySelectorField)
                .addLabeledComponent("要剔除的节点（一行一个）：", new JBScrollPane(removeSelectorsArea))
                .addLabeledComponent("下一页选择器：", nextSelectorField)
                .addLabeledComponent("「下一页」文本：", nextTextContainsField)
                .addLabeledComponent("多页拼接上限：", maxPagesSpinner)
                .addLabeledComponent("额外请求头（一行一个 Key: Value）：", new JBScrollPane(headersArea))
                .addComponent(problemsLabel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        JScrollPane scroll = new JBScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void onRowSelected() {
        // 先提交上一行的编辑，否则切换行时最后那次改动会丢
        commitForm();
        int row = table.getSelectedRow();
        currentRow = row;
        loadForm(model.getRuleAt(row));
    }

    /** 规则 → 表单。 */
    private void loadForm(@Nullable NovelRule rule) {
        boolean enabled = rule != null;
        for (JComponent input : formInputs) {
            input.setEnabled(enabled);
        }
        if (!enabled) {
            nameField.setText("");
            matchPatternField.setText("");
            exampleUrlField.setText("");
            encodingField.setText("");
            userAgentField.setText("");
            tocContainerField.setText("");
            tocLinkField.setText("");
            titleSelectorField.setText("");
            bodySelectorField.setText("");
            removeSelectorsArea.setText("");
            nextSelectorField.setText("");
            nextTextContainsField.setText("");
            headersArea.setText("");
            maxPagesSpinner.setValue(20);
            problemsLabel.setText("");
            return;
        }

        nameField.setText(rule.ruleName);
        matchTypeBox.setSelectedItem(
                rule.siteMatch == null || rule.siteMatch.type == null
                        ? "host" : rule.siteMatch.type.trim());
        matchPatternField.setText(rule.siteMatch == null ? "" : nullToEmpty(rule.siteMatch.pattern));
        exampleUrlField.setText(nullToEmpty(rule.exampleUrl));
        encodingField.setText(rule.encoding);
        userAgentField.setText(nullToEmpty(rule.userAgent));
        tocContainerField.setText(rule.getContainerSelector());
        tocLinkField.setText(rule.getLinkSelector());
        titleSelectorField.setText(rule.getTitleSelector());
        bodySelectorField.setText(rule.getBodySelector());
        removeSelectorsArea.setText(RuleTextUtils.joinSelectors(rule.getRemoveSelectors()));
        nextSelectorField.setText(rule.getNextSelector());
        nextTextContainsField.setText(rule.getNextTextContains());
        maxPagesSpinner.setValue(rule.getMaxPages());
        headersArea.setText(RuleTextUtils.formatHeaders(rule.headers));
        updateProblemsLabel(rule);
    }

    /** 表单 → 当前行的规则对象。 */
    private void commitForm() {
        NovelRule rule = model.getRuleAt(currentRow);
        if (rule == null) {
            return;
        }
        rule.ruleName = nameField.getText().trim();
        rule.exampleUrl = exampleUrlField.getText().trim();
        rule.encoding = encodingField.getText().trim();
        rule.userAgent = userAgentField.getText().trim();

        // 嵌套配置可能为 null（用户手写 JSON 时漏写了某一节），赋值前补齐
        if (rule.siteMatch == null) {
            rule.siteMatch = new NovelRule.SiteMatch();
        }
        rule.siteMatch.type = selectedMatchType();
        rule.siteMatch.pattern = matchPatternField.getText().trim();

        if (rule.toc == null) {
            rule.toc = new NovelRule.TocConfig();
        }
        rule.toc.containerSelector = tocContainerField.getText().trim();
        rule.toc.linkSelector = tocLinkField.getText().trim();

        if (rule.content == null) {
            rule.content = new NovelRule.ContentConfig();
        }
        rule.content.titleSelector = titleSelectorField.getText().trim();
        rule.content.bodySelector = bodySelectorField.getText().trim();
        rule.content.removeSelectors = RuleTextUtils.splitSelectors(removeSelectorsArea.getText());

        if (rule.paging == null) {
            rule.paging = new NovelRule.PagingConfig();
        }
        rule.paging.nextSelector = nextSelectorField.getText().trim();
        rule.paging.nextTextContains = nextTextContainsField.getText().trim();
        rule.paging.maxPages = intValue(maxPagesSpinner);

        rule.headers = RuleTextUtils.parseHeaders(headersArea.getText());

        updateProblemsLabel(rule);
        // 让表格里的规则名/匹配内容等跟着表单即时更新
        model.fireTableRowsUpdated(currentRow, currentRow);
    }

    private String selectedMatchType() {
        Object selected = matchTypeBox.getSelectedItem();
        return selected == null ? "host" : selected.toString();
    }

    private void updateProblemsLabel(NovelRule rule) {
        List<String> problems = RuleTextUtils.describeProblems(rule);
        if (problems.isEmpty()) {
            problemsLabel.setText("这条规则看起来没问题。");
            problemsLabel.setForeground(com.intellij.ui.JBColor.GRAY);
            return;
        }
        problemsLabel.setText("可能的问题：" + String.join("；", problems));
        problemsLabel.setForeground(com.intellij.ui.JBColor.RED);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int intValue(JSpinner spinner) {
        Object value = spinner == null ? null : spinner.getValue();
        return value instanceof Number ? ((Number) value).intValue() : 20;
    }
}
