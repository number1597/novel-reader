package com.novelreader;

import com.novelreader.settings.RenderMode;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 渲染模式的解析与判定。
 *
 * <p>{@code parse} 是<b>读取用户设置文件</b>的入口：设置可能被手改坏、被旧版本写入过、
 * 或大小写不一致。无论哪种情况都必须退回默认值 —— 绝不能因为一个设置项写错就让用户读不了书。
 *
 * <p>模式只有两种，所以判定是<b>互斥</b>的：任一模式下「发通知」与「用面板」恰好一真一假。
 * 这条不变量被单独钉死 —— 设置页正靠它决定哪些输入框该亮。
 */
public class RenderModeTest {

    @Test
    public void defaultIsNotificationSoUpgradesAreInvisible() {
        assertEquals("默认必须是通知，保持与历史行为一致",
                RenderMode.NOTIFICATION, RenderMode.DEFAULT);
    }

    @Test
    public void parseAcceptsExactNames() {
        assertEquals(RenderMode.NOTIFICATION, RenderMode.parse("NOTIFICATION"));
        assertEquals(RenderMode.PANEL, RenderMode.parse("PANEL"));
    }

    @Test
    public void parseIsCaseInsensitiveAndTrims() {
        assertEquals(RenderMode.PANEL, RenderMode.parse("panel"));
        assertEquals(RenderMode.PANEL, RenderMode.parse("  Panel  "));
        assertEquals(RenderMode.NOTIFICATION, RenderMode.parse("\tnotification\n"));
    }

    @Test
    public void parseFallsBackToDefaultOnGarbage() {
        assertEquals("null 退回默认", RenderMode.DEFAULT, RenderMode.parse(null));
        assertEquals("空白退回默认", RenderMode.DEFAULT, RenderMode.parse(""));
        assertEquals("纯空格退回默认", RenderMode.DEFAULT, RenderMode.parse("   "));
        assertEquals("拼错退回默认", RenderMode.DEFAULT, RenderMode.parse("SCREEN"));
        assertEquals("旧值/未知值退回默认", RenderMode.DEFAULT, RenderMode.parse("WINDOW"));
    }

    @Test
    public void legacyBothValueMapsToPanelInsteadOfSilentlyLosingThePanel() {
        // 早期版本有第三种取值「两者同时」。老 XML 里读到它不能退回默认（=通知），
        // 那等于把用户当初明确要的面板悄悄拿走；映射到面板只是少发一份通知，不打扰。
        assertEquals(RenderMode.LEGACY_BOTH + " 应映射到面板",
                RenderMode.PANEL, RenderMode.parse("BOTH"));
        assertEquals("大小写不一致也要能认出这个旧值",
                RenderMode.PANEL, RenderMode.parse("  Both  "));
        assertFalse("BOTH 已经不是一个合法模式了",
                java.util.Arrays.asList(RenderMode.values())
                        .contains(RenderMode.LEGACY_BOTH));
    }

    @Test
    public void notificationOnlyModeNeverTouchesThePanel() {
        assertTrue(RenderMode.NOTIFICATION.includesNotification());
        assertFalse("通知模式不该去动面板", RenderMode.NOTIFICATION.includesPanel());
    }

    @Test
    public void panelOnlyModeNeverSendsNotifications() {
        assertTrue(RenderMode.PANEL.includesPanel());
        assertFalse("面板模式不该再发正文通知", RenderMode.PANEL.includesNotification());
    }

    @Test
    public void theTwoTargetsAreMutuallyExclusiveInEveryMode() {
        // 设置页按这两个方法决定「每段最大字数」与「字号/行距」的启停，
        // 所以每个模式里必须恰好有一个为真 —— 两个都为真会让同一段正文出现两遍，
        // 都不为真则正文根本不显示。
        for (RenderMode mode : RenderMode.values()) {
            assertTrue("模式 " + mode + " 必须恰好命中一个渲染目标，"
                            + "实际 通知=" + mode.includesNotification()
                            + " 面板=" + mode.includesPanel(),
                    mode.includesNotification() ^ mode.includesPanel());
        }
    }

    @Test
    public void everyModeHasAUniqueDisplayName() {
        Set<String> names = new HashSet<>();
        for (RenderMode mode : RenderMode.values()) {
            String name = mode.getDisplayName();
            assertFalse("每个模式都要有展示名", name == null || name.trim().isEmpty());
            assertTrue("展示名不能重复：" + name, names.add(name));
            assertEquals("toString 应当就等于展示名，省掉下拉框的自定义渲染器",
                    name, mode.toString());
        }
        assertEquals("只应有两种渲染模式", 2, RenderMode.values().length);
    }
}
