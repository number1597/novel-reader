package com.novelreader;

import com.intellij.openapi.project.Project;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.ConfiguredPresenter;
import com.novelreader.reader.ReaderPresenter;
import com.novelreader.settings.RenderMode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;

/**
 * 渲染模式的分发规则。
 *
 * <p>为什么要单独测：分发错了在<b>默认（通知）模式下完全看不出来</b> ——
 * 例如「面板模式下也发通知」只有切到面板模式才会发现重复打扰，
 * 「close 只清面板」则会在切换模式后留下清不掉的通知。
 *
 * <p>不启动平台的做法：目标展示器换成「记录调用」的假实现，
 * 模式来源换成可注入的 {@link Supplier}（生产用的是实时读设置的 lambda）。
 * 这些方法本身不碰 UI，所以 Project / ReaderState 传 null 即可。
 */
public class ConfiguredPresenterTest {

    /** 只记录调用次数的假展示器。 */
    private static final class RecordingPresenter implements ReaderPresenter {
        private final String name;
        private final List<String> calls = new ArrayList<>();

        RecordingPresenter(String name) {
            this.name = name;
        }

        @Override
        public void present(Project project, ReaderState state) {
            calls.add("present");
        }

        @Override
        public void close(Project project) {
            calls.add("close");
        }

        @Override
        public void info(Project project, String title, String message) {
            calls.add("info");
        }

        @Override
        public void error(Project project, String title, String message) {
            calls.add("error");
        }

        @Override
        public void status(Project project, String message) {
            calls.add("status");
        }

        int count(String call) {
            return (int) calls.stream().filter(call::equals).count();
        }

        String describe() {
            return name + calls;
        }
    }

    private static ConfiguredPresenter presenterFor(RenderMode mode,
                                                   RecordingPresenter notification,
                                                   RecordingPresenter panel) {
        return new ConfiguredPresenter(notification, panel, () -> mode);
    }

    @Test
    public void notificationModeOnlySendsNotifications() {
        RecordingPresenter notification = new RecordingPresenter("通知");
        RecordingPresenter panel = new RecordingPresenter("面板");
        ConfiguredPresenter presenter = presenterFor(RenderMode.NOTIFICATION, notification, panel);

        presenter.present(null, null);

        assertEquals("通知模式必须发通知", 1, notification.count("present"));
        assertEquals("通知模式不该去动面板：" + panel.describe(), 0, panel.count("present"));
    }

    @Test
    public void panelModeOnlyDrivesThePanel() {
        RecordingPresenter notification = new RecordingPresenter("通知");
        RecordingPresenter panel = new RecordingPresenter("面板");
        ConfiguredPresenter presenter = presenterFor(RenderMode.PANEL, notification, panel);

        presenter.present(null, null);

        assertEquals("面板模式不该再发正文通知：" + notification.describe(), 0, notification.count("present"));
        assertEquals("面板模式必须更新面板", 1, panel.count("present"));
    }

    @Test
    public void bothModeDrivesBothTargets() {
        RecordingPresenter notification = new RecordingPresenter("通知");
        RecordingPresenter panel = new RecordingPresenter("面板");
        ConfiguredPresenter presenter = presenterFor(RenderMode.BOTH, notification, panel);

        presenter.present(null, null);

        assertEquals(1, notification.count("present"));
        assertEquals(1, panel.count("present"));
    }

    @Test
    public void infoAndErrorReachBothTargetsInEveryMode() {
        // 通知负责留痕（Event Log 不弹窗、不打扰），面板负责让人**当场看见** —— 两个都要有。
        // 早期版本只发通知，后果就是「在面板里点下一章、加载失败却什么都没看到」：
        // 通知组是 displayType=NONE，没人会想到去翻 Event Log。
        for (RenderMode mode : RenderMode.values()) {
            RecordingPresenter notification = new RecordingPresenter("通知");
            RecordingPresenter panel = new RecordingPresenter("面板");
            ConfiguredPresenter presenter = presenterFor(mode, notification, panel);

            presenter.info(null, "标题", "到头了");
            presenter.error(null, "标题", "失败了");

            assertEquals("模式 " + mode + " 下提示应留痕到通知", 1, notification.count("info"));
            assertEquals("模式 " + mode + " 下错误应留痕到通知", 1, notification.count("error"));
            assertEquals("模式 " + mode + " 下面板也要能看到提示：" + panel.describe(),
                    1, panel.count("info"));
            assertEquals(1, panel.count("error"));
        }
    }

    @Test
    public void closeClearsBothTargetsInEveryMode() {
        // 用户可能刚从通知模式切到面板模式，遗留的正文通知仍需被清掉；两者都是幂等操作
        for (RenderMode mode : RenderMode.values()) {
            RecordingPresenter notification = new RecordingPresenter("通知");
            RecordingPresenter panel = new RecordingPresenter("面板");
            ConfiguredPresenter presenter = presenterFor(mode, notification, panel);

            presenter.close(null);

            assertEquals("模式 " + mode + " 下通知也要清", 1, notification.count("close"));
            assertEquals("模式 " + mode + " 下面板也要清", 1, panel.count("close"));
        }
    }

    @Test
    public void modeIsReadOnEveryCallSoSettingChangesTakeEffectImmediately() {
        RecordingPresenter notification = new RecordingPresenter("通知");
        RecordingPresenter panel = new RecordingPresenter("面板");
        AtomicReference<RenderMode> mode = new AtomicReference<>(RenderMode.NOTIFICATION);
        ConfiguredPresenter presenter =
                new ConfiguredPresenter(notification, panel, mode::get);

        presenter.present(null, null);
        assertEquals("一开始是通知模式", 1, notification.count("present"));
        assertEquals(0, panel.count("present"));

        mode.set(RenderMode.PANEL);
        presenter.present(null, null);
        assertEquals("切到面板模式后不该再发通知", 1, notification.count("present"));
        assertEquals("切换应当立即生效，无需重启", 1, panel.count("present"));

        mode.set(RenderMode.BOTH);
        presenter.present(null, null);
        assertEquals(2, notification.count("present"));
        assertEquals(2, panel.count("present"));
    }

    @Test
    public void nullModeFromSupplierFallsBackToDefault() {
        RecordingPresenter notification = new RecordingPresenter("通知");
        RecordingPresenter panel = new RecordingPresenter("面板");
        ConfiguredPresenter presenter =
                new ConfiguredPresenter(notification, panel, () -> null);

        presenter.present(null, null);

        assertEquals("模式取不到时要退回默认（通知），而不是什么都不渲染",
                1, notification.count("present"));
        assertEquals(0, panel.count("present"));
    }

    @Test
    public void nullSupplierFallsBackToDefaultInsteadOfThrowing() {
        RecordingPresenter notification = new RecordingPresenter("通知");
        RecordingPresenter panel = new RecordingPresenter("面板");
        ConfiguredPresenter presenter = new ConfiguredPresenter(notification, panel, null);

        presenter.present(null, null);

        assertEquals(1, notification.count("present"));
        assertEquals(0, panel.count("present"));
    }

    // ---------- 进度状态的分发（只给面板） ----------

    @Test
    public void statusGoesToPanelOnly() {
        RecordingPresenter notification = new RecordingPresenter("通知");
        RecordingPresenter panel = new RecordingPresenter("面板");
        ConfiguredPresenter presenter = presenterFor(RenderMode.BOTH, notification, panel);

        presenter.status(null, "正在加载《第2章》…");

        assertEquals("「正在加载」不该写进通知去刷屏", 0, notification.count("status"));
        assertEquals("面板负责显示进度", 1, panel.count("status"));
    }
}
