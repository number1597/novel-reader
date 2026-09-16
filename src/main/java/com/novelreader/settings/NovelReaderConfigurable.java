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
 * <p>可配置规则文件路径、每段最大字数、请求超时与失败重试次数，
 * 并提供「打开规则文件」「重新加载规则」两个便利按钮。
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
                .addLabeledComponent("每段最大字数：", maxCharsSpinner)
                .addLabeledComponent("请求超时（毫秒）：", timeoutSpinner)
                .addLabeledComponent("失败重试次数：", retriesSpinner)
                .addLabeledComponent("渲染方式：", renderModeBox)
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
        return !rulesPath().equals(settings.getRulesPath())
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
        settings.setRulesPath(rulesPath());
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
            rulesPathField.setText(settings.getRulesPath());
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
        fontSizeSpinner = null;
        lineSpacingSpinner = null;
        cacheEnabledBox = null;
        clearCacheButton = null;
        cacheInfoLabel = null;
        statusLabel = null;
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
