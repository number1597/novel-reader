package com.novelreader.reader;

import com.intellij.openapi.project.Project;
import com.novelreader.model.ReaderState;

/**
 * 用 IDEA 通知呈现正文 —— 即本插件最初的行为，也是当前的默认行为。
 *
 * <p>只是把 {@link ReaderNotifier} 包一层适配到 {@link ReaderPresenter}，
 * 行为<b>一字未改</b>：正文仍放在通知<b>标题</b>栏、内容栏为空串、不挂任何操作按钮。
 * 这样 {@link ReaderManager} 可以完全不关心渲染目标，而通知这条路的能力与限制
 * （超长截断、不能滚动/复制）依旧由 {@link ReaderNotifier} 自己承担。
 *
 * <p>本类无状态，可以安全共享；{@link ReaderNotifier} 同理（平台调用都在方法内部）。
 */
public class NotificationPresenter implements ReaderPresenter {

    private final ReaderNotifier notifier;

    public NotificationPresenter() {
        this(new ReaderNotifier());
    }

    public NotificationPresenter(ReaderNotifier notifier) {
        this.notifier = notifier == null ? new ReaderNotifier() : notifier;
    }

    @Override
    public void present(Project project, ReaderState state) {
        notifier.show(project, state);
    }

    @Override
    public void close(Project project) {
        notifier.closeAll(project);
    }

    @Override
    public void info(Project project, String title, String message) {
        notifier.info(project, title, message);
    }

    @Override
    public void error(Project project, String title, String message) {
        notifier.error(project, title, message);
    }

    /**
     * 进度状态在通知模式下<b>刻意不展示</b>。
     *
     * <p>通知里没有"状态行"这种常驻位置，每次加载都发一条通知只会把 Event Log 刷满；
     * 而且「正在加载」是转瞬即逝的当前状态，不是"发生过的事"，留痕没有意义。
     */
    @Override
    public void status(Project project, String message) {
    }
}
