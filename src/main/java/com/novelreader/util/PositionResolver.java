package com.novelreader.util;

import java.util.List;

/**
 * 「把一个保存下来的阅读位置，还原成当前目录里的合法位置」的纯逻辑。
 *
 * <h3>为什么单独抽出来</h3>
 * 阅读历史与书签都要做同一件事：拿存下来的章 / 段，在<b>可能已经变过的</b>章节目录里定位。
 * 两份实现各写一遍，早晚会在「越界怎么夹」这种细节上分叉出不一致的行为，
 * 所以这里只留一份，两边都委托过来。
 *
 * <h3>策略：URL 优先，下标兜底</h3>
 * 站点目录改版后增删章节，存下来的<b>下标会漂移</b>（本来第 12 章变成第 13 章），
 * 而章 URL 是稳定的 —— 所以优先按 URL 找同一章，找不到才退回下标，并夹到合法范围。
 *
 * <p>本类不碰文件、不碰平台 API、不发网络请求，可直接用普通单元测试覆盖。
 */
public final class PositionResolver {

    private PositionResolver() {
    }

    /**
     * 把「保存过的章节」解析为当前章节目录里的下标。
     *
     * @param chapterUrls  当前解析出的章节 URL 列表，顺序与章节一致；可为 null
     * @param chapterUrl   保存过的章节 URL；为空则直接用下标
     * @param fallbackIndex 保存过的章节下标（越界会被夹取，负数视为 0）
     * @return 合法的章节下标，保证落在 {@code [0, max(0, size-1)]}
     */
    public static int resolveChapterIndex(List<String> chapterUrls, String chapterUrl,
                                          int fallbackIndex) {
        int last = chapterUrls == null ? -1 : chapterUrls.size() - 1;
        String url = normalize(chapterUrl);
        if (!url.isEmpty() && chapterUrls != null) {
            for (int i = 0; i < chapterUrls.size(); i++) {
                if (url.equals(chapterUrls.get(i))) {
                    return i;
                }
            }
        }
        if (last < 0) {
            return 0;
        }
        return Math.min(Math.max(0, fallbackIndex), last);
    }

    /**
     * 把段落游标夹到本章实际段数的合法范围。
     *
     * <p>站点正文长度可能变化，存下来的段号未必还在；越界时退到<b>最后一段</b>
     * 而不是第 1 段，这样「继续阅读」不会倒退。
     *
     * @param segmentCount 本章实际段数
     * @return 合法的段下标；{@code segmentCount <= 0} 时返回 0
     */
    public static int resolveSegmentIndex(int segmentIndex, int segmentCount) {
        if (segmentCount <= 0) {
            return 0;
        }
        return Math.min(Math.max(0, segmentIndex), segmentCount - 1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
