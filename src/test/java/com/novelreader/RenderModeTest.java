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
        assertEquals(RenderMode.BOTH, RenderMode.parse("BOTH"));
    }

    @Test
    public void parseIsCaseInsensitiveAndTrims() {
        assertEquals(RenderMode.PANEL, RenderMode.parse("panel"));
        assertEquals(RenderMode.BOTH, RenderMode.parse("  Both  "));
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
    public void notificationOnlyModeNeverTouchesThePanel() {
        assertTrue(RenderMode.NOTIFICATION.includesNotification());
        assertFalse("仅通知模式不该去动面板", RenderMode.NOTIFICATION.includesPanel());
    }

    @Test
    public void panelOnlyModeNeverSendsNotifications() {
        assertTrue(RenderMode.PANEL.includesPanel());
        assertFalse("仅面板模式不该再发正文通知", RenderMode.PANEL.includesNotification());
    }

    @Test
    public void bothModeDrivesBothTargets() {
        assertTrue(RenderMode.BOTH.includesNotification());
        assertTrue(RenderMode.BOTH.includesPanel());
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
        assertEquals("只应有三种渲染模式", 3, RenderMode.values().length);
    }
}
