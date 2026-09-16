package com.novelreader.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点解析规则。字段与 rules.json 中的单个规则对象一一对应。
 *
 * <p>所有 *Selector 字段均为 jsoup 原生 CSS 选择器语法
 * （如 {@code #content}、{@code .listmain dd a}、{@code a:contains(下一页)}），不支持 XPath。
 */
public class NovelRule {

    /** 规则名，仅用于在设置页/日志中展示。 */
    public String ruleName = "";

    /** 是否启用该规则。 */
    public boolean enabled = true;

    /** 示例目录页 URL，便于用户直接测试规则。 */
    public String exampleUrl = "";

    /** 站点匹配条件。 */
    public SiteMatch siteMatch = new SiteMatch();

    /** 编码策略：auto / UTF-8 / GBK / GB2312。 */
    public String encoding = "auto";

    /** 自定义 User-Agent，留空使用内置伪装 UA。 */
    public String userAgent = "";

    /**
     * 附加请求头，例如 {@code {"Referer": "https://example.com/"}}。
     *
     * <p>用于个别站点的额外要求（校验 Referer、要特定 {@code X-Requested-With} 等）。
     * 键名重复时以本表为准，可覆盖内置的 Accept / Accept-Language 等默认头；
     * {@code Cookie} 由抓取层自己维护，不建议在这里硬写。
     */
    public Map<String, String> headers = new LinkedHashMap<>();

    /** 目录页解析配置。 */
    public TocConfig toc = new TocConfig();

    /** 章节正文解析配置。 */
    public ContentConfig content = new ContentConfig();

    /** 章节多页翻页配置。 */
    public PagingConfig paging = new PagingConfig();

    public static class SiteMatch {
        /** host：域名全等（含子域名后缀）；url_contains：URL 包含子串；regex：正则匹配完整 URL。 */
        public String type = "host";
        public String pattern = "";
    }

    public static class TocConfig {
        /** 目录容器选择器，留空表示在整个页面中查找链接。 */
        public String containerSelector = "";
        /** 章节链接选择器，例如 {@code dd a}。 */
        public String linkSelector = "";
    }

    public static class ContentConfig {
        /** 章节标题选择器，留空则不提取标题。 */
        public String titleSelector = "";
        /** 正文容器选择器。 */
        public String bodySelector = "";
        /** 需要在正文中剔除的节点选择器（广告、推荐、分页链接等）。 */
        public List<String> removeSelectors = new ArrayList<>();
    }

    public static class PagingConfig {
        /** 下一页链接选择器；与 nextTextContains 二选一，优先使用选择器。 */
        public String nextSelector = "";
        /** 按链接文本查找下一页，默认「下一页」。 */
        public String nextTextContains = "下一页";
        /** 多页抓取的最大页数，防止站点互链导致死循环。 */
        public int maxPages = 20;
    }

    // ---------- 空值安全的访问方法 ----------

    public String getRuleName() {
        return ruleName == null || ruleName.isEmpty() ? "<未命名规则>" : ruleName;
    }

    public String getEncoding() {
        return encoding == null || encoding.isEmpty() ? "auto" : encoding.trim();
    }

    public String getContainerSelector() {
        return toc == null || toc.containerSelector == null ? "" : toc.containerSelector.trim();
    }

    public String getLinkSelector() {
        return toc == null || toc.linkSelector == null ? "" : toc.linkSelector.trim();
    }

    public String getTitleSelector() {
        return content == null || content.titleSelector == null ? "" : content.titleSelector.trim();
    }

    public String getBodySelector() {
        return content == null || content.bodySelector == null ? "" : content.bodySelector.trim();
    }

    public List<String> getRemoveSelectors() {
        if (content == null || content.removeSelectors == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String s : content.removeSelectors) {
            if (s != null && !s.trim().isEmpty()) {
                result.add(s.trim());
            }
        }
        return result;
    }

    public String getNextSelector() {
        return paging == null || paging.nextSelector == null ? "" : paging.nextSelector.trim();
    }

    public String getNextTextContains() {
        return paging == null || paging.nextTextContains == null ? "下一页" : paging.nextTextContains.trim();
    }

    public int getMaxPages() {
        return paging == null || paging.maxPages <= 0 ? 20 : paging.maxPages;
    }

    /**
     * 附加请求头（空值安全：跳过空键与空值，键与值都 trim）。
     *
     * <p>返回的是清理后的新表，调用方随便改都不会影响规则本身。
     */
    public Map<String, String> getHeaders() {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                clean.put(key, value);
            }
        }
        return clean;
    }
}
