package com.novelreader;

import com.novelreader.settings.NovelReaderSettings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 阅读面板的显示设置：字号与行距。
 *
 * <p>这类值直接决定排版，写错的后果是"看着别扭"而不是报错，所以边界逐条钉死。
 *
 * <p>行距还多一层：它是浮点、又按 0.1 步进累加。若不收敛小数位，
 * 连点几次「行距+」就会攒出 {@code 1.2000000000000002} 这种值 ——
 * 写进 XML 难看是小事，更要紧的是「是不是已经调到头了」这类比较会因此失效，
 * 按钮的灰/亮状态就会漂。
 */
public class DisplaySettingsTest {

    @Test
    public void panelDefaultsAreFourteenPointFontAndOnePointFiveLineSpacing() {
        // 这两个数是产品默认值，写在测试里是为了让「有人顺手改了默认值」当场暴露：
        // 它们直接决定面板第一眼的观感，改动应当是有意的。
        assertEquals("面板默认字号", 14, NovelReaderSettings.DEFAULT_FONT_SIZE);
        assertEquals("面板默认行距", 1.5f, NovelReaderSettings.DEFAULT_LINE_SPACING, 0.0001f);

        NovelReaderSettings fresh = new NovelReaderSettings();
        assertEquals("全新安装就该是 14 号", 14, fresh.getFontSize());
        assertEquals("全新安装就该是 1.5 倍行距", 1.5f, fresh.getLineSpacing(), 0.0001f);
    }

    @Test
    public void lineSpacingHasSaneDefault() {
        assertEquals("默认行距就是常量里那个值",
                NovelReaderSettings.DEFAULT_LINE_SPACING,
                new NovelReaderSettings().getLineSpacing(), 0.0001f);
    }

    @Test
    public void lineSpacingFallsBackToDefaultWhenStoredValueIsOutOfRange() {
        assertEquals("低于下限（可能被手改坏了 XML）应退回默认",
                NovelReaderSettings.DEFAULT_LINE_SPACING, settingsWith(0.2f).getLineSpacing(), 0.0001f);
        assertEquals("高于上限同样退回默认",
                NovelReaderSettings.DEFAULT_LINE_SPACING, settingsWith(99f).getLineSpacing(), 0.0001f);
    }

    @Test
    public void lineSpacingIsClampedWhenSet() {
        NovelReaderSettings low = new NovelReaderSettings();
        low.setLineSpacing(0.1f);
        assertEquals(NovelReaderSettings.MIN_LINE_SPACING, low.getLineSpacing(), 0.0001f);

        NovelReaderSettings high = new NovelReaderSettings();
        high.setLineSpacing(99f);
        assertEquals(NovelReaderSettings.MAX_LINE_SPACING, high.getLineSpacing(), 0.0001f);
    }

    @Test
    public void lineSpacingIsRoundedToTenths() {
        NovelReaderSettings settings = new NovelReaderSettings();

        settings.setLineSpacing(1.2345f);

        assertEquals("落库前收敛到一位小数，避免浮点噪声被写进 XML",
                1.2f, settings.getLineSpacing(), 0.0001f);
    }

    @Test
    public void repeatedStepsDoNotAccumulateFloatNoise() {
        NovelReaderSettings settings = new NovelReaderSettings();
        settings.setLineSpacing(NovelReaderSettings.DEFAULT_LINE_SPACING);
        float start = settings.getLineSpacing();

        for (int i = 0; i < 5; i++) {
            settings.setLineSpacing(settings.getLineSpacing() + NovelReaderSettings.LINE_SPACING_STEP);
        }

        // 这里刻意用「精确相等」而不是带容差的比较：要证明的正是「一点浮点噪声都没有」。
        // 收敛到一位小数后 2.0f 是可精确表示的，所以必须严格相等 ——
        // 带容差的话 2.0000000000000004 这种值会被放过，"到没到头"的比较照样会漂。
        assertTrue("连点 5 次「行距+」应正好停在起点 + 0.5，实际是 " + settings.getLineSpacing(),
                settings.getLineSpacing() == start + NovelReaderSettings.LINE_SPACING_STEP * 5);
    }

    @Test
    public void fontSizeIsClampedAndFallsBackWhenOutOfRange() {
        NovelReaderSettings settings = new NovelReaderSettings();

        settings.setFontSize(999);
        assertEquals(NovelReaderSettings.MAX_FONT_SIZE, settings.getFontSize());

        settings.setFontSize(1);
        assertEquals(NovelReaderSettings.MIN_FONT_SIZE, settings.getFontSize());

        assertEquals("XML 里存了离谱的字号时退回默认",
                NovelReaderSettings.DEFAULT_FONT_SIZE,
                settingsWithFontSize(0).getFontSize());
    }

    private static NovelReaderSettings settingsWith(float lineSpacing) {
        NovelReaderSettings settings = new NovelReaderSettings();
        NovelReaderSettings.State state = new NovelReaderSettings.State();
        state.lineSpacing = lineSpacing;
        settings.loadState(state);
        return settings;
    }

    private static NovelReaderSettings settingsWithFontSize(int fontSize) {
        NovelReaderSettings settings = new NovelReaderSettings();
        NovelReaderSettings.State state = new NovelReaderSettings.State();
        state.fontSize = fontSize;
        settings.loadState(state);
        return settings;
    }
}
