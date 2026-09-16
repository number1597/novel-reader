package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.reader.ReaderManager;
import com.novelreader.settings.NovelReaderSettings;
import org.jetbrains.annotations.NotNull;

/**
 * 「调整阅读面板显示」这类动作的共同部分：改设置 → 立刻重绘面板。
 *
 * <p>四个动作（字号 ±、行距 ±）逻辑完全一样，只有改哪个字段、加减方向不同，
 * 所以把易错的部分（边界判断、刷新的时机、可用性）收在这里一处。
 *
 * <h3>为什么到边界时按钮变灰，而不是点了给个提示</h3>
 * 「已经是最大字号了」这种提示要占用状态行、还会盖掉真正有用的加载状态；
 * 直接变灰是平台里表达"到此为止"的通用做法，用户一看就懂。
 */
abstract class DisplayTweakAction extends AnAction {

    @Override
    public final void actionPerformed(@NotNull AnActionEvent event) {
        // 用 getInstanceOrNull：动作的可用性已由 update() 把关，这里再兜一层，
        // 避免任何情况下在无平台环境里抛 NPE
        NovelReaderSettings settings = NovelReaderSettings.getInstanceOrNull();
        if (settings == null || !canAdjust(settings)) {
            return;
        }
        adjust(settings);

        Project project = event.getProject();
        if (project != null) {
            // 只重绘：阅读位置没变，不该被当成一次翻页写进历史
            ReaderManager.getInstance().refresh(project);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        NovelReaderSettings settings = NovelReaderSettings.getInstanceOrNull();
        event.getPresentation().setEnabled(
                project != null
                        && settings != null
                        && ReaderManager.getInstance().hasState(project)
                        && canAdjust(settings));
    }

    /**
     * 还能继续调整吗？
     *
     * <p>没有会话时一律为 false：设置改是改得动的，但面板上看不到效果，
     * 那种"点了没反应"最让人困惑。
     */
    protected abstract boolean canAdjust(NovelReaderSettings settings);

    /** 执行一次调整。调用前已确认 {@link #canAdjust} 为 true。 */
    protected abstract void adjust(NovelReaderSettings settings);
}
