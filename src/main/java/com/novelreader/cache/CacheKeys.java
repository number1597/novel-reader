package com.novelreader.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 把 URL 变成可以安全当文件名的字符串。
 *
 * <h3>为什么用摘要而不是直接清洗 URL</h3>
 * 缓存目录的名字只能来自 URL。直接清洗（去掉 `:/?#`）会留下两个问题：
 * <ul>
 *   <li><b>长度不可控</b>：小说章节 URL 常有很长的路径与参数，容易撞上 Windows 的路径长度上限；</li>
 *   <li><b>不唯一 + 大小写不敏感</b>：清洗后可能有两个不同 URL 映射到同一个名字
 *       （Windows 文件名不区分大小写），互相覆盖。</li>
 * </ul>
 * 摘要没有这两个问题：长度固定、不同 URL 的碰撞概率可忽略。
 * 代价是目录名不可读 —— 但目录里那份 `book.json` / 章节文件内部都存着原始 URL 与书名，
 * 想查是哪个站只需要打开看一眼，命令行 ls 的观感不值这个风险。
 *
 * <p>纯静态、无副作用，可直接单测。
 */
public final class CacheKeys {

    /** 取 SHA-256 十六进制前 16 位：足够避免碰撞，又比全量摘要短一半。 */
    private static final int KEY_LENGTH = 16;

    private CacheKeys() {
    }

    /**
     * URL → 文件名片段。
     *
     * <p>先 trim 再摘要：URL 前后空白不该产生两个不同的缓存目录。
     *
     * @param url 原始 URL；null / 空返回空串（调用方据此判断「不可缓存」）
     */
    public static String key(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(KEY_LENGTH);
            for (byte b : bytes) {
                if (sb.length() >= KEY_LENGTH) {
                    break;
                }
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                if (sb.length() >= KEY_LENGTH) {
                    break;
                }
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常，属于不可恢复错误
            throw new IllegalStateException("运行环境缺少 SHA-256 算法", e);
        }
    }
}
