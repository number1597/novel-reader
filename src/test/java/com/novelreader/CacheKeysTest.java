package com.novelreader;

import com.novelreader.cache.CacheKeys;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * URL → 缓存文件名片段的转换。
 *
 * <p>两条必须成立的性质：
 * <ul>
 *   <li><b>确定性</b>：同一个 URL 任何时候都要算出同一个 key，否则缓存写了也读不回来；</li>
 *   <li><b>文件名安全</b>：结果只含十六进制字符，不含 `/` `:` `?` 之类的路径字符 ——
 *       否则在 Windows 上会直接建不出文件。</li>
 * </ul>
 */
public class CacheKeysTest {

    @Test
    public void keyIsDeterministic() {
        String url = "https://www.tlxsbook.com/255_255330/12.html";
        assertEquals("同一个 URL 必须算出同一个 key", CacheKeys.key(url), CacheKeys.key(url));
    }

    @Test
    public void keyDiffersBetweenUrls() {
        String a = CacheKeys.key("https://a.com/1.html");
        String b = CacheKeys.key("https://a.com/2.html");
        assertNotEquals("不同 URL 应得到不同 key", a, b);
    }

    @Test
    public void keyIsFixedLengthLowercaseHex() {
        String key = CacheKeys.key("https://example.com/very/long/path/with?query=1&x=2");
        assertEquals("key 长度固定为 16", 16, key.length());
        assertTrue("key 只能是十六进制字符（文件名安全）：" + key,
                key.matches("[0-9a-f]{16}"));
    }

    @Test
    public void keyIgnoresSurroundingWhitespace() {
        assertEquals("URL 前后空白不该产生两个缓存目录",
                CacheKeys.key("https://a.com/1.html"),
                CacheKeys.key("  https://a.com/1.html\n"));
    }

    @Test
    public void blankOrNullUrlHasNoKey() {
        assertEquals("null 返回空串（调用方据此跳过缓存）", "", CacheKeys.key(null));
        assertEquals("空串返回空串", "", CacheKeys.key(""));
        assertEquals("纯空白返回空串", "", CacheKeys.key("   "));
    }

    @Test
    public void keyIsCaseSensitive() {
        // 路径大小写不同就是不同资源，不能共用缓存
        assertNotEquals(CacheKeys.key("https://a.com/Content.html"),
                CacheKeys.key("https://a.com/content.html"));
    }

    @Test
    public void keyContainsNoPathSeparators() {
        String key = CacheKeys.key("https://a.com/shu/../shu/12.html?v=1#frag");
        assertTrue("不能出现路径分隔符：" + key, !key.contains("/") && !key.contains("\\"));
        assertTrue("不能出现冒号：" + key, !key.contains(":"));
    }
}
