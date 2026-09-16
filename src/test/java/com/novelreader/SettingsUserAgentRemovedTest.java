package com.novelreader;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 源码级守卫：设置里不再有全局 User-Agent。
 *
 * <p>保留这个字段的代价不是「多一行 UI」，而是<b>难查的故障</b>：UA 是站点级属性
 * （有的站拉黑浏览器 UA、有的站反之），一个全局字段会在用户改完之后让某个站点
 * 莫名其妙读不了，而从规则文件上完全看不出原因 —— 本项目已亲历
 * danfoaaa.com「WAF 拉黑浏览器 UA」的问题。
 *
 * <p>所以这里用源码级断言把这个决定钉死，防止以后有人"顺手"把它加回来。
 */
public class SettingsUserAgentRemovedTest {

    private static final Path SETTINGS =
            Path.of("src/main/java/com/novelreader/settings/NovelReaderSettings.java");
    private static final Path CONFIGURABLE =
            Path.of("src/main/java/com/novelreader/settings/NovelReaderConfigurable.java");

    @Test
    public void settingsNoLongerStoreAGlobalUserAgent() throws Exception {
        String source = Files.readString(SETTINGS);

        assertFalse("设置状态里不应再有 UA 字段（UA 只认规则文件）",
                source.contains("public String userAgent"));
        assertFalse("不应再有 UA 的读方法", source.contains("getUserAgent"));
        assertFalse("不应再有 UA 的写方法", source.contains("setUserAgent"));
    }

    @Test
    public void settingsPageNoLongerShowsTheUserAgentRow() throws Exception {
        String source = Files.readString(CONFIGURABLE);

        assertFalse("设置页不应再有 UA 输入框", source.contains("userAgentField"));
        assertFalse("设置页不应再有 UA 的输入行",
                source.contains("addLabeledComponent(\"User-Agent"));
    }

    @Test
    public void settingsPageStillExposesTheRetryCount() throws Exception {
        String source = Files.readString(CONFIGURABLE);

        assertTrue("重试次数应当可以在设置页调整", source.contains("失败重试次数"));
        assertTrue("重试次数应接入设置状态", source.contains("getMaxRetries"));
    }
}
