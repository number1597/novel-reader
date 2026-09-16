package com.novelreader;

import com.novelreader.fetch.RetryPolicy;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 重试策略（纯逻辑，不碰网络）。
 *
 * <p>这里的取舍直接决定用户体验：重试太少挡不住偶发抖动，重试太多会让用户干等，
 * 而在「站点明确拒绝」的情况下重试纯属浪费。因此逐条钉死。
 */
public class RetryPolicyTest {

    private static final int MAX_ATTEMPTS = RetryPolicy.DEFAULT_MAX_ATTEMPTS;

    @Test
    public void retriesTransientNetworkFailures() {
        assertTrue("读超时应重试", RetryPolicy.isRetryable(new SocketTimeoutException("Read timed out")));
        assertTrue("连接被拒应重试", RetryPolicy.isRetryable(new ConnectException("Connection refused")));
        assertTrue("连接被重置应重试", RetryPolicy.isRetryable(new SocketException("Connection reset")));
    }

    @Test
    public void unwrapsExceptionCauseChain() {
        IOException wrapped = new IOException("外层包装", new SocketTimeoutException("timeout"));

        assertTrue("套了一层的超时也要能认出来", RetryPolicy.isRetryable(wrapped));

        IOException deep = new IOException("a", new IOException("b", new ConnectException("refused")));
        assertTrue("嵌套多层的连接失败也要能认出来", RetryPolicy.isRetryable(deep));
    }

    @Test
    public void doesNotRetryPermanentNetworkFailures() {
        assertFalse("域名解析失败不该重试（多半是域名写错了）",
                RetryPolicy.isRetryable(new UnknownHostException("no-such-host")));
        assertFalse("null 不该被当成可重试", RetryPolicy.isRetryable(null));
    }

    @Test
    public void doesNotRetryOurOwnBusinessErrors() {
        // 这类错误是站点行为（一直把你踢到 /404.html），重试只会让用户多等几秒才看到真相
        IOException redirectLoop = new IOException(
                "被重定向超过 5 次（最后位置 https://www.example.com/404.html）");

        assertFalse("重定向死循环不该重试", RetryPolicy.isRetryable(redirectLoop));
    }

    @Test
    public void retriesServerErrorsAndThrottling() {
        assertTrue("500 是服务端临时故障，该重试",
                RetryPolicy.isRetryable(new RetryPolicy.HttpStatusException(500, "boom")));
        assertTrue("503 该重试",
                RetryPolicy.isRetryable(new RetryPolicy.HttpStatusException(503, "unavailable")));
        assertTrue("429 是限流，退避之后通常能过",
                RetryPolicy.isRetryable(new RetryPolicy.HttpStatusException(429, "too many requests")));
    }

    @Test
    public void doesNotRetryClientErrors() {
        assertFalse("403 是站点明确拒绝，重试无益",
                RetryPolicy.isRetryable(new RetryPolicy.HttpStatusException(403, "forbidden")));
        assertFalse("404 不该重试",
                RetryPolicy.isRetryable(new RetryPolicy.HttpStatusException(404, "not found")));
    }

    @Test
    public void shouldRetryStopsAtTheLastAttempt() {
        IOException transientError = new SocketTimeoutException("timeout");

        assertTrue("第 1 次失败后还应再试", RetryPolicy.shouldRetry(1, MAX_ATTEMPTS, transientError));
        assertTrue("第 2 次失败后还应再试", RetryPolicy.shouldRetry(2, MAX_ATTEMPTS, transientError));
        assertFalse("到达上限就不再重试", RetryPolicy.shouldRetry(3, MAX_ATTEMPTS, transientError));
        assertFalse("超过上限也不重试", RetryPolicy.shouldRetry(4, MAX_ATTEMPTS, transientError));
        assertFalse("maxAttempts=1 表示根本不重试", RetryPolicy.shouldRetry(1, 1, transientError));
    }

    @Test
    public void shouldRetryChecksTheErrorTypeToo() {
        IOException permanent = new RetryPolicy.HttpStatusException(404, "not found");

        assertFalse("上限没到但错误不值得重试时也不重试",
                RetryPolicy.shouldRetry(1, MAX_ATTEMPTS, permanent));
    }

    @Test
    public void backoffGrowsExponentiallyAndIsCapped() {
        assertEquals(500L, RetryPolicy.baseDelayMs(1));
        assertEquals(1000L, RetryPolicy.baseDelayMs(2));
        assertEquals(2000L, RetryPolicy.baseDelayMs(3));
        assertEquals(4000L, RetryPolicy.baseDelayMs(4));
        assertEquals("越涨越慢要封顶，否则用户以为插件卡死了",
                RetryPolicy.MAX_DELAY_MS, RetryPolicy.baseDelayMs(5));
        assertEquals(0L, RetryPolicy.baseDelayMs(0));
    }

    @Test
    public void jitterStaysWithinTwentyFivePercent() {
        assertEquals("抖动下限 0.75×", 375L, RetryPolicy.delayMs(1, 0.0));
        assertEquals("抖动中值 1.0×", 500L, RetryPolicy.delayMs(1, 0.5));
        assertEquals("抖动上限 1.25×", 625L, RetryPolicy.delayMs(1, 1.0));
        assertEquals("越界的随机值被夹取，不会算出负数", 375L, RetryPolicy.delayMs(1, -1.0));
        assertEquals("越界的随机值被夹取，不会等超长", 625L, RetryPolicy.delayMs(1, 2.0));
        assertEquals(0L, RetryPolicy.delayMs(0, 0.5));
    }

    @Test
    public void defaultAttemptsIsThree() {
        assertEquals("默认总共试 3 次（首次 + 2 次重试）", 3, RetryPolicy.DEFAULT_MAX_ATTEMPTS);
    }
}
