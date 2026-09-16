package com.novelreader;

import com.novelreader.reader.ReadingPanel;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 阅读面板工具栏的按钮必须「一眼能分辨」。
 *
 * <h3>为什么值得单测</h3>
 * 平台的工具栏在动作**没有图标**时并不是留空，而是统一套上
 * {@code AllIcons.Toolbar.Unknown} 占位图 —— 于是所有按钮长得一模一样，
 * 而且**界面上不会有任何报错**：插件的 XML 是合法的、动作也正常可用，
 * 只有人肉点开面板才会发现"完全分不清哪个是哪个"。
 *
 * <p>同类坑还有一个更隐蔽的：新增动作时**复制粘贴上一段 XML 忘了改 icon**，
 * 于是两个按钮共用一个图标 —— 同样"分不出来"，同样不报错。
 *
 * <p>所以这里把三件事钉死：动作声明了图标、图标文件真的存在、图标互不重复。
 * 都是源码级校验，不启动平台。
 */
public class ReaderToolbarIconsTest {

    private static final Path PLUGIN_XML =
            Path.of("src/main/resources/META-INF/plugin.xml");

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static String pluginXml() throws Exception {
        return Files.readString(PLUGIN_XML);
    }

    /** 取某个动作的整段声明（属性可能跨多行）；找不到返回 null。 */
    private static String declarationOf(String actionId, String xml) {
        Matcher m = Pattern.compile(
                "<action id=\"" + Pattern.quote(actionId) + "\"([^>]*)>", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** 取某个动作声明的图标路径；未声明返回空串。 */
    private static String iconOf(String actionId, String xml) {
        String body = declarationOf(actionId, xml);
        if (body == null) {
            return "";
        }
        Matcher icon = Pattern.compile("icon=\"([^\"]+)\"").matcher(body);
        return icon.find() ? icon.group(1) : "";
    }

    @Test
    public void everyToolbarActionIsDeclaredInPluginXml() throws Exception {
        String xml = pluginXml();
        for (String[] group : ReadingPanel.TOOLBAR_GROUPS) {
            for (String id : group) {
                assertTrue("工具栏引用的动作必须在 plugin.xml 里声明，否则按钮不会出现：" + id,
                        declarationOf(id, xml) != null);
            }
        }
    }

    @Test
    public void everyToolbarActionDeclaresAnIcon() throws Exception {
        String xml = pluginXml();
        for (String[] group : ReadingPanel.TOOLBAR_GROUPS) {
            for (String id : group) {
                assertFalse("动作没声明图标时，工具栏会给它套一个统一的占位图，"
                                + "结果所有按钮长得一样（正是修过的 bug）：" + id,
                        iconOf(id, xml).isEmpty());
            }
        }
    }

    @Test
    public void everyToolbarIconFileExists() throws Exception {
        String xml = pluginXml();
        for (String[] group : ReadingPanel.TOOLBAR_GROUPS) {
            for (String id : group) {
                String icon = iconOf(id, xml);
                assertFalse("图标路径要非空： " + id, icon.isEmpty());
                // 声明形如 /icons/x.svg，对应 resources/icons/x.svg
                Path file = RESOURCES.resolve(icon.startsWith("/") ? icon.substring(1) : icon);
                assertTrue("图标文件不存在，图标会加载失败而显示为空白：" + file + "（动作 " + id + "）",
                        Files.exists(file));
                // 文件在但不合法（截断、写错内容）时，现象同样是「图标空白」，所以顺便看一眼内容
                assertTrue("图标文件内容不像 SVG：" + file,
                        Files.readString(file).contains("<svg"));
            }
        }
    }

    @Test
    public void toolbarIconsAreDistinct() throws Exception {
        String xml = pluginXml();
        List<String> icons = new ArrayList<>();
        for (String[] group : ReadingPanel.TOOLBAR_GROUPS) {
            for (String id : group) {
                icons.add(iconOf(id, xml));
            }
        }
        Set<String> unique = new LinkedHashSet<>(icons);
        assertTrue("有动作共用了同一个图标，按钮就又分不出来了：" + icons + " -> " + unique,
                unique.size() == icons.size());
    }

    @Test
    public void toolbarGroupsAreWellFormed() {
        assertTrue("工具栏至少要有一组按钮", ReadingPanel.TOOLBAR_GROUPS.length > 0);
        Set<String> seen = new LinkedHashSet<>();
        for (String[] group : ReadingPanel.TOOLBAR_GROUPS) {
            assertTrue("不该出现空分组（会渲染出多余的分隔线）", group.length > 0);
            for (String id : group) {
                assertTrue("同一个动作不该在工具栏里出现两次：" + id, seen.add(id));
            }
        }
    }
}
