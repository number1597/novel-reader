package com.novelreader;

import com.novelreader.action.NovelReaderActionGroup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tools → Novel Reader 二级子菜单的结构约束。
 *
 * <p>这些断言都是「看一眼就知道对不对、但写错了极难从现象反推」的坑：
 * <ul>
 *   <li>父节点必须 {@code isPopup()}，否则它退化成一个普通动作，点下去不展开菜单；</li>
 *   <li>菜单文案必须非空，否则平台会回退显示动作 id / 类短名；</li>
 *   <li>子菜单条目必须全部来自 {@code childGroupId}，即 XML 声明要能真正装载进来。</li>
 * </ul>
 *
 * <p>这里只做源码级 / 常量级校验，不启动 IntelliJ 平台——平台侧的装载由
 * {@code buildSearchableOptions} 在真实 IDEA 中验证。
 */
public class NovelReaderMenuGroupTest {

    private static final Path PLUGIN_XML =
            Path.of("src/main/resources/META-INF/plugin.xml");

    /** 期望出现在二级子菜单里的全部动作，顺序即 menu 中的声明顺序。 */
    private static final String[] ACTION_IDS = {
            "NovelReader.PasteTocUrl",
            "NovelReader.History",
            "NovelReader.NextSegment",
            "NovelReader.PrevSegment",
            "NovelReader.NextChapter",
            "NovelReader.PrevChapter",
            "NovelReader.ChapterList",
            "NovelReader.AddBookmark",
            "NovelReader.Bookmarks",
            "NovelReader.CacheWholeBook",
            "NovelReader.StopReading",
    };

    private static String pluginXml() throws Exception {
        return Files.readString(PLUGIN_XML);
    }

    @Test
    public void parentGroupDeclaresItselfAsPopup() {
        // 关键实现细节：copyFromGroup 会覆盖 popup 标记，所以构造函数里
        // 必须先 copyFromGroup 再 setPopup(true)。这条断言就是防这个回归。
        String source = readSource("src/main/java/com/novelreader/action/NovelReaderActionGroup.java");
        int copyIndex = source.indexOf("copyFromGroup");
        int popupIndex = source.indexOf("setPopup(true)");
        assertTrue("构造里必须调用 copyFromGroup", copyIndex >= 0);
        assertTrue("构造里必须调用 setPopup(true)", popupIndex >= 0);
        assertTrue("setPopup(true) 必须在 copyFromGroup 之后，否则 popup 标记会被覆盖",
                popupIndex > copyIndex);
    }

    @Test
    public void childGroupIdIsUsedByEveryAction() throws Exception {
        String xml = pluginXml();

        assertTrue("需要有虚拟子分组声明", xml.contains("id=\"" + NovelReaderActionGroup.CHILD_GROUP_ID + "\""));

        // 每个动作都应挂到虚拟子分组下，而不是直接挂到 ToolsMenu
        int toolsMenuDirect = countOccurrences(xml, "group-id=\"ToolsMenu\"");
        assertEquals("Tools 根菜单下只应挂父节点一个条目", 1, toolsMenuDirect);

        String childrenGroup = "group-id=\"" + NovelReaderActionGroup.CHILD_GROUP_ID + "\"";
        assertEquals("声明的动作都应注册到虚拟子分组",
                ACTION_IDS.length, countOccurrences(xml, childrenGroup));
    }

    @Test
    public void noActionIsLeftBehindInToolsRoot() throws Exception {
        String xml = pluginXml();

        // 回归防线：历史版本把动作直接挂在 ToolsMenu 下，改造后不应再有残留
        for (String actionId : ACTION_IDS) {
            int actionStart = xml.indexOf("id=\"" + actionId + "\"");
            assertTrue("plugin.xml 应声明动作 " + actionId, actionStart >= 0);
            int actionEnd = xml.indexOf("</action>", actionStart);
            String block = xml.substring(actionStart, actionEnd);
            assertFalse(actionId + " 不应再直接挂到 ToolsMenu",
                    block.contains("group-id=\"ToolsMenu\""));
            assertTrue(actionId + " 应挂到虚拟子分组",
                    block.contains("group-id=\"" + NovelReaderActionGroup.CHILD_GROUP_ID + "\""));
        }
    }

    @Test
    public void parentGroupHasVisibleTextAndDoesNotCollideWithActionIds() throws Exception {
        assertFalse("子菜单文案不能为空，否则平台会回退成类短名",
                NovelReaderActionGroup.GROUP_TEXT.trim().isEmpty());

        String xml = pluginXml();
        String groupId = NovelReaderActionGroup.PARENT_GROUP_ID;
        assertTrue("父组 id 必须与动作 id 区分开，避免互相覆盖",
                groupId.startsWith("NovelReader.") && !groupId.equals("NovelReader"));

        // 父组 id 不应被任何 <action id="..."> 复用（会互相覆盖）
        assertFalse("父组 id 与动作 id 冲突：<action id=\"" + groupId + "\">",
                xml.contains("<action id=\"" + groupId + "\""));
    }

    @Test
    public void keymapShortcutsAreStillDeclaredOnActions() throws Exception {
        String xml = pluginXml();

        // 快捷键绑定的是动作本身，收起菜单后必须仍然存在
        for (String shortcut : new String[]{"control alt N", "control alt H", "control alt shift L",
                "control alt PERIOD", "control alt COMMA",
                "control alt shift M", "control alt shift B", "control alt shift D"}) {
            assertTrue("快捷键应保留：" + shortcut, xml.contains("first-keystroke=\"" + shortcut + "\""));
        }
    }

    @Test
    public void bookmarkShortcutsDoNotStealDefaultIdeShortcuts() throws Exception {
        // 默认键位表里 control alt B 是「跳转到实现」、control alt M 是「提取方法」，
        // 都是高频键 —— 书签动作必须带上 shift，不能把它们顶掉。
        // 这条断言防的是「以后有人为了少按一个键把 shift 去掉」。
        String xml = pluginXml();
        for (String id : new String[]{"NovelReader.AddBookmark", "NovelReader.Bookmarks"}) {
            int start = xml.indexOf("id=\"" + id + "\"");
            assertTrue("plugin.xml 应声明动作 " + id, start >= 0);
            String block = xml.substring(start, xml.indexOf("</action>", start));
            assertFalse(id + " 不能用 control alt <字母>（会顶掉 IDE 默认高频快捷键）",
                    block.contains("first-keystroke=\"control alt B\"")
                            || block.contains("first-keystroke=\"control alt M\"")
                            || block.contains("first-keystroke=\"control alt H\""));
        }
    }

    @Test
    public void nullActionManagerYieldsEmptyMenuWithoutThrowing() {
        // 构造器允许注入 ActionManager 便于测试；传 null 时必须安全退化为空菜单
        NovelReaderActionGroup group = new NovelReaderActionGroup(null);

        // 注意：不能拿 getChildren((ActionManager) null) 来断言——DefaultActionGroup
        // 自身对 null 入参有 @NotNull 校验，那是平台约定、与本次改造无关。
        // 改为断言「子动作一个都没装载」这一同等事实：菜单条目数取自
        // getChildActionsOrStubs()，它是纯本地列表、不做校验。
        assertEquals("没有 ActionManager 时不应装载任何子动作",
                0, group.getChildActionsOrStubs().length);
        // 即便没有子动作，父节点仍应保持 popup 形态，避免退化成可点击动作
        assertTrue("父节点应始终是 popup 组", group.isPopup());
    }

    private static String readSource(String relative) {
        try {
            return Files.readString(Path.of(relative));
        } catch (Exception e) {
            throw new AssertionError("无法读取 " + relative, e);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
