package com.novelreader;

import com.novelreader.model.NovelRule;
import com.novelreader.settings.RuleTableModel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 规则表格模型：列 ↔ 字段的映射。
 *
 * <p>{@code AbstractTableModel} 不需要界面就能实例化，所以这些断言是真正的单测。
 * 映射写错的典型症状是「改了一列却动到别的字段」，在界面上很难看出来，因此逐列验证往返。
 */
public class RuleTableModelTest {

    private static NovelRule rule(String name) {
        NovelRule rule = new NovelRule();
        rule.ruleName = name;
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "host";
        rule.siteMatch.pattern = "example.com";
        rule.encoding = "auto";
        rule.userAgent = "ua";
        return rule;
    }

    private static RuleTableModel modelWith(NovelRule... rules) {
        return new RuleTableModel(new ArrayList<>(Arrays.asList(rules)));
    }

    @Test
    public void columnsAreNamedAndTyped() {
        RuleTableModel model = modelWith(rule("A"));

        assertEquals(6, model.getColumnCount());
        assertEquals("启用", model.getColumnName(RuleTableModel.COL_ENABLED));
        assertEquals("规则名", model.getColumnName(RuleTableModel.COL_NAME));
        assertEquals("匹配方式", model.getColumnName(RuleTableModel.COL_MATCH_TYPE));
        assertEquals("匹配内容", model.getColumnName(RuleTableModel.COL_MATCH_PATTERN));
        assertEquals("编码", model.getColumnName(RuleTableModel.COL_ENCODING));
        assertEquals("User-Agent", model.getColumnName(RuleTableModel.COL_USER_AGENT));
        assertEquals("启用列必须是 Boolean，表格才会渲染成复选框",
                Boolean.class, model.getColumnClass(RuleTableModel.COL_ENABLED));
        assertEquals(String.class, model.getColumnClass(RuleTableModel.COL_NAME));
    }

    @Test
    public void readsEveryColumnFromTheRule() {
        NovelRule rule = rule("甲站");
        RuleTableModel model = modelWith(rule);

        assertEquals(Boolean.TRUE, model.getValueAt(0, RuleTableModel.COL_ENABLED));
        assertEquals("甲站", model.getValueAt(0, RuleTableModel.COL_NAME));
        assertEquals("host", model.getValueAt(0, RuleTableModel.COL_MATCH_TYPE));
        assertEquals("example.com", model.getValueAt(0, RuleTableModel.COL_MATCH_PATTERN));
        assertEquals("auto", model.getValueAt(0, RuleTableModel.COL_ENCODING));
        assertEquals("ua", model.getValueAt(0, RuleTableModel.COL_USER_AGENT));
    }

    @Test
    public void writesEachColumnToTheRightField() {
        NovelRule rule = rule("甲站");
        RuleTableModel model = modelWith(rule);

        model.setValueAt(Boolean.FALSE, 0, RuleTableModel.COL_ENABLED);
        model.setValueAt("乙站", 0, RuleTableModel.COL_NAME);
        model.setValueAt("regex", 0, RuleTableModel.COL_MATCH_TYPE);
        model.setValueAt(".*\\.com", 0, RuleTableModel.COL_MATCH_PATTERN);
        model.setValueAt("GBK", 0, RuleTableModel.COL_ENCODING);
        model.setValueAt("novel-reader/1.0", 0, RuleTableModel.COL_USER_AGENT);

        assertFalse("改的是 enabled，不该动别的", rule.enabled);
        assertEquals("乙站", rule.ruleName);
        assertEquals("regex", rule.siteMatch.type);
        assertEquals(".*\\.com", rule.siteMatch.pattern);
        assertEquals("GBK", rule.encoding);
        assertEquals("novel-reader/1.0", rule.userAgent);
    }

    @Test
    public void writesTrimTextAndTreatNullAsEmpty() {
        NovelRule rule = rule("甲站");
        RuleTableModel model = modelWith(rule);

        model.setValueAt("  带空格  ", 0, RuleTableModel.COL_NAME);
        model.setValueAt(null, 0, RuleTableModel.COL_ENCODING);

        assertEquals("带空格", rule.ruleName);
        assertEquals("", rule.encoding);
    }

    @Test
    public void missingNestedConfigIsCreatedOnDemand() {
        // 用户手写 JSON 时可能漏了 siteMatch 整节，这时改表格不能抛 NPE
        NovelRule rule = new NovelRule();
        rule.ruleName = "缺配置";
        rule.siteMatch = null;
        RuleTableModel model = modelWith(rule);

        model.setValueAt("example.com", 0, RuleTableModel.COL_MATCH_PATTERN);

        assertEquals("应补上 siteMatch 再赋值", "example.com", rule.siteMatch.pattern);
    }

    @Test
    public void addNewRuleAppendsARowWithNestedConfigs() {
        RuleTableModel model = modelWith(rule("甲站"));

        int row = model.addNewRule();

        assertEquals(1, row);
        assertEquals(2, model.getRowCount());
        NovelRule added = model.getRuleAt(row);
        assertEquals("新规则", added.ruleName);
        assertEquals("host", added.siteMatch.type);
        assertEquals(20, added.paging.maxPages);
    }

    @Test
    public void copyRuleInsertsBelowAndIsADeepCopy() {
        NovelRule source = rule("甲站");
        source.toc.linkSelector = "dd a";
        RuleTableModel model = modelWith(source);

        int row = model.copyRule(0);

        assertEquals("副本应插在原规则下面", 1, row);
        assertEquals(2, model.getRowCount());
        NovelRule copy = model.getRuleAt(1);
        assertEquals("甲站 副本", copy.ruleName);
        assertNotSame("必须是新对象", source, copy);

        copy.toc.linkSelector = "改过了";
        assertEquals("改副本不该影响原规则", "dd a", source.toc.linkSelector);
    }

    @Test
    public void removeRuleDropsTheRow() {
        RuleTableModel model = modelWith(rule("甲"), rule("乙"), rule("丙"));

        assertTrue(model.removeRule(1));

        assertEquals(2, model.getRowCount());
        assertEquals("甲", model.getRuleAt(0).ruleName);
        assertEquals("丙", model.getRuleAt(1).ruleName);
        assertFalse("越界删除应返回 false 而不是抛异常", model.removeRule(5));
        assertFalse(model.removeRule(-1));
    }

    @Test
    public void moveUpAndDownReorderTheRules() {
        RuleTableModel model = modelWith(rule("甲"), rule("乙"), rule("丙"));

        assertTrue(model.moveDown(0));
        assertEquals("乙", model.getRuleAt(0).ruleName);
        assertEquals("甲", model.getRuleAt(1).ruleName);

        assertTrue(model.moveUp(2));
        assertEquals("丙", model.getRuleAt(1).ruleName);
        assertEquals("甲", model.getRuleAt(2).ruleName);

        assertFalse("首行不能再上移", model.moveUp(0));
        assertFalse("末行不能再下移", model.moveDown(model.getRowCount() - 1));
    }

    @Test
    public void outOfRangeAccessIsSafe() {
        RuleTableModel model = modelWith(rule("甲"));

        assertNull(model.getRuleAt(-1));
        assertNull(model.getRuleAt(9));
        assertNull("越界读值应返回 null 而不是抛异常", model.getValueAt(9, 0));
        model.setValueAt("x", 9, RuleTableModel.COL_NAME); // 不该抛异常
        assertFalse("没有行时不可编辑", model.isCellEditable(9, 0));
    }

    @Test
    public void modelEditsTheSameListItWasGivenSoChangesLandOnTheRules() {
        NovelRule shared = rule("甲站");
        ArrayList<NovelRule> rules = new ArrayList<>(Arrays.asList(shared));

        RuleTableModel model = new RuleTableModel(rules);
        model.setValueAt("改过", 0, RuleTableModel.COL_NAME);

        assertSame("模型必须直接持有调用方的列表，否则编辑结果落不到规则上",
                shared, rules.get(0));
        assertEquals("改过", shared.ruleName);
    }

    @Test
    public void nullListIsTolerated() {
        RuleTableModel model = new RuleTableModel(null);

        assertEquals(0, model.getRowCount());
        assertNull(model.getRuleAt(0));
    }
}
