package com.novelreader.fetch;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * 抓取失败后的重试策略（纯静态逻辑，不碰网络，便于单测）。
 *
 * <h3>为什么需要它</h3>
 * 小说站普遍不稳定：连接超时、连接被重置、502/503 抖动都常见。一次失败就报错给用户，
 * 体验上等于「这站不能用」。给网络类失败加少量指数退避重试，能挡掉绝大多数偶发故障。
 *
 * <h3>什么该重试、什么不该</h3>
 * 采用<b>白名单</b>而不是「凡是 IOException 就重试」，因为有些失败重试纯属浪费时间：
 * <ul>
 *   <li><b>可重试</b>：连接超时、连接被拒/重置等 {@link SocketException} 家族；
 *       HTTP <b>5xx</b>（服务端临时故障）与 <b>429</b>（被限流，退避后通常能过）。</li>
 *   <li><b>不重试</b>：其它 4xx（404 / 403 这类是站点明确拒绝，重试只会更慢）、
 *       以及本插件自己抛出的业务错误 —— 比如「被重定向超过 N 次」「服务器未返回 Location」，
 *       这些是站点行为，立刻报错让用户看到原因才有意义。</li>
 * </ul>
 * DNS 解析失败（{@code UnknownHostException}）也不重试：多半是域名写错了，重试无益。
 */
public final class RetryPolicy {

    /** 默认最大尝试次数（含首次）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 退避基准时长：第 1 次失败后等 500ms，之后翻倍。 */
    public static final long BASE_DELAY_MS = 500L;

    /** 退避上限，避免等太久让用户以为卡死了。 */
    public static final long MAX_DELAY_MS = 5000L;

    private RetryPolicy() {
    }

    /**
     * 携带 HTTP 状态码的失败，让重试策略能区分「5xx 可重试」与「4xx 别重试」。
     */
    public static class HttpStatusException extends IOException {
        private final int statusCode;

        public HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * 这次失败之后还应不应该再试一次。
     *
     * @param attempt    已经失败的尝试序号（1 表示第一次就失败了）
     * @param maxAttempts 总尝试次数上限（含首次）
     * @param error      失败原因
     */
    public static boolean shouldRetry(int attempt, int maxAttempts, Throwable error) {
        if (attempt < 1 || attempt >= Math.max(1, maxAttempts)) {
            return false;
        }
        return isRetryable(error);
    }

    /** 判断某个失败是否属于「重试有意义」的类型。 */
    public static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }
        HttpStatusException http = find(error, HttpStatusException.class);
        if (http != null) {
            return http.getStatusCode() >= 500 || http.getStatusCode() == 429;
        }
        if (find(error, SocketTimeoutException.class) != null) {
            return true;
        }
        if (find(error, ConnectException.class) != null) {
            return true;
        }
        // SocketException 覆盖 connection reset 等连接层抖动；
        // 注意 UnknownHostException 不在其列（DNS 失败多半是域名写错，重试无益）。
        return find(error, SocketException.class) != null;
    }

    /**
     * 第 {@code attempt} 次失败后应等待的毫秒数（不含抖动）。
     *
     * <p>500ms、1s、2s、4s，封顶 {@link #MAX_DELAY_MS}。
     */
    public static long baseDelayMs(int attempt) {
        if (attempt < 1) {
            return 0L;
        }
        long delay = BASE_DELAY_MS;
        for (int i = 1; i < attempt && delay < MAX_DELAY_MS; i++) {
            delay *= 2L;
        }
        return Math.min(delay, MAX_DELAY_MS);
    }

    /**
     * 加抖动后的等待时长。
     *
     * <p>抖动是为了避免「多个站点同时重试」或「重试节奏和站点限流窗口对齐」。
     * 随机源由调用方注入，测试才能拿到确定值。
     *
     * @param randomRatio {@code [0,1)} 的随机数；实际系数落在 0.75×~1.25× 之间
     */
    public static long delayMs(int attempt, double randomRatio) {
        long base = baseDelayMs(attempt);
        if (base <= 0) {
            return 0L;
        }
        double ratio = Math.max(0.0, Math.min(1.0, randomRatio));
        double factor = 0.75 + 0.5 * ratio;
        return Math.max(1L, Math.round(base * factor));
    }

    /** 在异常链里找指定类型的异常。 */
    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }
}
