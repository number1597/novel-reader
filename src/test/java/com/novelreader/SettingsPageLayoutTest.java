package com.novelreader;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/**
 * 设置页的控件**确实被挂进面板、顺序也符合预期**。
 *
 * <p>为什么值得单独立一个源码级测试：控件「创建了但忘了 add 进 FormBuilder」
 * 是一个**完全静默**的缺陷 —— 编译通过、测试全绿、类文件里那句文案也在，
 * 只有真正点开设置页才会发现它根本不显示。断言「文案在 class 里」是证明不了接线的，
 * 本次就踩到了：`modeHintLabel` 建好、也会随渲染方式更新文字，却没被加进面板。
 *
 * <p>同理，「正文相关项收在渲染方式一起」这种布局意图也只能靠源码级断言钉住 ——
 * 无头环境下跑不起真实的设置页。
 */
public class SettingsPageLayoutTest {

    private static final String CONFIGURABLE =
            "src/main/java/com/novelreader/settings/NovelReaderConfigurable.java";

    private static String source() throws Exception {
        return Files.readString(Paths.get(CONFIGURABLE));
    }

    @Test
    public void modeHintLabelIsActuallyWiredIntoThePanel() throws Exception {
        String source = source();

        assertTrue("提示标签建出来了吗", source.contains("modeHintLabel = new JBLabel()"));
        assertTrue("提示文案要跟着渲染方式变", source.contains("modeHintLabel.setText("));
        assertTrue("**必须真的加进 FormBuilder** —— 只创建不加，界面上就永远看不到它，"
                        + "而且编译、测试都不会报错",
                source.contains("addComponent(modeHintLabel)"));
    }

    @Test
    public void bodyRelatedRowsSitTogetherAfterTheRenderModeRow() throws Exception {
        String source = source();
        String form = formChain(source);

        int mode = form.indexOf("\"渲染方式：\"");
        int maxChars = form.indexOf("\"每段最大字数：\"");
        int fontSize = form.indexOf("\"正文字号：\"");
        int lineSpacing = form.indexOf("\"正文行距：\"");

        assertTrue("这几行都应该在同一个 FormBuilder 链里", mode >= 0 && maxChars >= 0
                && fontSize >= 0 && lineSpacing >= 0);
        assertTrue("「每段最大字数」应移到「渲染方式」之后（它只在通知模式下生效）",
                maxChars > mode);
        assertTrue("「正文字号」应跟在「渲染方式」之后", fontSize > mode);
        assertTrue("「正文行距」应跟在「渲染方式」之后", lineSpacing > mode);
    }

    @Test
    public void networkRowsStayAheadOfTheReadingRows() throws Exception {
        String form = formChain(source());

        // 网络项是「配一次就不动」的基础配置，正文相关项是常调的阅读偏好 ——
        // 当前排布刻意把前者放前面，避免每次调字号都要越过它们。
        assertTrue("「请求超时」应排在「渲染方式」之前",
                form.indexOf("\"请求超时（毫秒）：\"") < form.indexOf("\"渲染方式：\""));
        assertTrue("「每段最大字数」不该再留在网络项那一堆里",
                form.indexOf("\"每段最大字数：\"") > form.indexOf("\"失败重试次数：\""));
    }

    @Test
    public void theThreeRowsAreToggledByTheChosenMode() throws Exception {
        String source = source();

        assertTrue("「每段最大字数」应随通知模式启停",
                source.contains("setRowEnabled(maxCharsSpinner, mode.includesNotification())"));
        assertTrue("字号应随面板模式启停",
                source.contains("setRowEnabled(fontSizeSpinner, mode.includesPanel())"));
        assertTrue("行距应随面板模式启停",
                source.contains("setRowEnabled(lineSpacingSpinner, mode.includesPanel())"));
    }

    /** 截出 FormBuilder 那条链，避免别处的同名文案干扰顺序断言。 */
    private static String formChain(String source) {
        int start = source.indexOf("mainPanel = FormBuilder.createFormBuilder()");
        int end = source.indexOf(".getPanel();", start);
        assertTrue("找不到 FormBuilder 链，源码结构可能变了", start >= 0 && end > start);
        return source.substring(start, end);
    }
}
