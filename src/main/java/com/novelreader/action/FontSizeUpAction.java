package com.novelreader.action;

import com.novelreader.settings.NovelReaderSettings;

/** 正文字号 +1。 */
public class FontSizeUpAction extends DisplayTweakAction {

    @Override
    protected boolean canAdjust(NovelReaderSettings settings) {
        return settings.getFontSize() < NovelReaderSettings.MAX_FONT_SIZE;
    }

    @Override
    protected void adjust(NovelReaderSettings settings) {
        settings.setFontSize(settings.getFontSize() + NovelReaderSettings.FONT_SIZE_STEP);
    }
}
