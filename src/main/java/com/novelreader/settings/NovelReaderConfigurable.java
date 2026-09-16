package com.novelreader.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.novelreader.cache.ChapterCache;
import com.novelreader.parser.RuleLoader;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置页：Settings / Preferences → Tools → Novel Reader。
 *
 * <p>可配置规则文件路径、每段最大字数、请求超时与失败重试次数、渲染方式与面板排版，
 * 并提供「打开规则文件」「编辑规则…」「重新加载规则」三个便利按钮。
 *
 * <p><b>规则文件路径默认值直接显示在输入框里</b>：留空虽然等价于「用默认位置」，
 * 但一个空输入框既让人看不出文件在哪，也看不出「留空」是什么意思。
 * 保存时会把「与默认位置等价」的路径归一化回空串（见
 * {@link NovelReaderSettings#normalizeRulesPath(String, Path)}），
 * 这样 XML 里保留的仍是「跟随默认」语义。
 *
 * <p><b>正文相关的项跟着渲染方式走</b>：「每段最大字数」只对通知模式有意义
 * （面板整章展示，不切段），字号与行距只对面板有意义。不生效的那些会被置灰并给出说明 ——
 * 否则最容易的结果是「改了没反应，然后怀疑插件坏了」。
 *
 * <p><b>这里没有 User-Agent 设置</b>：UA 是站点级属性（有的站会拉黑浏览器 UA，
 * 有的站反之），只能在规则文件里按站配置（{@code NovelRule.userAgent}），
 * 留空则用内置浏览器 UA。放一个全局 UA 会造成「改一处、某站莫名读不了」
 * 而且从规则文件上看不出原因，故不提供。
 *
 * <p>规则文件默认落在 {@code {IDEA 配置目录}/novelReader/rules.json}，
 * 首次使用时会自动写入<b>随插件打包的默认规则</b>，安装即开箱可用。
 */
public class NovelReaderConfigurable implements Configurable {

    private TextFieldWithBrowseButton rulesPathField;
    private JSpinner maxCharsSpinner;
    private JSpinner timeoutSpinner;
    private JSpinner retriesSpinner;
    private JComboBox<RenderMode> renderModeBox;
    /** 随渲染方式变化的一句话说明，解释哪些项在当前模式下不生效。 */
    private JBLabel modeHintLabel;
    private JSpinner fontSizeSpinner;
    /** 行距是小数（1.0~3.0，步进 0.1），用能装小数的 Spinner 模型。 */
    private JSpinner lineSpacingSpinner;
    private JCheckBox cacheEnabledBox;
    private JButton clearCacheButton;
    private JBLabel cacheInfoLabel;
    private JBLabel statusLabel;
    private JPanel mainPanel;

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "Novel Reader";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (mainPanel != null) {
            return mainPanel;
        }
        rulesPathField = new TextFieldWithBrowseButton();
        rulesPathField.addBrowseFolderListener(new TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor("json")));

        maxCharsSpinner = new JSpinner(new SpinnerNumberModel(
                NovelReaderSettings.DEFAULT_MAX_CHARS, 20, 20000, 20));
        timeoutSpinner = new JSpinner(new SpinnerNumberModel(
                NovelReaderSettings.DEFAULT_TIMEOUT_MS, 1000, 120000, 1000));
        retriesSpinner = new JSpinner(new SpinnerNumberModel(
                NovelReaderSettings.DEFAULT_MAX_RETRIES, 0,
                NovelReaderSettings.MAX_ALLOWED_RETRIES, 1));
        // RenderMode 重写了 toString()，下拉框无需自定义渲染器即可显示中文名
        renderModeBox = new JComboBox<>(RenderMode.values());
        // 切换渲染方式时立刻反映到下方哪些项可用，不必点「应用」
        renderModeBox.addActionListener(e -> updateModeDependentRows());
        modeHintLabel = new JBLabel();
        modeHintLabel.setForeground(JBColor.GRAY);
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(
                NovelReaderSettings.DEFAULT_FONT_SIZE,
                NovelReaderSettings.MIN_FONT_SIZE,
                NovelReaderSettings.MAX_FONT_SIZE, 1));
        lineSpacingSpinner = new JSpinner(new SpinnerNumberModel(
                (double) NovelReaderSettings.DEFAULT_LINE_SPACING,
                (double) NovelReaderSettings.MIN_LINE_SPACING,
                (double) NovelReaderSettings.MAX_LINE_SPACING,
                (double) NovelReaderSettings.LINE_SPACING_STEP));

        JButton openFileButton = new JButton("打开规则文件");
        openFileButton.addActionListener(e -> openRulesFile());
        JButton editRulesButton = new JButton("编辑规则…");
        editRulesButton.addActionListener(e -> editRules());
        JButton reloadButton = new JButton("重新加载规则");
        reloadButton.addActionListener(e -> reloadRules());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(openFileButton);
        buttons.add(editRulesButton);
        buttons.add(reloadButton);

        cacheEnabledBox = new JCheckBox("已读章节不再重复请求，断网时也能继续读");
        clearCacheButton = new JButton("清空缓存");
        clearCacheButton.addActionListener(e -> clearCache());
        cacheInfoLabel = new JBLabel();
        JPanel cacheButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        cacheButtons.add(clearCacheButton);
        cacheButtons.add(cacheInfoLabel);

        statusLabel = new JBLabel();

        mainPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("规则文件（JSON）：", rulesPathField)
                .addComponent(buttons)
                .addLabeledComponent("请求超时（毫秒）：", timeoutSpinner)
                .addLabeledComponent("失败重试次数：", retriesSpinner)
                // 正文相关的三项收在「渲染方式」一起：它们是同一条选择的两个分支，
                // 分散在页面两端时，「哪些项在当前模式下生效」就看不出来了。
                .addLabeledComponent("渲染方式：", renderModeBox)
                .addComponent(modeHintLabel)
                .addLabeledComponent("每段最大字数：", maxCharsSpinner)
                .addLabeledComponent("正文字号：", fontSizeSpinner)
                .addLabeledComponent("正文行距：", lineSpacingSpinner)
                .addSeparator()
                .addLabeledComponent("离线缓存：", cacheEnabledBox)
                .addComponent(cacheButtons)
                .addComponent(statusLabel)
                .addComponentFillVertically(new JPanel(new BorderLayout()), 0)
                .getPanel();
        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        NovelReaderSettings settings = NovelReaderSettings.getInstance();
        // 用归一化后的值比较：输入框里预填的是「当前生效路径」，默认位置就是它，
        // 直接拿原文比较会让「什么都没改」也显示成已修改。
        return !normalizedRulesPath().equals(settings.getRulesPath())
                || intValue(maxCharsSpinner) != settings.getMaxCharsPerPage()
                || intValue(timeoutSpinner) != settings.getRequestTimeoutMs()
                || intValue(retriesSpinner) != settings.getMaxRetries()
                || selectedRenderMode() != settings.getRenderMode()
                || intValue(fontSizeSpinner) != settings.getFontSize()
                || Math.abs(floatValue(lineSpacingSpinner) - settings.getLineSpacing()) > 0.001f
                || cacheEnabled() != settings.isCacheEnabled();
    }

    @Override
    public void apply() {
        NovelReaderSettings settings = NovelReaderSettings.getInstance();
        settings.setRulesPath(normalizedRulesPath());
        // 不生效的那些项也照常保存：用户切到另一种渲染方式时，之前调好的值还在
        settings.setMaxCharsPerPage(intValue(maxCharsSpinner));
        settings.setRequestTimeoutMs(intValue(timeoutSpinner));
        settings.setMaxRetries(intValue(retriesSpinner));
        settings.setRenderMode(selectedRenderMode());
        settings.setFontSize(intValue(fontSizeSpinner));
        settings.setLineSpacing(floatValue(lineSpacingSpinner));
        settings.setCacheEnabled(cacheEnabled());

        Path file = settings.getRulesFile();
        if (!Files.exists(file)) {
            try {
                RuleLoader.writeTemplateIfAbsent(file);
                setStatus("已生成默认规则文件（随插件自带）：" + file, false);
            } catch (IOException e) {
                setStatus("无法创建规则文件：" + e.getMessage(), true);
            }
            return;
        }
        setStatus("设置已保存。", false);
    }

    @Override
    public void reset() {
        NovelReaderSettings settings = NovelReaderSettings.getInstance();
        if (rulesPathField != null) {
            // 显示「当前生效路径」而不是空的 state 值：默认位置也一并显示出来
            rulesPathField.setText(settings.getRulesFile().toString());
        }
        if (maxCharsSpinner != null) {
            maxCharsSpinner.setValue(settings.getMaxCharsPerPage());
        }
        if (timeoutSpinner != null) {
            timeoutSpinner.setValue(settings.getRequestTimeoutMs());
        }
        if (retriesSpinner != null) {
            retriesSpinner.setValue(settings.getMaxRetries());
        }
        if (renderModeBox != null) {
            renderModeBox.setSelectedItem(settings.getRenderMode());
        }
        if (fontSizeSpinner != null) {
            fontSizeSpinner.setValue(settings.getFontSize());
        }
        if (lineSpacingSpinner != null) {
            lineSpacingSpinner.setValue((double) settings.getLineSpacing());
        }
        if (cacheEnabledBox != null) {
            cacheEnabledBox.setSelected(settings.isCacheEnabled());
        }
        updateModeDependentRows();
        updateCacheInfo();
        setStatus("", false);
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        rulesPathField = null;
        maxCharsSpinner = null;
        timeoutSpinner = null;
        retriesSpinner = null;
        renderModeBox = null;
        modeHintLabel = null;
        fontSizeSpinner = null;
        lineSpacingSpinner = null;
        cacheEnabledBox = null;
        clearCacheButton = null;
        cacheInfoLabel = null;
        statusLabel = null;
    }

    /**
     * 让「只对当前渲染方式有意义」的项跟着渲染方式走。
     *
     * <p>「每段最大字数」只在通知模式生效（面板整章展示，不切段）；
     * 字号与行距只对面板生效。一直显示成可编辑最容易的后果是
     * 「改了没反应，然后怀疑插件坏了」，所以不生效的那些直接置灰，
     * 并用一句话说明为什么 —— 而不是留个空输入框让人猜。
     */
    private void updateModeDependentRows() {
        RenderMode mode = selectedRenderMode();
        setRowEnabled(maxCharsSpinner, mode.includesNotification());
        setRowEnabled(fontSizeSpinner, mode.includesPanel());
        setRowEnabled(lineSpacingSpinner, mode.includesPanel());

        if (modeHintLabel != null) {
            modeHintLabel.setText(mode.includesPanel()
                    ? "专用面板整章展示：字号与行距生效，「每段最大字数」不生效。"
                    : "通知逐段推送：只受「每段最大字数」影响，字号与行距不生效。");
        }
    }

    private static void setRowEnabled(JComponent component, boolean enabled) {
        if (component != null) {
            component.setEnabled(enabled);
        }
    }

    // ---------- 按钮行为 ----------

    private void openRulesFile() {
        Path path = currentRulesFile();
        try {
            if (!Files.exists(path)) {
                RuleLoader.writeTemplateIfAbsent(path);
            }
        } catch (IOException e) {
            setStatus("无法创建规则文件：" + e.getMessage(), true);
            return;
        }
        if (openInEditor(path)) {
            setStatus("已在编辑器中打开：" + path, false);
            return;
        }
        try {
            Desktop.getDesktop().open(path.toFile());
            setStatus("已用系统默认程序打开：" + path, false);
        } catch (Exception e) {
            setStatus("请手动打开该文件：" + path, false);
        }
    }

    /** 优先在 IDEA 编辑器中打开；没有可用项目时返回 false。 */
    private boolean openInEditor(Path path) {
        Project project = firstOpenProject();
        if (project == null) {
            return false;
        }
        VirtualFile virtualFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(path.toAbsolutePath().toString().replace('\\', '/'));
        if (virtualFile == null) {
            return false;
        }
        FileEditorManager.getInstance(project)
                .openTextEditor(new OpenFileDescriptor(project, virtualFile), true);
        return true;
    }

    private static Project firstOpenProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }

    /**
     * 打开规则编辑器。
     *
     * <p>规则文件不存在时先写出随插件自带的默认规则，保证编辑器里至少有东西可改。
     * 保存成功后在状态栏告知备份位置 —— 「保存会重写整个文件」这件事必须让用户看得见。
     */
    private void editRules() {
        Path path = currentRulesFile();
        try {
            if (!Files.exists(path)) {
                RuleLoader.writeTemplateIfAbsent(path);
            }
            RuleLoader.RuleSet ruleSet = RuleLoader.load(path);
            RuleEditorDialog dialog = new RuleEditorDialog(firstOpenProject(), path, ruleSet);
            if (!dialog.showAndGet()) {
                setStatus("已取消，规则文件未被修改。", false);
                return;
            }
            Path backup = dialog.getBackupPath();
            setStatus(backup == null
                    ? "规则已保存：" + path
                    : "规则已保存：" + path + "（原文件已备份为 " + backup.getFileName() + "）", false);
        } catch (IOException e) {
            setStatus("无法打开规则文件：" + e.getMessage(), true);
        }
    }

    private void reloadRules() {
        Path path = currentRulesFile();
        if (!Files.exists(path)) {
            setStatus("规则文件不存在：" + path, true);
            return;
        }
        try {
            RuleLoader.RuleSet ruleSet = RuleLoader.load(path);
            int total = ruleSet.getRules().size();
            long enabled = ruleSet.getRules().stream().filter(r -> r != null && r.enabled).count();
            setStatus("规则加载成功：共 " + total + " 条，其中启用 " + enabled + " 条。", false);
        } catch (IOException e) {
            setStatus("规则加载失败：" + e.getMessage(), true);
        }
    }

    private Path currentRulesFile() {
        String configured = rulesPath();
        if (!configured.isEmpty()) {
            return Path.of(configured);
        }
        return NovelReaderSettings.defaultRulesFile();
    }

    /**
     * 清空离线缓存。
     *
     * <p>先报出「要删多少」再二次确认：缓存可能攒了几百 MB，
     * 不该点一下就没了。清缓存<b>只删正文</b>，阅读历史与书签都在别的文件里，不受影响 ——
     * 这一点要写在确认框里，否则用户会担心进度也一起没了。
     */
    private void clearCache() {
        ChapterCache cache = ChapterCache.getInstanceOrNull();
        if (cache == null) {
            setStatus("缓存服务当前不可用，无需清理。", false);
            return;
        }
        int books = cache.bookCount();
        long size = cache.totalSizeBytes();
        if (books == 0) {
            setStatus("缓存已经是空的。", false);
            updateCacheInfo();
            return;
        }
        int answer = Messages.showYesNoDialog(
                "确定清空离线缓存吗？\n\n将删除 " + books + " 本书的缓存，释放 "
                        + ChapterCache.humanSize(size) + "。\n"
                        + "阅读历史与书签不受影响；已读过的章节下次阅读时会重新联网抓取。",
                "清空离线缓存", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            setStatus("已取消，缓存未改动。", false);
            return;
        }
        cache.clearAll();
        updateCacheInfo();
        setStatus("已清空缓存，释放 " + ChapterCache.humanSize(size) + "。", false);
    }

    /** 刷新缓存占用提示。 */
    private void updateCacheInfo() {
        if (cacheInfoLabel == null) {
            return;
        }
        ChapterCache cache = ChapterCache.getInstanceOrNull();
        if (cache == null) {
            cacheInfoLabel.setText("");
            return;
        }
        int books = cache.bookCount();
        cacheInfoLabel.setText(books == 0
                ? "（当前没有缓存）"
                : "（已缓存 " + books + " 本书，占用 "
                        + ChapterCache.humanSize(cache.totalSizeBytes()) + "）");
    }

    private void setStatus(String message, boolean isError) {
        if (statusLabel == null) {
            return;
        }
        statusLabel.setText(message);
        statusLabel.setForeground(isError ? JBColor.RED : JBColor.GRAY);
    }

    // ---------- 取值 ----------

    private String rulesPath() {
        return rulesPathField == null || rulesPathField.getText() == null
                ? "" : rulesPathField.getText().trim();
    }

    /**
     * 输入框内容归一化后的规则路径：与默认位置等价时返回空串。
     *
     * <p>{@code apply} 与 {@code isModified} 都必须走这里 ——
     * 输入框预填的是「生效路径」，默认位置就是它，
     * 直接拿原文比较会让「什么都没改」也显示成已修改。
     */
    private String normalizedRulesPath() {
        return NovelReaderSettings.normalizeRulesPath(
                rulesPath(), NovelReaderSettings.defaultRulesFile());
    }

    /** 下拉框当前选中的渲染方式；未选中时退回默认值。 */
    private RenderMode selectedRenderMode() {
        Object selected = renderModeBox == null ? null : renderModeBox.getSelectedItem();
        return selected instanceof RenderMode ? (RenderMode) selected : RenderMode.DEFAULT;
    }

    /** 勾选框当前状态；组件还没建出来时退回设置里的值（视为「未修改」）。 */
    private boolean cacheEnabled() {
        return cacheEnabledBox == null
                ? NovelReaderSettings.getInstance().isCacheEnabled()
                : cacheEnabledBox.isSelected();
    }

    private static int intValue(JSpinner spinner) {
        Object value = spinner == null ? null : spinner.getValue();
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static float floatValue(JSpinner spinner) {
        Object value = spinner == null ? null : spinner.getValue();
        return value instanceof Number ? ((Number) value).floatValue() : 0f;
    }
}
