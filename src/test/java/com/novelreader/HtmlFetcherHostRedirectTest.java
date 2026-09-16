package com.novelreader;

import com.novelreader.fetch.HtmlFetcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 锁定 HtmlFetcher 在「真实站点反爬墙」场景下的行为。
 *
 * <p>用户报「Server redirected too many times (20)」的根因常常不是单 host 内
 * 的死循环，而是 PC 与移动两个 host 来回互跳（甚至只在 cookie 不达标时才会跳），
 * JDK 的 {@code HttpURLConnection} 在这种情况下会沿着同一条 connection 一直跟，
 * 直到达到内置上限才抛英文硬错。这一类测试就是要证明：
 * 即便站方真的把客户端逼到这种死循环，HtmlFetcher 也应当在 {@code maxRedirects}
 * 跳内立刻抛一条带位置的可读错误，而不是让 JDK 自己冒出来一条冷消息。
 */
public class HtmlFetcherHostRedirectTest {

    private HttpServer pcServer;
    private HttpServer mServer;
    private String pcBase;
    private String mBase;

    @Before
    public void setUp() throws IOException {
        pcServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pcServer.start();
        mServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mServer.start();
        pcBase = "http://127.0.0.1:" + pcServer.getAddress().getPort();
        mBase = "http://127.0.0.1:" + mServer.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (pcServer != null) pcServer.stop(0);
        if (mServer != null) mServer.stop(0);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.getResponseBody().close();
    }

    private static void redirectWithSetCookie(HttpExchange exchange, String location,
                                              String name, String value) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.getResponseHeaders().add("Set-Cookie", name + "=" + value + "; Path=/");
        exchange.sendResponseHeaders(302, -1);
        exchange.getResponseBody().close();
    }

    private static void plain(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * JDK 的硬错误关键字。该断言失败即说明 setInstanceFollowRedirects(false) 没生效。
     */
    private static void assertNotJdkHardError(String msg) {
        assertNotNull("错误消息不应为 null", msg);
        assertFalse("不应把 JDK 的硬错误透传出来：Server redirected too many times，实际： " + msg,
                msg.contains("Server redirected too many times"));
    }

    /**
     * 场景：PC 站 302 到移动站，移动站 302 回 PC 站。danfoaaa.com 之类站点的真实形态。
     */
    @Test
    public void pcToMobilePingPongIsBounded() {
        pcServer.createContext("/shu/509705/", exchange ->
                redirect(exchange, mBase + "/shu/509705/"));
        mServer.createContext("/shu/509705/", exchange ->
                redirect(exchange, pcBase + "/shu/509705/"));

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            fetcher.get(pcBase + "/shu/509705/", null);
            fail("PC↔移动无限跳应在 fetcher 自己手里结束，不能让 JDK 自己抛硬错");
        } catch (IOException e) {
            assertNotJdkHardError(e.getMessage());
            assertTrue("应提示重定向超过上限，实际： " + e.getMessage(),
                    e.getMessage().contains("重定向超过"));
        }
    }

    /**
     * 场景：每次 302 都种 cookie，但同 host 内死循环。
     */
    @Test
    public void redirectLoopWithSetCookieIsStillBounded() {
        AtomicInteger hits = new AtomicInteger();
        pcServer.createContext("/loop", exchange -> {
            hits.incrementAndGet();
            redirectWithSetCookie(exchange, pcBase + "/loop", "session", "s-" + hits.get());
        });

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            fetcher.get(pcBase + "/loop", null);
            fail("应报错而不是无限跳转");
        } catch (IOException e) {
            assertNotJdkHardError(e.getMessage());
            assertTrue("应提示重定向超过上限，实际： " + e.getMessage(),
                    e.getMessage().contains("重定向超过"));
        }
        assertTrue("fetcher 应当限制每次请求最多跳 maxRedirects+1 次，实际跳了 " + hits.get() + " 次",
                hits.get() <= HtmlFetcher.DEFAULT_MAX_REDIRECTS + 1);
    }

    /**
     * 真实场景：先到 m 站被种 cookie，再跨 host 访问 PC 站，看是否在 PC 站带了 cookie。
     */
    @Test
    public void cookieFromMobileIsCarriedOnPcLaterRequest() {
        AtomicInteger sawCookieOnPc = new AtomicInteger();
        mServer.createContext("/seed", exchange ->
                redirectWithSetCookie(exchange, pcBase + "/seed/back", "ssid", "xyz"));

        pcServer.createContext("/seed/back", exchange -> {
            String c = exchange.getRequestHeaders().getFirst("Cookie");
            if (c != null && c.contains("ssid=xyz")) {
                sawCookieOnPc.incrementAndGet();
            }
            plain(exchange, "ok-on-pc");
        });

        HtmlFetcher fetcher = new HtmlFetcher(3000);
        try {
            fetcher.get(mBase + "/seed", null);
        } catch (IOException e) {
            fail("种子页不应报错：" + e);
        }

        try {
            fetcher.get(pcBase + "/seed/back", null);
        } catch (IOException e) {
            fail("PC 站后续访问不应报错：" + e);
        }
        assertTrue("同 fetcher 跨 host 后 PC 站请求应带 cookie，实际： " + sawCookieOnPc.get(),
                sawCookieOnPc.get() >= 1);
    }
}
