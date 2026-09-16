package com.novelreader.settings;

import com.novelreader.model.NovelRule;
import com.novelreader.parser.RuleLoader;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则表格的数据模型：一行一条规则，只放「一眼要对比的常用字段」。
 *
 * <p>长字段（各种选择器、剔除列表、请求头）放在选中规则的详情表单里
 * （见 {@link RuleEditorDialog}），表格保持窄到能一眼扫完。
 *
 * <p>继承 {@link AbstractTableModel} 而不是直接用 Swing 组件，是为了能在<b>无界面环境</b>里
 * 直接单测「列 ↔ 字段」的读写 —— 这类映射写错了表现为「改了一列却动到别的字段」，
 * 从界面上很难看出来。
 */
public class RuleTableModel extends AbstractTableModel {

    public static final int COL_ENABLED = 0;
    public static final int COL_NAME = 1;
    public static final int COL_MATCH_TYPE = 2;
    public static final int COL_MATCH_PATTERN = 3;
    public static final int COL_ENCODING = 4;
    public static final int COL_USER_AGENT = 5;
    public static final int COLUMN_COUNT = 6;

    private static final String[] COLUMN_NAMES = {
            "启用", "规则名", "匹配方式", "匹配内容", "编码", "User-Agent",
    };

    /** 直接持有调用方的列表：表格编辑的结果要落到同一批对象上，不能是副本。 */
    private final List<NovelRule> rules;

    public RuleTableModel(List<NovelRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    /** 当前编辑中的规则（就是传给构造器的那个列表）。 */
    public List<NovelRule> getRules() {
        return rules;
    }

    /** 取某行的规则；越界返回 null 而不是抛异常。 */
    public NovelRule getRuleAt(int row) {
        if (row < 0 || row >= rules.size()) {
            return null;
        }
        return rules.get(row);
    }

    // ---------- 增删改 ----------

    /** 追加一条空白规则（匹配方式默认 host）。 */
    public int addNewRule() {
        NovelRule rule = new NovelRule();
        rule.ruleName = "新规则";
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.toc = new NovelRule.TocConfig();
        rule.content = new NovelRule.ContentConfig();
        rule.paging = new NovelRule.PagingConfig();
        return addRule(rule);
    }

    /** 追加一条规则，返回它的行号。 */
    public int addRule(NovelRule rule) {
        NovelRule target = rule == null ? new NovelRule() : rule;
        rules.add(target);
        int row = rules.size() - 1;
        fireTableRowsInserted(row, row);
        return row;
    }

    /**
     * 复制某条规则并插到它下面，返回新行号（越界返回 -1）。
     *
     * <p>走 {@link RuleLoader#copyRule} 深拷贝：手写逐字段复制的话，
     * 以后给 NovelRule 加字段就会静默丢字段。
     */
    public int copyRule(int row) {
        NovelRule source = getRuleAt(row);
        if (source == null) {
            return -1;
        }
        NovelRule copy = RuleLoader.copyRule(source);
        if (copy != null) {
            copy.ruleName = source.getRuleName() + " 副本";
        }
        rules.add(row + 1, copy);
        fireTableRowsInserted(row + 1, row + 1);
        return row + 1;
    }

    /** 删除某行；越界返回 false。 */
    public boolean removeRule(int row) {
        if (row < 0 || row >= rules.size()) {
            return false;
        }
        rules.remove(row);
        fireTableRowsDeleted(row, row);
        return true;
    }

    /** 上移一行；已在首行返回 false。规则顺序会影响命中优先级，所以要能调。 */
    public boolean moveUp(int row) {
        if (row <= 0 || row >= rules.size()) {
            return false;
        }
        swap(row, row - 1);
        fireTableRowsUpdated(row - 1, row);
        return true;
    }

    /** 下移一行；已在末行返回 false。 */
    public boolean moveDown(int row) {
        if (row < 0 || row >= rules.size() - 1) {
            return false;
        }
        swap(row, row + 1);
        fireTableRowsUpdated(row, row + 1);
        return true;
    }

    private void swap(int a, int b) {
        NovelRule tmp = rules.get(a);
        rules.set(a, rules.get(b));
        rules.set(b, tmp);
    }

    // ---------- TableModel ----------

    @Override
    public int getRowCount() {
        return rules.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_COUNT;
    }

    @Override
    public String getColumnName(int column) {
        return column >= 0 && column < COLUMN_COUNT ? COLUMN_NAMES[column] : "";
    }

    /** 布尔列返回 Boolean，表格才会把它渲染成复选框。 */
    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == COL_ENABLED ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return getRuleAt(rowIndex) != null;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        NovelRule rule = getRuleAt(rowIndex);
        if (rule == null) {
            return null;
        }
        switch (columnIndex) {
            case COL_ENABLED:
                return rule.enabled;
            case COL_NAME:
                return rule.ruleName;
            case COL_MATCH_TYPE:
                return rule.siteMatch == null ? "" : rule.siteMatch.type;
            case COL_MATCH_PATTERN:
                return rule.siteMatch == null ? "" : rule.siteMatch.pattern;
            case COL_ENCODING:
                return rule.encoding;
            case COL_USER_AGENT:
                return rule.userAgent;
            default:
                return null;
        }
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        NovelRule rule = getRuleAt(rowIndex);
        if (rule == null) {
            return;
        }
        String text = value == null ? "" : value.toString().trim();
        switch (columnIndex) {
            case COL_ENABLED:
                rule.enabled = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(text);
                break;
            case COL_NAME:
                rule.ruleName = text;
                break;
            case COL_MATCH_TYPE:
                // 嵌套配置可能为 null（用户手写 JSON 时没写这一节），赋值前补上
                if (rule.siteMatch == null) {
                    rule.siteMatch = new NovelRule.SiteMatch();
                }
                rule.siteMatch.type = text;
                break;
            case COL_MATCH_PATTERN:
                if (rule.siteMatch == null) {
                    rule.siteMatch = new NovelRule.SiteMatch();
                }
                rule.siteMatch.pattern = text;
                break;
            case COL_ENCODING:
                rule.encoding = text;
                break;
            case COL_USER_AGENT:
                rule.userAgent = text;
                break;
            default:
                return;
        }
        fireTableRowsUpdated(rowIndex, rowIndex);
    }
}
