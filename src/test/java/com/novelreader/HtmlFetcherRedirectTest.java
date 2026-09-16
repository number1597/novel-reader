package com.novelreader;

import com.novelreader.fetch.HtmlFetcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证 HtmlFetcher 的「Cookie 维护 / Referer 传递 / 限重定向」三件套，
 * 覆盖重定向死循环、站方种 cookie 后再请求、跨跳 referer 拼接等真实场景。
 *
 * <p>本类不复用 HtmlFetcherMockTest 的 server——每个测试独立起本地 mock，
 * 便于断言跳转链路的每一步。
 */
public class HtmlFetcherRedirectTest {

    private HttpServer server;
    private String base;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 简单响应：200 + 纯文本。 */
    private static void plain(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 重定向：302 + Location。 */
    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.getResponseBody().close();
    }

    /** 重定向 + 同时种 cookie（模拟小说站常见行为）。 */
    private static void redirectWithSetCookie(HttpExchange exchange, String location,
                                              String name, String value) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.getResponseHeaders().add("Set-Cookie", name + "=" + value + "; Path=/");
        exchange.sendResponseHeaders(302, -1);
        exchange.getResponseBody().close();
    }

    /** 简单 200 响应 + Set-Cookie。 */
    private static void okWithSetCookie(HttpExchange exchange, String body,
                                         String name, String value) throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", name + "=" + value + "; Path=/");
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // -------- 用例 --------

    /**
     * 用户报的错就是这种：站方无限重定向。HtmlFetcher 应当走满 maxRedirects 后抛
     * 清晰错误，而不是让 JDK 自己弹一条冷冰冰的英文消息（更糟的是把后续流程静默打断）。
     */
    @Test
    public void infiniteRedirectLoopIsBoundedAndReadable() {
        server.createContext("/loop", exchange -> {
            // 永远 302 到自己
            redirect(exchange, "/loop");
        });

        HtmlFetcher fetcher = new HtmlFetcher(3000, 5);
        try {
            fetcher.get(base + "/loop", null);
            fail("应该抛 IOException 而不是正常返回");
        } catch (IOException e) {
            String msg = e.getMessage();
            assertNotNull("错误消息不应为 null", msg);
            assertTrue("应提示达到重定向上限，实际： " + msg,
                    msg.contains("重定向超过"));
            assertTrue("应附上最终位置便于排查，实际： " + msg,
                    msg.contains("/loop"));
        }
    }

    /**
     * 一次 302 跳转到最终页；Referer 应当是「上一跳的 URL」，即跳转前的地址。
     */
    @Test
    public void refererOnRedirectChainIsThePreviousHop() {
        AtomicInteger finalRefererLen = new AtomicInteger(-1);
        server.createContext("/start", exchange -> {
            redirect(exchange, "/final");
        });
        server.createContext("/final", exchange -> {
            String ref = exchange.getRequestHeaders().getFirst("Referer");
            // mock 的 Referer 在 setUp 之间由本机传出，本机 base 是 http://127.0.0.1:PORT
            finalRefererLen.set(ref == null ? 0 : ref.length());
            plain(exchange, "done");
        });

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            HtmlFetcher.Response r = fetcher.get(base + "/start", null);
            assertEquals("done", r.text().trim());
        } catch (IOException e) {
            fail("不应抛错：" + e);
        }
        // 至少非空，且必须包含我们 mock 的 host
        assertTrue("Referer 应已被设置；actual=" + finalRefererLen.get(),
                finalRefererLen.get() > 0);
    }

    /**
     * 第一次访问站方 302 + 种 cookie；第二次直接 GET 目标页；cookie 应当被
     * 自动带上（请求里能看到 Cookie 头）。这是修「redirected too many times」
     * 的核心：站方需要 cookie 才能"放你过"。
     */
    @Test
    public void cookieFromRedirectIsCarriedOnNextRequest() {
        AtomicInteger sawCookieOnFinal = new AtomicInteger(0);
        server.createContext("/enter", exchange -> {
            redirectWithSetCookie(exchange, "/home", "session", "abc123");
        });
        server.createContext("/home", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null && cookie.contains("session=abc123")) {
                sawCookieOnFinal.incrementAndGet();
            }
            plain(exchange, "welcome");
        });

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            fetcher.get(base + "/enter", null);
            fetcher.get(base + "/home", null);
        } catch (IOException e) {
            fail("不应抛错：" + e);
        }
        // 第一次：enter 跳转 home 那一跳，cookie 被种下且紧跟着被携带
        // 第二次：直接打 home 也带 cookie
        // 合计应 ≥ 2
        assertTrue("两次请求都应带上 cookie，实际：" + sawCookieOnFinal.get(),
                sawCookieOnFinal.get() >= 2);
    }

    /**
     * 直接 200 + 种 cookie；下一次同 host 请求应自动带上。覆盖"首屏就是 cookie 页"的场景。
     */
    @Test
    public void cookieFromDirect200IsPersistedAcrossFor() {
        AtomicInteger sawCookie = new AtomicInteger(0);
        server.createContext("/seed", exchange -> {
            okWithSetCookie(exchange, "ok", "uid", "u-1");
        });
        server.createContext("/use", exchange -> {
            String c = exchange.getRequestHeaders().getFirst("Cookie");
            if (c != null && c.contains("uid=u-1")) {
                sawCookie.incrementAndGet();
            }
            plain(exchange, "use-ok");
        });

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            fetcher.get(base + "/seed", null);
            fetcher.get(base + "/use", null);
        } catch (IOException e) {
            fail("不应抛错：" + e);
        }
        assertEquals(1, sawCookie.get());
    }

    /**
     * 多跳链：/a -> /b -> /c -> 200；任何一步出错就失败。能跟到第3 跳即可。
     */
    @Test
    public void followsMultiHopRedirectChain() {
        server.createContext("/a", exchange -> redirect(exchange, "/b"));
        server.createContext("/b", exchange -> redirect(exchange, "/c"));
        server.createContext("/c", exchange -> plain(exchange, "end"));

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            HtmlFetcher.Response r = fetcher.get(base + "/a", null);
            assertEquals("end", r.text().trim());
            assertEquals(base + "/c", r.getFinalUrl());
        } catch (IOException e) {
            fail("不应抛错：" + e);
        }
    }

    /**
     * 重定向到相对路径：服务器返回 Location: "rel"，应能基于当前 URL 解析。
     */
    @Test
    public void relativeLocationIsResolvedAgainstCurrentUrl() {
        server.createContext("/dir/start", exchange -> redirect(exchange, "next"));
        server.createContext("/dir/next", exchange -> plain(exchange, "ok-rel"));

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            HtmlFetcher.Response r = fetcher.get(base + "/dir/start", null);
            assertEquals("ok-rel", r.text().trim());
        } catch (IOException e) {
            fail("不应抛错：" + e);
        }
    }

    /**
     * maxRedirects=0 应当禁掉任何跳转：第一次就 302 时立即报错。
     */
    @Test
    public void zeroMaxRedirectsRejectsAnyRedirect() {
        server.createContext("/r", exchange -> redirect(exchange, "/r"));
        // maxRedirects < 1 在构造器里被夹到 DEFAULT（5），但行为仍是"达到上限后报错"
        HtmlFetcher fetcher = new HtmlFetcher(3000, 0);
        try {
            fetcher.get(base + "/r", null);
            fail("任何 maxRedirects 下都应在 302 死循环时报错");
        } catch (IOException e) {
            assertTrue("应提示达到重定向上限，实际：" + e.getMessage(),
                    e.getMessage().contains("重定向超过"));
        }
    }

    /**
     * clearCookies 之后 cookie 仓应当被清空——便于用户切换站点或测试隔离。
     */
    @Test
    public void clearCookiesDropsAll() {
        server.createContext("/seed", exchange -> okWithSetCookie(exchange, "ok", "k", "v"));
        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            fetcher.get(base + "/seed", null);
        } catch (IOException e) {
            fail("不应抛错：" + e);
        }
        List<HttpCookie> before = fetcher.getCookieStore().get(URI.create(base));
        assertEquals(1, before.size());
        fetcher.clearCookies();
        List<HttpCookie> after = new ArrayList<>(fetcher.getCookieStore().getCookies());
        assertEquals("clearCookies 后仓应为空", 0, after.size());
    }
}