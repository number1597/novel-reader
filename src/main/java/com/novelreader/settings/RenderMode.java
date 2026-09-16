package com.novelreader.settings;

/**
 * 正文渲染到哪儿。
 *
 * <p>默认 {@link #NOTIFICATION}：与插件历史行为完全一致，老用户升级后无感；
 * 想要专用阅读面板的人自己去设置里切换。
 *
 * <p>持久化时存的是枚举名（见 {@code NovelReaderSettings.State.renderMode} 的 String 字段），
 * 解析走 {@link #parse(String)} —— 非法值一律退回默认，绝不因为设置文件被改坏而读不了书。
 */
public enum RenderMode {

    /** 只发通知（默认）。 */
    NOTIFICATION,

    /** 只用专用阅读面板。 */
    PANEL,

    /** 面板与通知同时更新。 */
    BOTH;

    /** 默认模式。 */
    public static final RenderMode DEFAULT = NOTIFICATION;

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
        for (RenderMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return DEFAULT;
    }

    /** 该模式下正文是否要发通知。 */
    public boolean includesNotification() {
        return this != PANEL;
    }

    /** 该模式下正文是否要更新阅读面板。 */
    public boolean includesPanel() {
        return this != NOTIFICATION;
    }

    /** 设置页展示名。 */
    public String getDisplayName() {
        switch (this) {
            case PANEL:
                return "仅专用面板";
            case BOTH:
                return "面板 + 通知";
            default:
                return "仅通知（默认）";
        }
    }

    /** 让 {@code JComboBox} 直接显示中文名，省掉自定义渲染器。 */
    @Override
    public String toString() {
        return getDisplayName();
    }
}
