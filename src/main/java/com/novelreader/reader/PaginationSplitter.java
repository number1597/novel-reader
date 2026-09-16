package com.novelreader.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * 把整章正文按「每段最大字数」切分为若干段，供通知逐段展示。
 *
 * <p>切分规则：
 * <ul>
 *   <li>优先在中文标点（。！？…；）或换行处断句，避免把一句话劈开；</li>
 *   <li>若窗口内找不到标点，则硬切，保证一定能推进（不会死循环）；</li>
 *   <li>切分是无损的：所有分段按顺序拼接后与原文完全一致，不丢字也不多加字符。</li>
 * </ul>
 */
public final class PaginationSplitter {

    /** 断句优先级字符，命中即在该字符之后断开。 */
    private static final String BREAK_CHARS = "。！？…；;!?\n";

    private PaginationSplitter() {
    }

    /**
     * @param text     正文
     * @param maxChars 每段最大字数（<=0 时按 1 处理）
     * @return 分段列表；输入为空时返回空列表
     */
    public static List<String> split(String text, int maxChars) {
        List<String> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }
        int max = Math.max(1, maxChars);
        int length = text.length();
        int start = 0;
        while (start < length) {
            int limit = Math.min(length, start + max);
            int cut = limit >= length ? length : findBreak(text, start, limit, max);
            segments.add(text.substring(start, cut));
            start = cut;
        }
        return segments;
    }

    /**
     * 在 {@code (start, limit)} 区间内自后向前寻找标点断点。
     * 为避免切出过短的段，只接受位于窗口后 2/3 区域的断点；找不到则返回 limit（硬切）。
     */
    private static int findBreak(String text, int start, int limit, int max) {
        int minCut = start + Math.max(1, max / 3);
        for (int i = limit - 1; i >= minCut; i--) {
            if (BREAK_CHARS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return limit;
    }

    /** 供测试与断言使用：把分段还原回原文。 */
    public static String join(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            sb.append(segment);
        }
        return sb.toString();
    }
}
