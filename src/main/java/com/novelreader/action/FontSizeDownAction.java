package com.novelreader.action;

import com.novelreader.settings.NovelReaderSettings;

/** 正文字号 -1。 */
public class FontSizeDownAction extends DisplayTweakAction {

    @Override
    protected boolean canAdjust(NovelReaderSettings settings) {
        return settings.getFontSize() > NovelReaderSettings.MIN_FONT_SIZE;
    }

    @Override
    protected void adjust(NovelReaderSettings settings) {
        settings.setFontSize(settings.getFontSize() - NovelReaderSettings.FONT_SIZE_STEP);
    }
}
