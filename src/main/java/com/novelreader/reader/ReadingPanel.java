package com.novelreader.reader;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.UIUtil;
import com.novelreader.model.ReaderState;
import com.novelreader.settings.NovelReaderSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * 阅读面板：<b>整章正文</b>，配一条复用现有动作的工具栏。
 *
 * <h3>为什么显示整章而不是仅当前段</h3>
 * 通知只能显示一段（约 300 字）是因为通知本身超长会截断、不能滚动。
 * 面板没有这个限制，于是可以一次给出整章内容自由滚动阅读 —— 这才是「面板」相对「通知」的真正价值。
 *
 * <h3>面板里为什么没有「当前段高亮」和翻页按钮</h3>
 * 既然整章都在眼前、由读者自己滚动，"读到第几段"就不再是有意义的位置概念：
 * 高亮块会随通知模式的翻页键到处跳，反而干扰阅读。翻页按钮同理 —— 整章就在下面，
 * 往下滚即可，不需要"下一页"。<b>但段游标本身仍然保留</b>：通知模式、阅读历史、
 * 书签都按段定位，只是面板不再把它可视化出来。
 *
 * <h3>懒创建</h3>
 * 工具窗只在用户首次交互时才由 {@link ReaderToolWindowFactory} 创建，
 * 因此本类构造时会<b>主动拉取</b>一次当前会话状态；否则「present 早于面板创建」会白屏。
 */
public class ReadingPanel extends JBPanel<ReadingPanel> implements DataProvider, Disposable {

    /** 无会话时的占位提示。 */
    public static final String EMPTY_HINT = "还没有开始阅读。\n\n"
            + "用 Tools → Novel Reader → 「打开：粘贴目录URL阅读」开始；\n"
            + "或在设置里把渲染方式切成「仅专用面板」后重新打开一本书。";

    /**
     * 工具栏按钮，按功能分组；组与组之间会插入一条分隔线。顺序即显示顺序。
     *
     * <p>分组不是装饰：「翻一章」和「跳一章」这类操作容易按错，
     * 一条竖线把它们隔开，比只靠图标区分可靠得多。
     *
     * <p>public 是为了让测试能断言「这里用到的每个动作都声明了图标」——
     * 少配一个图标，按钮就会退化成平台的 {@code AllIcons.Toolbar.Unknown} 占位图，
     * 与其它按钮长得一模一样（这正是修过的 bug）。
     *
     * <p>注意按钮文案与图标都在 {@code plugin.xml} 的 action 声明里，
     * 这里只引用 id —— 菜单与工具栏因此共享同一份定义，不会漂移。
     */
    public static final String[][] TOOLBAR_GROUPS = {
            // 换一章
            {"NovelReader.PrevChapter", "NovelReader.NextChapter"},
            // 打开某个列表
            {"NovelReader.ChapterList", "NovelReader.Bookmarks", "NovelReader.History"},
            // 显示调整
            {"NovelReader.FontSizeDown", "NovelReader.FontSizeUp",
                    "NovelReader.LineSpacingDown", "NovelReader.LineSpacingUp"},
            // 结束阅读
            {"NovelReader.StopReading"},
    };

    /** 正文内边距：面板是窄竖条，文字贴着边框读起来很累。 */
    private static final int TEXT_HORIZONTAL_PADDING = 16;
    private static final int TEXT_VERTICAL_PADDING = 10;

    /** 组件尚未布局、量不到滚动条宽度时的兜底值（IDEA 细滚动条的常见宽度）。 */
    private static final int FALLBACK_SCROLL_BAR_WIDTH = 12;

    /** 状态行最多显示多少字符；更长的部分收进 tooltip（错误详情常是多行的）。 */
    private static final int STATUS_MAX_CHARS = 140;

    private final Project project;
    private final JBLabel titleLabel = new JBLabel();
    private final JBLabel progressLabel = new JBLabel();
    private final JBLabel statusLabel = new JBLabel();
    private final JTextPane textPane = new JTextPane();
    private final JBScrollPane scroll;
    private final ActionToolbar toolbar;

    /** 垂直滚动条宽度；右侧内边距要把它扣掉，左右留白才看起来一样宽。 */
    private int scrollBarWidth = FALLBACK_SCROLL_BAR_WIDTH;

    /** 当前会话状态；null 表示还没开始读。 */
    private ReaderState state;

    public ReadingPanel(@NotNull Project project) {
        super(new BorderLayout(0, 6));
        this.project = project;

        // 行距靠替换文本视图实现（见 LineSpacingEditorKit），倍数在创建视图时向设置现取
        textPane.setEditorKit(new LineSpacingEditorKit(this::currentLineSpacing));
        textPane.setEditable(false);          // 只读，但仍可选中与 Ctrl+C

        scroll = new JBScrollPane(textPane);
        scroll.setBorder(null);
        // 滚动条常驻：面板里整章内容几乎总是超过一屏，而且常驻才能让"右侧留白补偿"稳定，
        // 否则内容一变短、滚动条消失，右边距就会突然变窄
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollBarWidth = resolveScrollBarWidth();
        applyTextPadding();

        toolbar = buildToolbar();
        buildUi();
        applyTheme();
        applyFontAndSpacing();
        installThemeRefresh();

        // 工具窗懒创建：面板可能在 present 之后才被建出来，这里主动拉一次避免白屏
        render(ReaderManager.getInstance().getState(project));
    }

    // ---------- 对外 ----------

    /**
     * 渲染一次。{@code state} 为 null / 空时显示占位提示。
     *
     * <p><b>顺序有讲究</b>：先 {@link #applyFontAndSpacing()} 再 {@code setText}。
     * 字号是组件属性，行距是"创建视图时读取"的 —— {@code setText} 会重建视图，
     * 所以把字体设在前、内容设在后，一次重建同时满足两者。
     * （早先版本只在构造器里应用过字体，结果改字号后界面毫无变化。）
     */
    public void render(@Nullable ReaderState state) {
        if (state == null || state.isEmpty()) {
            clear();
            return;
        }
        this.state = state;

        titleLabel.setText(buildTitleText(state));
        progressLabel.setText(buildProgressText(state));
        setStatus("", false);   // 新内容来了，旧提示（含上一章的加载提示）就该让位
        applyFontAndSpacing();
        textPane.setText(state.getFullText());
        textPane.setCaretPosition(0);
        // 组件尚未布局时 caret 的滚动会落空，等这一轮事件处理完再补一次，确保新章从头看起
        SwingUtilities.invokeLater(() -> textPane.setCaretPosition(0));
        // 让按钮的可用性立刻跟上（例如到末章时"下一章"变灰）
        toolbar.updateActionsImmediately();
    }

    /** 清空面板并显示占位提示（「关闭阅读」后也走这里）。 */
    public void clear() {
        this.state = null;
        titleLabel.setText(ReaderPresenter.DEFAULT_TITLE);
        progressLabel.setText("");
        setStatus("", false);
        applyFontAndSpacing();
        textPane.setText(EMPTY_HINT);
        textPane.setCaretPosition(0);
        toolbar.updateActionsImmediately();
    }

    /**
     * 在状态行显示一句话（{@code null} / 空串表示清掉）。
     *
     * <p>这是「点了按钮到底有没有反应」的唯一可见载体：加载中、加载失败、到头了
     * 都在这里显示。此前这些消息只进通知，而通知组是 {@code displayType=NONE}
     * 不弹窗，于是"点了下一章没变化"看上去就像按钮坏了。
     *
     * @param error true 时用红色，便于和普通进度区分
     */
    public void setStatus(@Nullable String message, boolean error) {
        String text = message == null ? "" : message;
        boolean show = !text.isEmpty();
        statusLabel.setText(show ? oneLine(text) : "");
        statusLabel.setToolTipText(show ? text : null);
        statusLabel.setForeground(error ? JBColor.RED : UIUtil.getContextHelpForeground());
        // 空状态行不该占高度：BorderLayout 会跳过不可见组件
        statusLabel.setVisible(show);
        revalidate();
    }

    // ---------- 构建 ----------

    private ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        ActionManager manager = ActionManager.getInstance();
        for (int g = 0; g < TOOLBAR_GROUPS.length; g++) {
            if (g > 0) {
                group.addSeparator();
            }
            for (String id : TOOLBAR_GROUPS[g]) {
                AnAction action = manager.getAction(id);
                if (action != null) {
                    group.add(action);
                }
            }
        }
        ActionToolbar bar = manager.createActionToolbar("NovelReaderPanel", group, true);

        // 右侧面板是窄竖条：按钮排不下时让它换行，而不是被折叠进「»」菜单
        // （折行只是多占一点高度，折叠会让按钮凭空消失）。
        // 注意 setLayoutPolicy(...) 在本平台是空实现，必须用 setLayoutStrategy。
        ToolbarLayoutStrategy wrap = ToolbarLayoutStrategy.WRAP_STRATEGY;
        if (wrap != null) {
            bar.setLayoutStrategy(wrap);
        }

        // 关键：把 target 设为本面板。本类实现 DataProvider 提供 PROJECT，
        // 现有动作的 update() 全靠 event.getProject() 判断可用性 ——
        // 少了这一步，工具栏里的按钮会全是灰的。
        bar.setTargetComponent(this);
        return bar;
    }

    private void buildUi() {
        titleLabel.setFont(UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD));
        progressLabel.setForeground(UIUtil.getContextHelpForeground());
        statusLabel.setVisible(false);

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(titleLabel, BorderLayout.WEST);
        header.add(progressLabel, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(statusLabel, BorderLayout.CENTER);
        top.add(toolbar.getComponent(), BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        // 首次可见（布局已完成）时重新量一次滚动条宽度：
        // 构造阶段组件还没布局，量到的是"首选宽度"而不是实际宽度
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                refreshScrollBarWidth();
            }
        });
    }

    /**
     * 设置正文内边距。
     *
     * <p>垂直滚动条占据的是<b>右侧</b>宽度：左右内边距若取同一个值，
     * 视觉上右边会比左边宽出一条滚动条（这正是「左右边距不一致」的成因）。
     * 所以右侧把滚动条宽度扣掉，"文字到面板边缘"的距离两边才相等。
     */
    private void applyTextPadding() {
        int right = Math.max(0, TEXT_HORIZONTAL_PADDING - scrollBarWidth);
        textPane.setBorder(BorderFactory.createEmptyBorder(
                TEXT_VERTICAL_PADDING, TEXT_HORIZONTAL_PADDING,
                TEXT_VERTICAL_PADDING, right));
    }

    private int resolveScrollBarWidth() {
        JScrollBar bar = scroll == null ? null : scroll.getVerticalScrollBar();
        if (bar == null) {
            return FALLBACK_SCROLL_BAR_WIDTH;
        }
        int width = bar.getWidth();
        if (width <= 0) {
            width = bar.getPreferredSize().width;
        }
        return width > 0 ? width : FALLBACK_SCROLL_BAR_WIDTH;
    }

    private void refreshScrollBarWidth() {
        int width = resolveScrollBarWidth();
        if (width != scrollBarWidth) {
            scrollBarWidth = width;
            applyTextPadding();
        }
    }

    private void applyTheme() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        textPane.setBackground(scheme.getDefaultBackground());
        textPane.setForeground(scheme.getDefaultForeground());
    }

    /**
     * 应用正文字号。
     *
     * <p>行距不在这里设置：它由 {@link LineSpacingEditorKit} 的视图在<b>创建</b>时向设置取值，
     * 而每次渲染都会 {@code setText} 重建视图，所以改完设置重新渲染一次即可生效。
     */
    private void applyFontAndSpacing() {
        NovelReaderSettings settings = NovelReaderSettings.getInstanceOrNull();
        int size = settings == null
                ? NovelReaderSettings.DEFAULT_FONT_SIZE : settings.getFontSize();
        textPane.setFont(UIUtil.getLabelFont().deriveFont((float) size));
        textPane.revalidate();
        textPane.repaint();
    }

    /** 主题切换时刷新配色与滚动条宽度；顺带重算字号（用户可能在设置里改过）。 */
    private void installThemeRefresh() {
        UIManager.addPropertyChangeListener(themeListener);
    }

    private final java.beans.PropertyChangeListener themeListener = event -> {
        if ("lookAndFeel".equals(event.getPropertyName())) {
            applyTheme();
            refreshScrollBarWidth();
            applyFontAndSpacing();
        }
    };

    @Override
    public void dispose() {
        UIManager.removePropertyChangeListener(themeListener);
    }

    /** 行距倍数；平台未就绪（单测）时退回默认值。 */
    private double currentLineSpacing() {
        NovelReaderSettings settings = NovelReaderSettings.getInstanceOrNull();
        return settings == null
                ? NovelReaderSettings.DEFAULT_LINE_SPACING : settings.getLineSpacing();
    }

    // ---------- 文案 ----------

    /** 标题：当前章标题；取不到时退回插件名。 */
    static String buildTitleText(ReaderState state) {
        String chapter = state.getChapterTitle();
        return chapter == null || chapter.isEmpty() ? ReaderPresenter.DEFAULT_TITLE : chapter;
    }

    /**
     * 进度：第几章 / 共几章。
     *
     * <p>不再显示「第几段」—— 面板整章展示、又没有高亮，段号在这里没有任何可对应的位置。
     * 段游标仍然按段推进（通知模式、历史、书签都用它），只是不显示。
     *
     * <p>public 是为了让测试直接断言文案（与 {@code ShowChapterListAction.labels} 同样处理）：
     * 文案错位的表现只是"显示得不对"，不会有任何报错。
     */
    public static String buildProgressText(ReaderState state) {
        if (!state.hasChapterList()) {
            return state.getTotal() + " 段";
        }
        return "第 " + (state.getChapterIndex() + 1) + " / " + state.getChapterCount() + " 章";
    }

    /** 把多行消息压成一行，超长截断（完整内容仍然可以从 tooltip 看）。 */
    public static String oneLine(String message) {
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= STATUS_MAX_CHARS
                ? flat : flat.substring(0, STATUS_MAX_CHARS) + "…";
    }

    // ---------- DataProvider ----------

    /**
     * 向工具栏动作提供 Project —— 现有动作的 {@code update()} 都靠它来判断可用性。
     *
     * <p>注意 {@link DataProvider#getData(String)} 收的是 <b>String key</b>（不是 DataKey），
     * 所以这里用 {@code CommonDataKeys.PROJECT.getName()} 比较。
     */
    @Override
    public @Nullable Object getData(@NotNull String dataId) {
        if (CommonDataKeys.PROJECT.getName().equals(dataId)) {
            return project;
        }
        return null;
    }

    // 供测试/调试观察当前状态
    ReaderState getState() {
        return state;
    }
}
