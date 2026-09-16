package com.novelreader.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 插件设置，持久化到 IDEA 配置目录下的 {@code novelReader.xml}。
 *
 * <p>注意：这里的 {@code novelReader.xml} 只保存「设置项」；小说解析规则本身存放在用户可编辑的
 * JSON 文件（见 {@link #getRulesFile()}）。
 *
 * <p><b>User-Agent 不在这里配置</b>：UA 是站点级属性（有的站会拉黑浏览器 UA），
 * 只认规则文件里的 {@code NovelRule.userAgent}，留空则用内置浏览器 UA。
 * 曾经在设置页放过一个全局 UA 字段，但它能读写却从未被读取（死字段），
 * 且「全局隐式生效 + 逐站需求不同」会制造最难排查的故障，故已删除。
 */
@State(name = "NovelReaderSettings", storages = @Storage("novelReader.xml"))
public class NovelReaderSettings implements PersistentStateComponent<NovelReaderSettings.State> {

    /** 默认每段字数。 */
    public static final int DEFAULT_MAX_CHARS = 300;

    /** 默认请求超时（毫秒）。 */
    public static final int DEFAULT_TIMEOUT_MS = 10000;

    /** 默认失败重试次数（首次请求之外的额外尝试次数）。 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** 允许配置的最大重试次数，防止误填成 999 把界面卡住。 */
    public static final int MAX_ALLOWED_RETRIES = 10;

    /** 默认阅读面板字号。 */
    public static final int DEFAULT_FONT_SIZE = 14;

    /** 允许配置的字号范围（太小看不清，太大一屏放不下几个字）。 */
    public static final int MIN_FONT_SIZE = 10;
    public static final int MAX_FONT_SIZE = 36;

    /** 字号每次调整的步进。 */
    public static final int FONT_SIZE_STEP = 1;

    /** 默认正文行距倍数（行高 = 字体行高 × 该值）。 */
    public static final float DEFAULT_LINE_SPACING = 1.6f;

    /** 允许的行距范围：1.0 = 不额外放大，3.0 = 三倍行高。 */
    public static final float MIN_LINE_SPACING = 1.0f;
    public static final float MAX_LINE_SPACING = 3.0f;

    /** 行距每次调整的步进。 */
    public static final float LINE_SPACING_STEP = 0.1f;

    /** 持久化的状态对象。 */
    public static class State {
        /** 规则 JSON 的路径；为空时使用配置目录下的默认路径。 */
        public String rulesPath = "";
        /** 每次在通知中展示的最大字数。 */
        public int maxCharsPerPage = DEFAULT_MAX_CHARS;
        /** 单次 HTTP 请求超时（毫秒）。 */
        public int requestTimeoutMs = DEFAULT_TIMEOUT_MS;
        /** 网络抖动时的最大重试次数；0 表示不重试。 */
        public int maxRetries = DEFAULT_MAX_RETRIES;
        /**
         * 正文渲染方式：NOTIFICATION / PANEL / BOTH。
         *
         * <p>刻意<b>存字符串而不是枚举</b>：避开 {@code @State} 序列化对枚举的处理细节，
         * 与既有的 encoding 等字符串字段风格一致；解析失败一律退回默认值
         * （见 {@link RenderMode#parse(String)}）。
         */
        public String renderMode = RenderMode.DEFAULT.name();
        /** 阅读面板的字号。 */
        public int fontSize = DEFAULT_FONT_SIZE;
        /**
         * 阅读面板的正文行距倍数。
         *
         * <p>旧版本升级上来的 XML 里没有这个字段，会取 Java 默认值 —— 也就是说
         * 老用户第一次打开新版会直接看到更松的行距。这是刻意的：默认值本来就偏紧。
         */
        public float lineSpacing = DEFAULT_LINE_SPACING;
        /**
         * 是否启用章节离线缓存。
         *
         * <p>默认开启：章节正文是静态内容，缓存下来既能让已读过的章节秒开，
         * 也是断网 / 站点挂掉时还能继续读的前提。
         * 旧版本升级上来的 XML 里没有这个字段，会取 Java 默认值 true，符合预期。
         */
        public boolean cacheEnabled = true;
    }

    private State state = new State();

    public static NovelReaderSettings getInstance() {
        return ApplicationManager.getApplication().getService(NovelReaderSettings.class);
    }

    /**
     * 平台尚未就绪（普通单元测试里跑生产逻辑）时返回 {@code null}。
     *
     * <p>调用方必须能接受 null：读设置的地方一律有默认值兜底
     * （见 {@code ChapterLoader} 对 null settings 的处理）。
     * 这样「是否启用缓存」这类判断在无平台环境下不会把测试炸掉。
     */
    public static NovelReaderSettings getInstanceOrNull() {
        Application application = ApplicationManager.getApplication();
        return application == null ? null : application.getService(NovelReaderSettings.class);
    }

    /** 规则 JSON 的默认位置：{IDEA 配置目录}/novelReader/rules.json。 */
    public static Path defaultRulesFile() {
        return Paths.get(PathManager.getConfigPath(), "novelReader", "rules.json");
    }

    /** 当前生效的规则文件路径。 */
    public Path getRulesFile() {
        String configured = state.rulesPath;
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return defaultRulesFile();
    }

    public String getRulesPath() {
        return state.rulesPath == null ? "" : state.rulesPath;
    }

    public void setRulesPath(String rulesPath) {
        state.rulesPath = rulesPath == null ? "" : rulesPath.trim();
    }

    public int getMaxCharsPerPage() {
        return state.maxCharsPerPage > 0 ? state.maxCharsPerPage : DEFAULT_MAX_CHARS;
    }

    public void setMaxCharsPerPage(int maxCharsPerPage) {
        state.maxCharsPerPage = Math.max(1, maxCharsPerPage);
    }

    public int getRequestTimeoutMs() {
        return state.requestTimeoutMs > 0 ? state.requestTimeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        state.requestTimeoutMs = Math.max(1000, requestTimeoutMs);
    }

    /** 网络抖动时的最大重试次数；0 表示不重试。 */
    public int getMaxRetries() {
        if (state.maxRetries < 0) {
            return DEFAULT_MAX_RETRIES;
        }
        return Math.min(state.maxRetries, MAX_ALLOWED_RETRIES);
    }

    public void setMaxRetries(int maxRetries) {
        state.maxRetries = Math.max(0, Math.min(maxRetries, MAX_ALLOWED_RETRIES));
    }

    /** 正文渲染方式；设置值非法时退回默认（见 {@link RenderMode#parse(String)}）。 */
    public RenderMode getRenderMode() {
        return RenderMode.parse(state.renderMode);
    }

    public void setRenderMode(RenderMode renderMode) {
        state.renderMode = (renderMode == null ? RenderMode.DEFAULT : renderMode).name();
    }

    /** 阅读面板字号。 */
    public int getFontSize() {
        if (state.fontSize < MIN_FONT_SIZE || state.fontSize > MAX_FONT_SIZE) {
            return DEFAULT_FONT_SIZE;
        }
        return state.fontSize;
    }

    public void setFontSize(int fontSize) {
        state.fontSize = Math.max(MIN_FONT_SIZE, Math.min(fontSize, MAX_FONT_SIZE));
    }

    /** 阅读面板正文行距倍数（行高 = 字体行高 × 该值）。 */
    public float getLineSpacing() {
        if (state.lineSpacing < MIN_LINE_SPACING || state.lineSpacing > MAX_LINE_SPACING) {
            return DEFAULT_LINE_SPACING;
        }
        return state.lineSpacing;
    }

    public void setLineSpacing(float lineSpacing) {
        float clamped = Math.max(MIN_LINE_SPACING, Math.min(lineSpacing, MAX_LINE_SPACING));
        // 步进是 0.1，累加会攒出 1.2000000000000002 这种值：写进 XML 难看，
        // 更麻烦的是「是不是已经调到头了」这类比较会因此失效。落库前收敛到一位小数。
        state.lineSpacing = Math.round(clamped * 10f) / 10f;
    }

    /** 是否启用章节离线缓存。 */
    public boolean isCacheEnabled() {
        return state.cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        state.cacheEnabled = cacheEnabled;
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
