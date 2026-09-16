package com.novelreader.action;

import com.novelreader.settings.NovelReaderSettings;

/** 正文行距 -0.1 倍。 */
public class LineSpacingDownAction extends DisplayTweakAction {

    /** 浮点容差：行距步进是 0.1 倍，这个量级远小于一步，不会误判。 */
    private static final float EPSILON = 0.001f;

    @Override
    protected boolean canAdjust(NovelReaderSettings settings) {
        return settings.getLineSpacing() - NovelReaderSettings.LINE_SPACING_STEP
                >= NovelReaderSettings.MIN_LINE_SPACING - EPSILON;
    }

    @Override
    protected void adjust(NovelReaderSettings settings) {
        settings.setLineSpacing(settings.getLineSpacing() - NovelReaderSettings.LINE_SPACING_STEP);
    }
}
