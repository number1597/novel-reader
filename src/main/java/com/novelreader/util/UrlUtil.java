package com.novelreader.util;

import com.novelreader.model.NovelRule;

import java.net.URI;
import java.net.URL;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** URL 处理的工具方法：相对链接补全、域名提取、规则匹配。 */
public final class UrlUtil {

    private UrlUtil() {
    }

    public static boolean isAbsolute(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * 把 {@code url} 解析为绝对 URL。已经是绝对地址时原样返回；
     * 协议相对地址（//host/path）补上 https；解析失败时返回原值。
     */
    public static String toAbsolute(String url, String baseUrl) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isAbsolute(trimmed)) {
            return trimmed;
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return trimmed;
        }
        try {
            return new URL(new URL(baseUrl), trimmed).toString();
        } catch (Exception e) {
            return trimmed;
        }
    }

    /** 提取小写域名，失败返回空串。 */
    public static String host(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 判断某条规则是否命中给定 URL。
     *
     * <p>siteMatch.type 支持：
     * <ul>
     *   <li>{@code host}（默认）：域名全等或为其子域名；pattern 可写 {@code example.com}，
     *       也可误写成完整 URL，会自动取出域名部分。</li>
     *   <li>{@code url_contains}：URL 包含 pattern 子串。</li>
     *   <li>{@code regex}：pattern 作为正则对完整 URL 做 find 匹配。</li>
     * </ul>
     */
    public static boolean matches(NovelRule rule, String url) {
        if (rule == null || url == null || url.trim().isEmpty()) {
            return false;
        }
        NovelRule.SiteMatch match = rule.siteMatch;
        if (match == null || match.pattern == null || match.pattern.trim().isEmpty()) {
            // 未配置 siteMatch 时，退化为用 exampleUrl 的域名匹配
            String exampleHost = host(rule.exampleUrl);
            return !exampleHost.isEmpty() && host(url).endsWith(exampleHost);
        }

        String pattern = match.pattern.trim();
        String type = match.type == null || match.type.trim().isEmpty() ? "host" : match.type.trim();

        switch (type.toLowerCase()) {
            case "url_contains":
                return url.contains(pattern);
            case "regex":
                try {
                    return Pattern.compile(pattern).matcher(url).find();
                } catch (PatternSyntaxException e) {
                    return false;
                }
            case "host":
            default:
                String patternHost = pattern.contains("://") ? host(pattern) : pattern.toLowerCase();
                if (patternHost.isEmpty()) {
                    return false;
                }
                String actual = host(url);
                return actual.equals(patternHost) || actual.endsWith("." + patternHost);
        }
    }
}
