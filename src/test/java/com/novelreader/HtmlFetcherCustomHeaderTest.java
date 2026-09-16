package com.novelreader;

import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.NovelRule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 规则里的自定义请求头（{@code NovelRule.headers}）要真的发出去。
 *
 * <p>用途：个别站点会额外校验 Referer、要特定 {@code X-Requested-With} 等。
 * 这些是站点级要求，所以和 UA 一样写在规则文件里，而不是全局设置。
 */
public class HtmlFetcherCustomHeaderTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/toc", exchange -> {
            lastExchange.set(exchange);
            byte[] body = "<html><body>ok</body></html>".getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static NovelRule ruleWithHeaders(String... keyValues) {
        NovelRule rule = new NovelRule();
        rule.ruleName = "带头部的规则";
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            rule.headers.put(keyValues[i], keyValues[i + 1]);
        }
        return rule;
    }

    @Test
    public void customHeadersAreSentOnTheRequest() throws Exception {
        NovelRule rule = ruleWithHeaders("X-Test-Header", "abc123", "X-Requested-With", "XMLHttpRequest");

        new HtmlFetcher(3000).get(base + "/toc", rule);

        HttpExchange exchange = lastExchange.get();
        assertEquals("自定义头应原样发出", "abc123",
                exchange.getRequestHeaders().getFirst("X-Test-Header"));
        assertEquals("自定义头应原样发出", "XMLHttpRequest",
                exchange.getRequestHeaders().getFirst("X-Requested-With"));
    }

    @Test
    public void ruleHeaderOverridesTheBuiltInReferer() throws Exception {
        // 内置逻辑会把 Referer 设成上一跳；规则显式声明时应当由规则说了算
        NovelRule rule = ruleWithHeaders("Referer", "https://expected.example.com/");

        new HtmlFetcher(3000).get(base + "/toc", rule);

        assertEquals("规则里的 Referer 应覆盖内置值",
                "https://expected.example.com/",
                lastExchange.get().getRequestHeaders().getFirst("Referer"));
    }

    @Test
    public void noHeadersMeansNoExtraRequestHeaders() throws Exception {
        NovelRule rule = new NovelRule();
        rule.ruleName = "没有自定义头";

        new HtmlFetcher(3000).get(base + "/toc", rule);

        HttpExchange exchange = lastExchange.get();
        assertNull("没配就不该凭空多出这个头", exchange.getRequestHeaders().getFirst("X-Test-Header"));
        assertNull("没配就不该凭空多出这个头", exchange.getRequestHeaders().getFirst("X-Requested-With"));
        // 内置头仍在，说明没被"清空"
        assertTrue("内置 UA 应仍然存在",
                exchange.getRequestHeaders().getFirst("User-Agent") != null);
    }

    @Test
    public void nullRuleStillWorksAndSendsDefaultHeaders() throws Exception {
        new HtmlFetcher(3000).get(base + "/toc", null);

        assertTrue("rule 为 null 时也应按默认头抓取",
                lastExchange.get().getRequestHeaders().getFirst("User-Agent") != null);
    }

    @Test
    public void headersGetterSkipsEmptyEntries() {
        NovelRule rule = new NovelRule();
        rule.headers.put("  ", "value");
        rule.headers.put("key", "   ");
        rule.headers.put("  Good-Key  ", "  good-value  ");

        assertEquals("空键与空值都要跳过，键值要 trim", 1, rule.getHeaders().size());
        assertEquals("good-value", rule.getHeaders().get("Good-Key"));
        assertFalse("空键不该出现", rule.getHeaders().containsKey(""));
    }

    @Test
    public void headersGetterIsSafeWhenHeadersIsNull() {
        NovelRule rule = new NovelRule();
        rule.headers = null;

        assertTrue("headers 为 null 时返回空表而不是 NPE", rule.getHeaders().isEmpty());
    }
}
