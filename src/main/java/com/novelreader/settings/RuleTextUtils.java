package com.novelreader.settings;

import com.novelreader.model.NovelRule;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 规则编辑器用到的文本 ⇄ 结构转换与校验（纯逻辑，不碰 Swing，便于单测）。
 *
 * <h3>为什么选择器列表「只按换行切」</h3>
 * CSS 选择器<b>本身可以含逗号</b>（{@code #content .ad, #content .bottem} 是一个合法的
 * 分组选择器）。若按逗号切分，用户写的分组选择器会被拆成两条无意义的选择器，
 * 结果是正文里该删的广告删不掉。所以这里只认换行作为分隔符 ——
 * 一行一条，逗号留给 CSS 自己用。
 */
public final class RuleTextUtils {

    /** 支持的匹配方式，与 {@code UrlUtil} 的实现保持一致。 */
    public static final List<String> MATCH_TYPES = List.of("host", "url_contains", "regex");

    private RuleTextUtils() {
    }

    // ---------- 选择器列表 ----------

    /** 选择器列表 → 文本（一行一条）。 */
    public static String joinSelectors(List<String> selectors) {
        if (selectors == null || selectors.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String selector : selectors) {
            if (selector == null || selector.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(selector.trim());
        }
        return sb.toString();
    }

    /**
     * 文本 → 选择器列表。
     *
     * <p>按<b>换行</b>切分（不切逗号，理由见类注释），trim、丢空行、去重且保持顺序。
     */
    public static List<String> splitSelectors(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    // ---------- 请求头 ----------

    /** 请求头 → 文本（一行一条 {@code Key: Value}）。 */
    public static String formatHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getKey().trim()).append(": ")
                    .append(entry.getValue() == null ? "" : entry.getValue().trim());
        }
        return sb.toString();
    }

    /**
     * 文本 → 请求头。
     *
     * <p>每行形如 {@code Key: Value}，**按第一个冒号切分** ——
     * 值里常常还有冒号（例如 {@code Referer: https://example.com/}），
     * 用 {@code split(":", 2)} 才不会把 URL 切坏。
     * 没有冒号的行、空键、空值一律跳过。
     */
    public static Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text == null || text.trim().isEmpty()) {
            return headers;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                headers.put(key, value);
            }
        }
        return headers;
    }

    // ---------- 校验 ----------

    /**
     * 校验一条规则能否正常工作，返回人话描述的问题（空列表表示没问题）。
     *
     * <p>只报<b>会导致解析失败</b>的问题，不做风格挑刺：规则名缺失、exampleUrl 缺失都不算问题。
     * 保存前把这些问题汇总给用户看，让他自己决定要不要改 —— 而不是拦着不让存。
     */
    public static List<String> describeProblems(NovelRule rule) {
        List<String> problems = new ArrayList<>();
        if (rule == null) {
            return List.of("存在空规则条目");
        }
        String name = rule.getRuleName();

        if (rule.siteMatch == null || isBlank(rule.siteMatch.pattern)) {
            problems.add(name + "：缺少 siteMatch.pattern，这条规则永远不会被命中");
        } else if (!MATCH_TYPES.contains(normalize(rule.siteMatch.type))) {
            problems.add(name + "：siteMatch.type 应为 " + MATCH_TYPES + " 之一，当前是「"
                    + rule.siteMatch.type + "」");
        }

        String encoding = rule.getEncoding();
        if (!"auto".equalsIgnoreCase(encoding) && !Charset.isSupported(encoding)) {
            problems.add(name + "：encoding「" + encoding + "」不是有效的字符集名");
        }

        if (isBlank(rule.getLinkSelector())) {
            problems.add(name + "：缺少 toc.linkSelector，解析不出章节列表");
        }
        if (isBlank(rule.getBodySelector())) {
            problems.add(name + "：缺少 content.bodySelector，抓不到正文");
        }
        if (rule.paging != null && rule.paging.maxPages <= 0) {
            problems.add(name + "：paging.maxPages 应为正数，当前是 " + rule.paging.maxPages);
        }
        return problems;
    }

    /** 汇总多条规则的问题。 */
    public static List<String> describeProblems(List<NovelRule> rules) {
        List<String> problems = new ArrayList<>();
        if (rules == null) {
            return problems;
        }
        for (NovelRule rule : rules) {
            problems.addAll(describeProblems(rule));
        }
        return problems;
    }

    /** 供下拉框/编辑框使用的匹配方式列表（不可变）。 */
    public static List<String> matchTypes() {
        return Collections.unmodifiableList(MATCH_TYPES);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
