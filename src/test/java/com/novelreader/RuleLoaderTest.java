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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 规则文件加载与匹配。 */
public class RuleLoaderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void parsesJsonWithTrailingCommasAndComments() throws IOException {
        String json = "{\n"
                + "  // 顶层版本号\n"
                + "  \"version\": 1,\n"
                + "  \"rules\": [\n"
                + "    {\n"
                + "      \"ruleName\": \"容忍尾随逗号\",\n"
                + "      \"exampleUrl\": \"https://example.com/novel/1/\",   // URL 里的 // 不能被当注释\n"
                + "      \"siteMatch\": {\"type\": \"host\", \"pattern\": \"example.com\",},\n"
                + "      /* 目录配置 */\n"
                + "      \"toc\": {\"containerSelector\": \"#list\", \"linkSelector\": \"dd a\",},\n"
                + "    },\n"
                + "  ]\n"
                + "}\n";
        Path file = folder.newFile("rules.json").toPath();
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        RuleLoader.RuleSet ruleSet = RuleLoader.load(file);

        assertEquals(1, ruleSet.getRules().size());
        NovelRule parsed = ruleSet.getRules().get(0);
        assertEquals("容忍尾随逗号", parsed.getRuleName());
        assertEquals("#list", parsed.getContainerSelector());
        assertEquals("dd a", parsed.getLinkSelector());
        assertEquals("https://example.com/novel/1/", parsed.exampleUrl);
    }

    @Test
    public void sanitizeKeepsUrlSlashesAndStringCommas() {
        String json = "{\"a\":\"https://x.com//p, q\",\"b\":[1,2,],}";

        String sanitized = RuleLoader.sanitize(json);

        assertEquals("{\"a\":\"https://x.com//p, q\",\"b\":[1,2]}", sanitized);
    }

    @Test
    public void rejectsBrokenJsonWithReadableMessage() {
        Path file = folder.getRoot().toPath().resolve("broken.json");
        try {
            Files.write(file, "{\"rules\":[ }".getBytes(StandardCharsets.UTF_8));
            RuleLoader.load(file);
            org.junit.Assert.fail("应当抛出 IOException");
        } catch (IOException e) {
            assertTrue("错误信息应包含文件名", e.getMessage().contains("broken.json"));
        }
    }

    @Test
    public void bundledDefaultRulesAreShippedAsClasspathResource() throws IOException {
        // 安装后自带默认规则：资源必须真的打进包里，且能解析
        String json = RuleLoader.defaultTemplateJson();

        assertTrue("默认规则资源不应为空", json != null && json.trim().length() > 0);
        assertTrue("默认规则应为 JSON 对象", json.trim().startsWith("{"));

        RuleLoader.RuleSet ruleSet = RuleLoader.parse(json, "rules-default.json");
        assertTrue("默认规则至少要有一条", ruleSet.getRules().size() >= 1);
    }

    @Test
    public void bundledDefaultRulesAreNotEmptyOnTheTestClasspath() throws IOException {
        // 回归防线：早先模板在资源缺失时会静默退化成 {"rules":[]}，
        // 结果是「装完插件却一个站点都读不了」，且没有任何报错。
        // 这里断言测试环境与生产环境看到的是同一份非空规则。
        RuleLoader.RuleSet ruleSet = RuleLoader.parse(
                RuleLoader.defaultTemplateJson(), "rules-default.json");

        assertFalse("默认规则不得静默为空 —— 空规则会让插件开箱即废",
                ruleSet.getRules().isEmpty());
    }

    @Test
    public void bundledDefaultRulesMatchTheTianLaiSite() throws IOException {
        RuleLoader.RuleSet ruleSet = RuleLoader.parse(
                RuleLoader.defaultTemplateJson(), "rules-default.json");

        NovelRule matched = RuleLoader.match(ruleSet.getRules(), "https://www.tlxsbook.com/255_255330/");

        assertNotNull("默认规则应能匹配天籁小说网的目录页 URL", matched);
        assertEquals("GBK", matched.getEncoding());
        assertEquals("#list dl", matched.getContainerSelector());
        assertEquals("#content", matched.getBodySelector());
    }

    @Test
    public void bundledDefaultRulesAlsoMatchMobileSubdomain() throws IOException {
        RuleLoader.RuleSet ruleSet = RuleLoader.parse(
                RuleLoader.defaultTemplateJson(), "rules-default.json");

        // host 匹配要覆盖 m. 子域，移动端链接同样能读
        assertNotNull("默认规则应覆盖 m. 子域",
                RuleLoader.match(ruleSet.getRules(), "https://m.tlxsbook.com/255_255330/"));
    }

    @Test
    public void writeTemplateIfAbsentWritesTheBundledDefaultRules() throws IOException {
        Path file = folder.getRoot().toPath().resolve("novelReader/rules.json");
        RuleLoader.writeTemplateIfAbsent(file);

        assertTrue("默认规则文件应被创建", Files.exists(file));

        RuleLoader.RuleSet ruleSet = RuleLoader.load(file);
        NovelRule matched = RuleLoader.match(ruleSet.getRules(), "https://www.tlxsbook.com/255_255330/");
        assertNotNull("落盘的默认规则应可直接用于匹配", matched);
        assertEquals("GBK", matched.getEncoding());
    }

    @Test
    public void writeTemplateIfAbsentNeverOverwritesUserEdits() throws IOException {
        Path file = folder.getRoot().toPath().resolve("novelReader/rules.json");
        Files.createDirectories(file.getParent());
        Files.write(file, "{\"version\":1,\"rules\":[]}".getBytes(StandardCharsets.UTF_8));

        RuleLoader.writeTemplateIfAbsent(file);

        // 用户已有的规则文件绝不能被默认规则覆盖
        assertEquals("{\"version\":1,\"rules\":[]}",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    public void matchResolvesHostSubdomains() {
        NovelRule rule = new NovelRule();
        rule.siteMatch.type = "host";
        rule.siteMatch.pattern = "example.com";
        java.util.List<NovelRule> rules = java.util.List.of(rule);

        assertNotNull(RuleLoader.match(rules, "https://example.com/a"));
        assertNotNull(RuleLoader.match(rules, "https://www.example.com/a"));
        assertNotNull(RuleLoader.match(rules, "https://m.example.com/a"));
        assertNull(RuleLoader.match(rules, "https://notexample.com/a"));
        assertNull(RuleLoader.match(rules, "https://example.org/a"));
    }

    @Test
    public void matchSupportsUrlContainsAndRegex() {
        NovelRule contains = new NovelRule();
        contains.siteMatch.type = "url_contains";
        contains.siteMatch.pattern = "/novel/";

        NovelRule regex = new NovelRule();
        regex.siteMatch.type = "regex";
        regex.siteMatch.pattern = "^https://[a-z]+\\.foo\\.com/.*$";

        java.util.List<NovelRule> rules = java.util.List.of(contains, regex);

        assertNotNull(RuleLoader.match(rules, "https://x.com/novel/1/"));
        assertNotNull(RuleLoader.match(rules, "https://abc.foo.com/1"));
        assertNull(RuleLoader.match(rules, "https://x.com/book/1/"));
    }

    @Test
    public void disabledRulesAreIgnored() {
        NovelRule rule = new NovelRule();
        rule.enabled = false;
        rule.siteMatch.type = "host";
        rule.siteMatch.pattern = "example.com";

        assertNull(RuleLoader.match(java.util.List.of(rule), "https://example.com/a"));
    }
}
