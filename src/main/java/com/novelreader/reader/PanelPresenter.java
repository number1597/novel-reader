package com.novelreader.reader;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.novelreader.model.ReaderState;
import org.jetbrains.annotations.Nullable;

/**
 * 把正文渲染到专用阅读面板（{@link ReadingPanel}）。
 *
 * <h3>为什么要处理「面板还不存在」</h3>
 * 工具窗是<b>懒创建</b>的：平台只在用户首次与它交互时才调
 * {@link ReaderToolWindowFactory#createToolWindowContent}。而 {@link #present} 往往
 * 在「用户刚打开一本书」时就被调用 —— 此时面板组件根本还没建出来。
 * 因此这里做三件事：
 * <ol>
 *   <li>必要时 {@code window.show()} 把工具窗显示出来（这一步会触发懒创建）；</li>
 *   <li>把渲染推迟到 {@code invokeLater}（可能来自后台任务回调，必须切回 EDT）；</li>
 *   <li>面板自身在构造时还会主动拉一次状态（见 {@link ReadingPanel} 构造器），
 *       所以「推送早于创建」不会白屏 —— 这也是这里找不到面板时可以直接放弃、
 *       不必反复重试的原因。</li>
 * </ol>
 *
 * <h3>状态提示不能顺手把面板建出来</h3>
 * 面板模式下 {@link #info} / {@link #error} / {@link #status} 会把消息写进面板的状态行，
 * 免得「点了下一章却什么都没发生」这种故障彻底静默（通知组的
 * {@code displayType=NONE} 不弹窗，用户不会去翻 Event Log）。
 * 但取面板必须走 {@code getContentManagerIfCreated()}：换成 {@code getContentManager()}
 * 时平台会为了「找面板」把内容创建出来 —— 那等于在「仅通知」模式下
 * 因为一句提示莫名把面板弹开。
 */
public class PanelPresenter implements ReaderPresenter {

    /** 与 {@code plugin.xml} 里 {@code <toolWindow id=...>} 保持一致。 */
    public static final String TOOL_WINDOW_ID = "NovelReaderPanel";

    @Override
    public void present(Project project, ReaderState state) {
        if (project == null || project.isDisposed() || state == null) {
            return;
        }
        ToolWindow window = toolWindow(project);
        if (window == null) {
            return; // 工具窗没注册（例如 plug-in 未正确加载），静默忽略
        }
        if (!window.isVisible()) {
            window.show();
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            ReadingPanel panel = findPanel(window);
            if (panel != null) {
                panel.render(state);
            }
        });
    }

    @Override
    public void close(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        ToolWindow window = toolWindow(project);
        if (window == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            ReadingPanel panel = findPanel(window);
            if (panel != null) {
                panel.clear();
            }
        });
    }

    @Override
    public void info(Project project, String title, String message) {
        showStatus(project, message, false);
    }

    @Override
    public void error(Project project, String title, String message) {
        showStatus(project, message, true);
    }

    @Override
    public void status(Project project, String message) {
        showStatus(project, message, false);
    }

    /**
     * 把提示写到面板的状态行上。
     *
     * <p>只通过 {@link #findPanel} 取面板 —— <b>不创建内容、不 show 工具窗</b>：
     * 这些提示在「仅通知」模式下也会走到这里（见 {@link ConfiguredPresenter}），
     * 那时面板压根不该出现，没有面板就安静地什么都不做。
     */
    private static void showStatus(Project project, String message, boolean error) {
        if (project == null || project.isDisposed() || message == null) {
            return;
        }
        ToolWindow window = toolWindow(project);
        if (window == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            ReadingPanel panel = findPanel(window);
            if (panel != null) {
                panel.setStatus(message, error);
            }
        });
    }

    private static @Nullable ToolWindow toolWindow(Project project) {
        return ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
    }

    /**
     * 从工具窗内容里取回面板。
     *
     * <p>用 {@code getContentManagerIfCreated()} 而不是 {@code getContentManager()}：
     * 后者会为了「找面板」反而把内容<b>创建出来</b>。
     * 遍历内容列表而不是取 {@code getContent(0)}，这样将来加了别的内容标签也不会认错。
     */
    static @Nullable ReadingPanel findPanel(ToolWindow window) {
        ContentManager manager = window.getContentManagerIfCreated();
        if (manager == null) {
            return null;
        }
        for (Content content : manager.getContents()) {
            if (content.getComponent() instanceof ReadingPanel) {
                return (ReadingPanel) content.getComponent();
            }
        }
        return null;
    }
}
