package com.novelreader.reader;

import com.intellij.openapi.project.Project;
import com.novelreader.model.ReaderState;
import com.novelreader.settings.NovelReaderSettings;
import com.novelreader.settings.RenderMode;

import java.util.function.Supplier;

/**
 * 按设置把渲染分发到「通知」「面板」或「两者」。
 *
 * <p>{@link ReaderManager} 只认识这一个 {@link ReaderPresenter}，因此<b>加面板时它一行都不用改</b>
 * —— 这正是 P2 抽出 presenter 抽象的目的。
 *
 * <h3>各方法的分发规则（有取舍，不是随手写的）</h3>
 * <ul>
 *   <li>{@link #present} —— 严格按模式：通知模式不碰面板，面板模式不发正文通知；</li>
 *   <li>{@link #info} / {@link #error} —— <b>通知与面板都发</b>。通知负责留痕（Event Log
 *       不弹窗、不打扰），面板负责让人<b>当场看见</b>；面板不存在时那边自然什么都不做；</li>
 *   <li>{@link #status} —— <b>只给面板</b>。「正在加载…」这类进度是转瞬即逝的当前状态，
 *       写进通知只会刷屏，也没有留痕价值；</li>
 *   <li>{@link #close} —— <b>两个目标都清</b>。用户可能刚从通知模式切到面板模式，
 *       之前遗留的正文通知仍然需要被清掉；两者都是幂等操作，多清一次无害。</li>
 * </ul>
 *
 * <h3>为什么用 Supplier 而不是直接读设置</h3>
 * 每次调用时读一次设置 → 用户改完渲染方式<b>立刻生效、无需重启</b>；
 * 而把取值做成可注入的 {@code Supplier}，测试就能在不启动平台的情况下验证分发逻辑
 * （平台服务 {@code NovelReaderSettings.getInstance()} 在单测里拿不到）。
 */
public class ConfiguredPresenter implements ReaderPresenter {

    private final ReaderPresenter notificationTarget;
    private final ReaderPresenter panelTarget;
    private final Supplier<RenderMode> modeSupplier;

    /** 生产用：目标就是通知与面板，模式实时读设置。 */
    public ConfiguredPresenter() {
        this(new NotificationPresenter(), new PanelPresenter(),
                () -> NovelReaderSettings.getInstance().getRenderMode());
    }

    /**
     * 注入版本。
     *
     * <p>public 是为了让测试注入「记录调用」的假实现与固定模式，
     * 从而在没有平台的环境里断言分发规则。
     */
    public ConfiguredPresenter(ReaderPresenter notificationTarget,
                               ReaderPresenter panelTarget,
                               Supplier<RenderMode> modeSupplier) {
        this.notificationTarget = notificationTarget;
        this.panelTarget = panelTarget;
        this.modeSupplier = modeSupplier;
    }

    @Override
    public void present(Project project, ReaderState state) {
        RenderMode mode = currentMode();
        if (mode.includesNotification()) {
            notificationTarget.present(project, state);
        }
        if (mode.includesPanel()) {
            panelTarget.present(project, state);
        }
    }

    @Override
    public void close(Project project) {
        // 两个目标都清：模式可能是刚切过来的，遗留的正文通知也得清掉（幂等）
        notificationTarget.close(project);
        panelTarget.close(project);
    }

    @Override
    public void info(Project project, String title, String message) {
        // 通知留一条记录（Event Log 不打扰），面板也显示在状态行上。
        // 早期版本只发通知，后果是：在面板里点「下一章」、加载失败却什么都没看到 ——
        // 通知组是 displayType=NONE，不弹窗，没人会想到去翻 Event Log。
        notificationTarget.info(project, title, message);
        panelTarget.info(project, title, message);
    }

    @Override
    public void error(Project project, String title, String message) {
        notificationTarget.error(project, title, message);
        panelTarget.error(project, title, message);
    }

    @Override
    public void status(Project project, String message) {
        // 进度状态只给面板：通知里没有常驻的状态行，写进去只是刷屏
        panelTarget.status(project, message);
    }

    /** 取当前模式；设置读不到或返回 null 时退回默认（绝不因为设置问题让渲染停摆）。 */
    private RenderMode currentMode() {
        if (modeSupplier == null) {
            return RenderMode.DEFAULT;
        }
        RenderMode mode = modeSupplier.get();
        return mode == null ? RenderMode.DEFAULT : mode;
    }
}
