package com.novelreader.action;

import com.novelreader.settings.NovelReaderSettings;

/** 正文行距 +0.1 倍。 */
public class LineSpacingUpAction extends DisplayTweakAction {

    /** 浮点容差：行距步进是 0.1 倍，这个量级远小于一步，不会误判。 */
    private static final float EPSILON = 0.001f;

    @Override
    protected boolean canAdjust(NovelReaderSettings settings) {
        // 判据是「再加一步也不越界」，而不是「当前值小于上限」——
        // 后者在浮点边界上会多给一次机会，点下去却什么都不会变。
        return settings.getLineSpacing() + NovelReaderSettings.LINE_SPACING_STEP
                <= NovelReaderSettings.MAX_LINE_SPACING + EPSILON;
    }

    @Override
    protected void adjust(NovelReaderSettings settings) {
        settings.setLineSpacing(settings.getLineSpacing() + NovelReaderSettings.LINE_SPACING_STEP);
    }
}
