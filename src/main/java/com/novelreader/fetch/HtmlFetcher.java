package com.novelreader.fetch;

import com.novelreader.model.NovelRule;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 在线抓取 HTML 并正确解码。
 *
 * <p>要点：
 * <ul>
 *   <li>伪装浏览器 User-Agent，主动声明 {@code Accept-Encoding: gzip} 并手动解压；</li>
 *   <li>编码嗅探顺序：规则指定 → 响应头 Content-Type → 页面 {@code <meta charset>} → 启发式兜底；</li>
 *   <li>始终以「字节流 + 显式字符集」交给 jsoup 解析，避免 GBK 站点乱码。</li>
 *   <li><b>自动维护 Cookie</b>：很多小说站首次访问种 cookie，缺 cookie 会被反复 302
 *       到验证页 / 错误页 → 形成"redirected too many times"循环；本类内部维护一个
 *       {@link CookieManager}（非全局），每次请求自动带 cookie、响应回来的 Set-Cookie
 *       自动入库。</li>
 *   <li><b>手动限重定向</b>：{@link HttpURLConnection} 没有公开 {@code setMaxRedirects}，
 *       JDK 21 默认跟 20 次。本类关掉自动跟随、自己实现 3xx 跟随循环，
 *       默认 5 次封顶后立刻抛带"被重定向到"+最终 URL 的可读错误。</li>
 *   <li><b>自动添加 Referer</b>：当前请求的来源站根域，用于通过站方常见的"referer 必须本站"
 *       反爬墙检查。Referer 选「同一 host 的上一跳 URL」，根域首次请求不带。</li>
 * </ul>
 */
public class HtmlFetcher {

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static final int DEFAULT_MAX_REDIRECTS = 5;

    /** 默认最大尝试次数（含首次），与 {@link RetryPolicy} 保持一致。 */
    public static final int DEFAULT_MAX_ATTEMPTS = RetryPolicy.DEFAULT_MAX_ATTEMPTS;

    private static final Pattern CONTENT_TYPE_CHARSET =
            Pattern.compile("charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern META_CHARSET =
            Pattern.compile("<meta[^>]*?charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private final int timeoutMs;
    private final int maxRedirects;
    /** 单次抓取的最大尝试次数（含首次）。网络抖动时按 {@link RetryPolicy} 退避重试。 */
    private final int maxAttempts;
    /** 跨请求持有的 cookie 仓；与 JDK 全局隔离。 */
    private final CookieManager cookieManager;

    public HtmlFetcher(int timeoutMs) {
        this(timeoutMs, DEFAULT_MAX_REDIRECTS);
    }

    public HtmlFetcher(int timeoutMs, int maxRedirects) {
        this(timeoutMs, maxRedirects, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * @param maxAttempts 总尝试次数（含首次）；小于 1 时退化为只试一次。
     *                    调用方通常把「用户设置的重试次数」+1 传进来。
     */
    public HtmlFetcher(int timeoutMs, int maxRedirects, int maxAttempts) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 10000;
        this.maxRedirects = maxRedirects > 0 ? maxRedirects : DEFAULT_MAX_REDIRECTS;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.cookieManager = new CookieManager();
        // 仅接受回 SAME_HOST / 标准 cookie，避免被奇怪源站点污染
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** 一次抓取的结果：已解压的字节 + 嗅探出的字符集 + 最终 URL（跟随重定向后）。 */
    public static final class Response {
        private final byte[] body;
        private final Charset charset;
        private final String finalUrl;
        private final String contentType;

        public Response(byte[] body, Charset charset, String finalUrl, String contentType) {
            this.body = body;
            this.charset = charset;
            this.finalUrl = finalUrl;
            this.contentType = contentType;
        }

        public byte[] getBody() {
            return body;
        }

        public Charset getCharset() {
            return charset;
        }

        public String getFinalUrl() {
            return finalUrl;
        }

        public String getContentType() {
            return contentType;
        }

        /** 按嗅探出的字符集解码为文本。 */
        public String text() {
            return new String(body, charset);
        }

        /** 以最终 URL 作为 baseUri 解析，保证相对链接可补全。 */
        public Document parse() {
            return Jsoup.parse(text(), finalUrl);
        }
    }

    /** 抓取并直接解析为 jsoup Document。 */
    public Document fetch(String url, NovelRule rule) throws IOException {
        return get(url, rule).parse();
    }

    /**
     * 抓取原始字节并完成 gzip 解压与编码嗅探。
     *
     * <p>外层按 {@link RetryPolicy} 做少量退避重试：<b>只重试网络类失败与 5xx / 429</b>；
     * 4xx 以及本类自己抛出的业务错误（如「重定向超限」「未返回 Location」）立刻抛出，
     * 不做无谓重试 —— 那些是站点的明确态度，重试只会让用户等更久。
     */
    public Response get(String url, NovelRule rule) throws IOException {
        if (url == null || url.isEmpty()) {
            throw new IOException("URL 为空");
        }
        IOException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchFollowingRedirects(url, rule);
            } catch (IOException e) {
                lastError = e;
                if (!RetryPolicy.shouldRetry(attempt, maxAttempts, e)) {
                    throw e;
                }
                if (!sleepBeforeRetry(attempt)) {
                    throw e; // 被中断就不再重试，交给上层处理
                }
            }
        }
        throw lastError == null ? new IOException("请求失败：" + url) : lastError;
    }

    /** 等待重试间隔（带抖动）；线程被中断时返回 false 表示应放弃重试。 */
    private boolean sleepBeforeRetry(int attempt) {
        long delay = RetryPolicy.delayMs(attempt, ThreadLocalRandom.current().nextDouble());
        if (delay <= 0) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 单次抓取：自己跟随 3xx（可限次数、可中断），并维护 Cookie 与 Referer。 */
    private Response fetchFollowingRedirects(String url, NovelRule rule) throws IOException {
        String currentUrl = url;
        URI currentUri = URI.create(currentUrl);
        String referer = null; // 每次请求的来源：上一跳的 URL
        Response last = null;

        for (int hops = 0; hops <= maxRedirects; hops++) {
            HttpURLConnection conn = (HttpURLConnection) currentUri.toURL().openConnection();
            try {
                conn.setInstanceFollowRedirects(false); // 自己跟随，可控、可中断
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestProperty("User-Agent", resolveUserAgent(rule));
                conn.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                conn.setRequestProperty("Accept-Encoding", "gzip");
                conn.setRequestProperty("Connection", "close");
                if (referer != null) {
                    conn.setRequestProperty("Referer", referer);
                }
                // 规则里声明的额外请求头最后应用：setRequestProperty 是覆盖语义，
                // 因此规则能盖掉上面这些默认头（例如强制指定 Referer、加 X-Requested-With）。
                // Cookie 由本类自己维护，不在这里处理。
                for (Map.Entry<String, String> header : ruleHeaders(rule).entrySet()) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }
                // 带上当前 host 适用的 cookie。CookieManager.get() 在没有 cookie 时返回空表。
                String cookieHeader = buildCookieHeader(currentUri);
                if (!cookieHeader.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHeader);
                }

                int code = conn.getResponseCode();
                // 3xx 自己处理
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    // 必须在 disconnect 之前 capture Set-Cookie，disconnect 后 header 就拿不到了
                    URI next;
                    if (location == null || location.isEmpty()) {
                        captureSetCookie(conn, currentUri);
                        conn.disconnect();
                        throw new IOException("HTTP " + code + "：服务器未返回 Location 头；" + currentUrl);
                    }
                    next = URI.create(location);
                    if (!next.isAbsolute()) {
                        next = currentUri.resolve(next);
                    }
                    // 收集 Set-Cookie（即使是重定向也保留站方种下的 cookie）
                    captureSetCookie(conn, next);
                    conn.disconnect();
                    currentUri = next;
                    referer = currentUri.toString();
                    continue;
                }
                if (code >= 400) {
                    String message = "HTTP " + code + "：" + currentUrl;
                    try (InputStream es = conn.getErrorStream()) {
                        if (es != null) {
                            byte[] err = readAll(es);
                            message += "（错误流 " + err.length + " 字节）";
                        }
                    } catch (IOException ignored) {
                        // 错误流读不到就算了
                    }
                    captureSetCookie(conn, currentUri);
                    conn.disconnect();
                    // 用带状态码的异常：重试策略据此区分「5xx/429 值得重试」与「4xx 别重试」
                    throw new RetryPolicy.HttpStatusException(code, message);
                }

                byte[] body;
                try (InputStream in = conn.getInputStream()) {
                    body = readAll(in);
                }
                // 必须在 disconnect 之前 capture（disconnect 后 header 就拿不到了）
                captureSetCookie(conn, currentUri);
                conn.disconnect();

                String contentEncoding = conn.getContentEncoding();
                if ((contentEncoding != null && contentEncoding.toLowerCase().contains("gzip"))
                        || looksLikeGzip(body)) {
                    body = gunzip(body);
                }

                String contentType = conn.getContentType();
                Charset charset = sniffEncoding(
                        contentType, body, rule == null ? "auto" : rule.getEncoding());
                String finalUrl = conn.getURL() == null ? currentUrl : conn.getURL().toString();
                last = new Response(body, charset, finalUrl, contentType);
                return last;
            } finally {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
        // 跳满 maxRedirects 次还没拿到内容：抛带可读信息的错误（典型表现即用户看到的
        // "Server redirected too many times"）。说明站方在循环跳。
        throw new IOException("被重定向超过 " + maxRedirects + " 次（最后位置 " + currentUri + "）；"
                + "可能站点反爬墙需要登录态，或 referer/cookie 不全");
    }

    /**
     * 读取响应里的 Set-Cookie，写入 cookie 仓。
     * JDK 的 {@code HttpURLConnection.getHeaderFields} 返回的是 "Set-Cookie" 列表，
     * 但不是解析后的 {@link HttpCookie}。所以这里手动 parse + put。
     *
     * <p>特别注意：服务器常常只发 {@code session=abc123; Path=/}，没有 Domain 字段；
     * 这样 {@link HttpCookie#getDomain()} 是 null，而 {@link CookieStore#get(URI)}
     * 默认按 domain 匹配，没有 Domain 的 cookie 永远不会匹配任何 host。
     * 这里显式把 Domain 设为本次响应对应的 host，把 Path 缺省补为 "/"。
     *
     * <p>JDK 在 HTTP/2 模式或某些实现下可能把 Set-Cookie 合并到 {@code getHeaderField}
     * 而非 {@code getHeaderFields} 里。这里两种方式都试，合并去重。
     */
    private void captureSetCookie(HttpURLConnection conn, URI forUri) {
        if (conn == null) {
            return;
        }
        Map<String, List<String>> headers = safeGetHeaderFields(conn);
        if (headers == null || headers.isEmpty()) {
            return;
        }
        String host = forUri.getHost();
        // 大小写不敏感地收集 Set-Cookie
        List<String> setCookies = new java.util.ArrayList<>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie") && e.getValue() != null) {
                setCookies.addAll(e.getValue());
            }
        }
        if (setCookies.isEmpty()) {
            return;
        }
        for (String raw : setCookies) {
            try {
                List<HttpCookie> parsed = HttpCookie.parse(raw);
                for (HttpCookie c : parsed) {
                    if (host != null && !host.isEmpty()
                            && (c.getDomain() == null || c.getDomain().isEmpty())) {
                        c.setDomain(host);
                    }
                    if (c.getPath() == null || c.getPath().isEmpty()) {
                        c.setPath("/");
                    }
                    try {
                        cookieManager.getCookieStore().add(forUri, c);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 安全读取 header 映射：disconnect 后某些 JDK 实现会抛 IllegalStateException。 */
    private static Map<String, List<String>> safeGetHeaderFields(HttpURLConnection conn) {
        try {
            return conn.getHeaderFields();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 把 cookie 仓里适用于 {@code uri} 的所有 cookie 拼成 {@code Cookie} 请求头的值。 */
    private String buildCookieHeader(URI uri) {
        List<HttpCookie> cookies = cookieManager.getCookieStore().get(uri);
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (HttpCookie c : cookies) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(c.getName()).append('=').append(c.getValue());
        }
        return sb.toString();
    }

    public CookieStore getCookieStore() {
        return cookieManager.getCookieStore();
    }

    /** 清 清除所有 cookie（便于测试隔离 / 切换站点时重置）。 */
    public void clearCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public static String resolveUserAgent(NovelRule rule) {
        if (rule != null && rule.userAgent != null && !rule.userAgent.trim().isEmpty()) {
            return rule.userAgent.trim();
        }
        return DEFAULT_USER_AGENT;
    }

    /**
     * 规则里声明的附加请求头。
     *
     * <p><b>User-Agent 只认规则</b>：{@link #resolveUserAgent(NovelRule)} 取不到时才用内置 UA。
     * 设置页不再提供全局 UA —— UA 是站点级属性，全局字段会造成「改一处、某站莫名读不了」
     * 且从规则文件上看不出原因。
     */
    private static Map<String, String> ruleHeaders(NovelRule rule) {
        return rule == null ? Collections.emptyMap() : rule.getHeaders();
    }

    // ---------- 编码嗅探 ----------

    /**
     * 编码嗅探：规则指定 → Content-Type → {@code <meta charset>} → 启发式兜底。
     *
     * @param contentType  响应头 Content-Type 原始值，可为 null
     * @param body         响应体字节（已解压）
     * @param ruleEncoding 规则中配置的编码，"auto" 表示自动
     */
    public static Charset sniffEncoding(String contentType, byte[] body, String ruleEncoding) {
        if (ruleEncoding != null && !ruleEncoding.trim().isEmpty()
                && !"auto".equalsIgnoreCase(ruleEncoding.trim())) {
            Charset explicit = forNameQuietly(ruleEncoding.trim());
            if (explicit != null) {
                return explicit;
            }
        }

        if (contentType != null) {
            Matcher m = CONTENT_TYPE_CHARSET.matcher(contentType);
            if (m.find()) {
                Charset cs = forNameQuietly(m.group(1));
                if (cs != null) {
                    return cs;
                }
            }
        }

        Charset meta = forNameQuietly(detectMetaCharset(body));
        if (meta != null) {
            return meta;
        }

        // 启发式：能把整段按 UTF-8 严格解码就认为是 UTF-8，否则按中文站点常见的 GBK 处理
        if (body != null && isValidUtf8(body)) {
            return StandardCharsets.UTF_8;
        }
        Charset gbk = forNameQuietly("GBK");
        return gbk != null ? gbk : StandardCharsets.UTF_8;
    }

    /** 从页面头部探测 {@code <meta charset>} / {@code <meta http-equiv="Content-Type">}。 */
    public static String detectMetaCharset(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        int limit = Math.min(body.length, 4096);
        String head = new String(body, 0, limit, StandardCharsets.ISO_8859_1);
        Matcher m = META_CHARSET.matcher(head);
        return m.find() ? m.group(1) : null;
    }

    public static Charset forNameQuietly(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String normalized = name.trim().replace("\"", "").replace("'", "");
        try {
            return Charset.forName(normalized);
        } catch (Exception e) {
            try {
                return Charset.forName(normalized.toUpperCase());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** 严格校验整段字节是否为合法 UTF-8。 */
    public static boolean isValidUtf8(byte[] body) {
        if (body == null || body.length == 0) {
            return true;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(body));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // ---------- gzip ----------

    public static byte[] gunzip(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAll(gz);
        }
    }

    /** 依据 gzip 魔数 (0x1f 0x8b) 判断，避免把普通 HTML 误判为压缩流。 */
    public static boolean looksLikeGzip(byte[] data) {
        return data != null && data.length > 2
                && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}