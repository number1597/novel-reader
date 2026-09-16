package com.novelreader.settings;

/**
 * 正文渲染到哪儿。
 *
 * <p>只有两种：{@link #NOTIFICATION}（默认，与插件历史行为完全一致）与 {@link #PANEL}
 * （IDE 右侧的专用阅读面板）。**刻意不做「两者同时」** —— 两种方式的阅读体验差别很大
 * （通知逐段推送、面板整章滚动），同时刷新只会让同一段正文出现两遍，
 * 而且「正文字号/行距」与「每段最大字数」也会互相矛盾。
 *
 * <p>持久化时存的是枚举名（见 {@code NovelReaderSettings.State.renderMode} 的 String 字段），
 * 解析走 {@link #parse(String)} —— 非法值一律退回默认，绝不因为设置文件被改坏而读不了书。
 */
public enum RenderMode {

    /** 只发通知（默认）。 */
    NOTIFICATION,

    /** 只用专用阅读面板。 */
    PANEL;

    /** 默认模式。 */
    public static final RenderMode DEFAULT = NOTIFICATION;

    /**
     * 历史上存在过的第三种取值：面板与通知同时刷新。
     *
     * <p>现已取消。老用户的 {@code novelReader.xml} 里可能还留着它，读到就按 {@link #PANEL}
     * 处理 —— 当初选「两者」的人本来就是想要面板，退回通知等于把他的面板悄悄拿走；
     * 反过来只是少发一份通知，不打扰。这也是本项目对旧设置值的一贯态度：
     * 宁可做一次有依据的映射，也不让用户面对「我设的那项去哪了」。
     */
    public static final String LEGACY_BOTH = "BOTH";

    /**
     * 解析持久化字符串。
     *
     * <p>null / 空白 / 拼错 / 大小写不一致都能容忍，退回 {@link #DEFAULT}。
     */
    public static RenderMode parse(String raw) {
        if (raw == null) {
            return DEFAULT;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return DEFAULT;
        }
        if (LEGACY_BOTH.equalsIgnoreCase(value)) {
            return PANEL;
        }
        for (RenderMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return DEFAULT;
    }

    /** 该模式下正文是否要发通知。 */
    public boolean includesNotification() {
        return this == NOTIFICATION;
    }

    /** 该模式下正文是否要更新阅读面板。 */
    public boolean includesPanel() {
        return this == PANEL;
    }

    /** 设置页展示名。 */
    public String getDisplayName() {
        return this == PANEL ? "专用面板" : "通知";
    }

    /** 让 {@code JComboBox} 直接显示中文名，省掉自定义渲染器。 */
    @Override
    public String toString() {
        return getDisplayName();
    }
}
