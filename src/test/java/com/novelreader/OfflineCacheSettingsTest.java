package com.novelreader;

import com.novelreader.cache.ChapterCache;
import com.novelreader.reader.ReaderManager;
import com.novelreader.settings.NovelReaderSettings;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 离线缓存的开关语义与界面接线。
 *
 * <p>「关掉缓存就不再碰缓存」这条链路由两段组成，这里各钉一半：
 * <ul>
 *   <li>设置里关掉 → {@link ReaderManager#cacheFor} 返回 null（本类）；</li>
 *   <li>拿到 null → 抓取链路既不读缓存也不写缓存（见 {@code ChapterLoaderCacheTest}）。</li>
 * </ul>
 * 剩下的是「动作与设置页有没有真的接上」——这类错误代码看起来完全正常，
 * 只有点开界面才发现少了东西，所以用源码级断言守住。
 */
public class OfflineCacheSettingsTest {

    @Test
    public void cacheIsEnabledByDefault() {
        NovelReaderSettings settings = new NovelReaderSettings();
        assertTrue("章节缓存默认应开启（否则断网时什么都读不了）", settings.isCacheEnabled());
    }

    @Test
    public void disablingCacheRemovesItFromTheReadingPath() {
        NovelReaderSettings settings = new NovelReaderSettings();
        settings.setCacheEnabled(false);

        assertNull("关掉缓存后不应再拿到缓存实例（从而既不读也不写）",
                ReaderManager.cacheFor(settings));
    }

    @Test
    public void enablingCacheDoesNotThrowWithoutPlatform() {
        // 平台未就绪（普通单测）时取不到服务，应当安全返回 null 而不是抛异常 ——
        // 缓存绝不该成为阅读链路的故障点
        NovelReaderSettings settings = new NovelReaderSettings();
        assertNull("无平台环境下应返回 null", ReaderManager.cacheFor(settings));
        assertNull("设置本身缺失时也应返回 null", ReaderManager.cacheFor(null));
    }

    @Test
    public void toggleIsPersistedOnTheState() {
        NovelReaderSettings settings = new NovelReaderSettings();
        settings.setCacheEnabled(false);
        assertFalse(settings.isCacheEnabled());
        settings.setCacheEnabled(true);
        assertTrue("应当能再打开", settings.isCacheEnabled());
    }

    // ---------- 体积展示 ----------

    @Test
    public void humanSizeFormatsBytes() {
        assertEquals("512 B", ChapterCache.humanSize(512));
        assertEquals("1.0 KB", ChapterCache.humanSize(1024));
        assertEquals("1.5 KB", ChapterCache.humanSize(1536));
        assertEquals("1.0 MB", ChapterCache.humanSize(1024L * 1024L));
        assertEquals("2.5 MB", ChapterCache.humanSize((long) (2.5 * 1024 * 1024)));
        assertEquals("1.0 GB", ChapterCache.humanSize(1024L * 1024L * 1024L));
    }

    @Test
    public void humanSizeHandlesZero() {
        assertEquals("0 B", ChapterCache.humanSize(0));
    }

    // ---------- 接线守卫 ----------

    @Test
    public void cacheServiceAndActionAreRegisteredInPluginXml() throws Exception {
        String xml = Files.readString(Paths.get("src/main/resources/META-INF/plugin.xml"));

        assertTrue("ChapterCache 必须注册为应用级服务",
                xml.contains("com.novelreader.cache.ChapterCache"));
        int start = xml.indexOf("id=\"NovelReader.CacheWholeBook\"");
        assertTrue("plugin.xml 应声明「缓存整本书」动作", start >= 0);
        String block = xml.substring(start, xml.indexOf("</action>", start));
        assertTrue("应挂到虚拟子分组",
                block.contains("group-id=\"NovelReader.MenuItems\""));
        assertTrue("应有动作类", block.contains("class=\"com.novelreader.action.CacheWholeBookAction\""));
        assertTrue("应带快捷键", block.contains("first-keystroke=\"control alt shift D\""));
    }

    @Test
    public void settingsPageExposesTheCacheSwitchAndCleanup() throws Exception {
        String source = Files.readString(
                Paths.get("src/main/java/com/novelreader/settings/NovelReaderConfigurable.java"));

        assertTrue("设置页应有缓存开关", source.contains("cacheEnabledBox"));
        assertTrue("设置页应有清空缓存按钮", source.contains("clearCacheButton"));
        assertTrue("清空缓存应二次确认（缓存可能几百 MB，不该点一下就没）",
                source.contains("Messages.showYesNoDialog"));
        assertTrue("保存时应把开关写回设置", source.contains("setCacheEnabled"));
    }

    @Test
    public void chapterLoadsGoThroughTheCacheAwareEntryPoint() throws Exception {
        String source = Files.readString(
                Paths.get("src/main/java/com/novelreader/reader/ReaderManager.java"));

        // 阅读时的章节加载必须走「带缓存」的重载，否则读到的永远是网络版本
        assertTrue("ReaderManager 应把缓存传进加载链路",
                source.contains("ChapterLoader.load(state.getTocUrl(), target, rule, settings, cache)"));
    }
}
