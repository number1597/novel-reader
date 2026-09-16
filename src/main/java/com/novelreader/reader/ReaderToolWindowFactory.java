package com.novelreader.reader;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

/**
 * 阅读面板的工具窗工厂。
 *
 * <p>由 {@code plugin.xml} 的 {@code <toolWindow ... factoryClass="...">} 声明注册，
 * 平台在用户首次与工具窗交互时调用 {@link #createToolWindowContent}（<b>懒创建</b>）。
 *
 * <p>面板自己做两件事来弥补懒创建带来的时序差：
 * <ul>
 *   <li>构造时主动从 {@link ReaderManager} 拉一次当前会话状态，避免白屏；</li>
 *   <li>之后由 {@link PanelPresenter} 在每次翻页时推送最新状态。</li>
 * </ul>
 */
public class ReaderToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentManager manager = toolWindow.getContentManager();
        // 防重复：平台在某些路径下（内容被移除后重新打开）可能再次调用本方法，
        // 重复 addContent 会出现两个一模一样的标签页
        for (Content existing : manager.getContents()) {
            if (existing.getComponent() instanceof ReadingPanel) {
                return;
            }
        }

        ReadingPanel panel = new ReadingPanel(project);
        Content content = ContentFactory.getInstance()
                .createContent(panel, ReaderPresenter.DEFAULT_TITLE, false);
        // 面板持有 UIManager 监听器，交给内容做生命周期管理，避免工具窗回收后监听器还在
        content.setDisposer(panel);
        manager.addContent(content);
    }
}
