package com.novelreader;

import com.novelreader.model.NovelRule;
import com.novelreader.parser.RuleLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 规则保存：备份、原子写、往返不丢字段。
 *
 * <p>图形化编辑规则最大的风险是「保存把用户手写的内容弄没了」，所以这里重点覆盖：
 * <ul>
 *   <li>保存前<b>原文件被备份</b>（注释丢了还能捞回来）；</li>
 *   <li>写入是原子的（不留 {@code .tmp} 残留，也不会出现半个 JSON）；</li>
 *   <li>写出去再读回来，字段与版本号都在。</li>
 * </ul>
 */
public class RuleLoaderWriteTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path rulesFile() {
        return folder.getRoot().toPath().resolve("novelReader/rules.json");
    }

    private static NovelRule rule(String name, String pattern) {
        NovelRule rule = new NovelRule();
        rule.ruleName = name;
        rule.siteMatch = new NovelRule.SiteMatch();
        rule.siteMatch.type = "host";
        rule.siteMatch.pattern = pattern;
        rule.toc = new NovelRule.TocConfig();
        rule.toc.linkSelector = "dd a";
        rule.content = new NovelRule.ContentConfig();
        rule.content.bodySelector = "#content";
        rule.content.removeSelectors = Arrays.asList("#content .ad", "#content .bottem");
        rule.paging = new NovelRule.PagingConfig();
        rule.headers.put("Referer", "https://example.com/");
        return rule;
    }

    private static RuleLoader.RuleSet setWith(NovelRule... rules) {
        RuleLoader.RuleSet set = new RuleLoader.RuleSet();
        set.rules = Arrays.asList(rules);
        return set;
    }

    @Test
    public void writeCreatesFileAndParentDirectories() throws Exception {
        Path file = rulesFile();
        assertFalse(Files.exists(file));

        Path backup = RuleLoader.write(setWith(rule("甲站", "a.com")), file);

        assertTrue("父目录应被自动创建", Files.exists(file));
        assertNull("原先没有文件，就不该有备份", backup);
    }

    @Test
    public void writeBacksUpTheExistingFileSoHandWrittenCommentsCanBeRecovered() throws Exception {
        Path file = rulesFile();
        Files.createDirectories(file.getParent());
        String original = "{ // 我手写的注释\n \"version\": 7, \"rules\": [] }";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));

        Path backup = RuleLoader.write(setWith(rule("甲站", "a.com")), file);

        assertNotNull("保存前必须备份原文件", backup);
        assertEquals("备份文件名应为 rules.json.bak",
                "rules.json.bak", backup.getFileName().toString());
        assertEquals("备份内容必须与原文件一字不差",
                original, new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
    }

    @Test
    public void writeLeavesNoTempFileBehind() throws Exception {
        Path file = rulesFile();

        RuleLoader.write(setWith(rule("甲站", "a.com")), file);

        assertFalse("不应留下 .tmp 残留",
                Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
    }

    @Test
    public void writtenRulesLoadBackWithAllFieldsIntact() throws Exception {
        Path file = rulesFile();
        RuleLoader.write(setWith(rule("甲站", "a.com")), file);

        RuleLoader.RuleSet loaded = RuleLoader.load(file);

        assertEquals(1, loaded.getRules().size());
        NovelRule rule = loaded.getRules().get(0);
        assertEquals("甲站", rule.ruleName);
        assertEquals("a.com", rule.siteMatch.pattern);
        assertEquals("dd a", rule.toc.linkSelector);
        assertEquals("#content", rule.content.bodySelector);
        assertEquals("剔除列表不能丢", 2, rule.content.removeSelectors.size());
        assertEquals("请求头不能丢", "https://example.com/", rule.headers.get("Referer"));
    }

    @Test
    public void versionIsPreservedAcrossEdits() throws Exception {
        Path file = rulesFile();
        RuleLoader.RuleSet set = setWith(rule("甲站", "a.com"));
        set.version = 7;

        RuleLoader.write(set, file);

        assertEquals("编辑一次不该把版本号退回默认值", 7, RuleLoader.load(file).version);
    }

    @Test
    public void overwritingTwiceKeepsWorkingAndBacksUpThePreviousVersion() throws Exception {
        Path file = rulesFile();
        RuleLoader.write(setWith(rule("第一版", "a.com")), file);

        RuleLoader.write(setWith(rule("第二版", "b.com")), file);

        assertEquals("第二次保存应备份第一次的内容",
                "第一版", RuleLoader.load(file.resolveSibling("rules.json.bak"))
                        .getRules().get(0).ruleName);
        assertEquals("第二版", RuleLoader.load(file).getRules().get(0).ruleName);
    }

    @Test
    public void backupExistingReturnsNullWhenThereIsNothingToBackUp() throws Exception {
        assertNull(RuleLoader.backupExisting(rulesFile()));
        assertNull(RuleLoader.backupExisting(null));
    }

    @Test
    public void writeRejectsInvalidArguments() {
        try {
            RuleLoader.write(setWith(new NovelRule()), null);
            fail("路径为空应当报错");
        } catch (IOException expected) {
            // 期望如此
        }
        try {
            RuleLoader.write(null, rulesFile());
            fail("规则集为空应当报错");
        } catch (IOException expected) {
            // 期望如此
        }
    }

    @Test
    public void copyRuleIsADeepCopySoEditingTheCopyDoesNotTouchTheOriginal() {
        NovelRule source = rule("甲站", "a.com");

        NovelRule copy = RuleLoader.copyRule(source);

        assertNotNull(copy);
        assertFalse("必须是不同对象", source == copy);
        copy.ruleName = "改过";
        copy.siteMatch.pattern = "b.com";
        copy.content.removeSelectors.add("#extra");

        assertEquals("原名不受影响", "甲站", source.ruleName);
        assertEquals("嵌套字段也要深拷贝", "a.com", source.siteMatch.pattern);
        assertEquals("列表字段不能共享引用", 2, source.content.removeSelectors.size());
    }

    @Test
    public void copyRuleHandlesNull() {
        assertNull(RuleLoader.copyRule(null));
    }

    @Test
    public void copiedRulesSurviveAFullWriteAndLoadCycle() throws Exception {
        Path file = rulesFile();
        List<NovelRule> copies = Arrays.asList(
                RuleLoader.copyRule(rule("甲站", "a.com")),
                RuleLoader.copyRule(rule("乙站", "b.com")));

        RuleLoader.write(setWith(copies.toArray(new NovelRule[0])), file);

        RuleLoader.RuleSet loaded = RuleLoader.load(file);
        assertEquals(2, loaded.getRules().size());
        assertEquals("乙站", loaded.getRules().get(1).ruleName);
        assertEquals("b.com", loaded.getRules().get(1).siteMatch.pattern);
    }
}
