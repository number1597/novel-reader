package com.novelreader;

import com.novelreader.reader.LineSpacingEditorKit;
import org.junit.Test;

import javax.swing.JTextPane;
import javax.swing.text.View;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/**
 * 行距的实现方式：把每个文本视图的竖直 span 按倍数放大。
 *
 * <h3>为什么值得一测</h3>
 * Swing 没有行距开关，这里是绕道视图层做的：
 * {@code ParagraphView}（FlowView）的行高取自行内子视图的竖直尺寸，
 * 所以 {@link LineSpacingEditorKit} 用一个放大竖直 span 的 {@code LabelView}
 * 顶替默认文本视图。这个方案"理论上成立"，但**没有任何编译器或运行时报错能提示它失效** ——
 * 失效的表现只是"点了行距按钮，屏幕上什么都没变"，跟没实现一模一样。
 *
 * <p>所以这里直接量：同一个文本，行距因子从 1.0 调到 2.0，根视图的竖直偏好尺寸必须变大。
 * 本测试不启动 IntelliJ 平台，只用一个普通 {@link JTextPane}。
 *
 * <p><b>注意必须先让文本排版一次</b>：没被绘制过的视图尺寸是 0，量出来的高度也是 0，
 * 那样的断言（0 与 0 相等）会"通过"却什么都没验证。这里用离屏图片强制绘制一遍。
 */
public class LineSpacingEditorKitTest {

    private static final String TEXT =
            "第一行文字，用来占位的内容。\n第二行文字，同样用来占位。\n第三行文字，还是占位。";

    private static final int WIDTH = 240;
    private static final int HEIGHT = 600;

    @Test
    public void largerFactorMakesTheTextTaller() {
        AtomicReference<Double> factor = new AtomicReference<>(1.0);
        JTextPane pane = newTextPane(factor);
        pane.setText(TEXT);
        float tight = preferredHeight(pane);

        factor.set(2.0);
        // 行高是布局时算好并缓存的，必须**重建视图**才会重算 —— 阅读面板每次渲染都会
        // setText，正好完成重建；这里照同样的路径来，否则测的就不是真实生效路径。
        pane.setText(TEXT);
        float loose = preferredHeight(pane);

        assertTrue("行距因子调大后整体高度必须变大，否则按钮等于没接上（tight="
                        + tight + " loose=" + loose + "）",
                loose > tight);
    }

    @Test
    public void factorOfOneKeepsTheOriginalHeight() {
        AtomicReference<Double> factor = new AtomicReference<>(1.0);
        JTextPane withKit = newTextPane(factor);
        withKit.setText(TEXT);

        JTextPane plain = new JTextPane();
        plain.setSize(WIDTH, HEIGHT);
        plain.setText(TEXT);

        float baseline = preferredHeight(plain);
        assertTrue("前提：基准文本本身要有高度，否则这个断言毫无意义", baseline > 0);

        assertTrue("因子为 1.0 时不该改行高（以普通 JTextPane 为基准）",
                Math.abs(preferredHeight(withKit) - baseline) < 0.5f);
    }

    @Test
    public void factorBelowOneIsIgnoredRatherThanSquashingText() {
        AtomicReference<Double> factor = new AtomicReference<>(0.5);
        JTextPane squashed = newTextPane(factor);
        squashed.setText(TEXT);

        JTextPane plain = new JTextPane();
        plain.setSize(WIDTH, HEIGHT);
        plain.setText(TEXT);

        float baseline = preferredHeight(plain);
        assertTrue("前提：基准文本本身要有高度", baseline > 0);

        assertTrue("小于 1 的因子应按 1 处理，不能把文字压扁",
                Math.abs(preferredHeight(squashed) - baseline) < 0.5f);
    }

    private static JTextPane newTextPane(AtomicReference<Double> factor) {
        JTextPane pane = new JTextPane();
        pane.setEditorKit(new LineSpacingEditorKit(factor::get));
        pane.setSize(WIDTH, HEIGHT);
        return pane;
    }

    /**
     * 根视图的竖直偏好高度 —— 也就是这段文本排版后需要多高。
     *
     * <p>先把组件离屏绘制一遍：文本视图只在绘制/校验时排版，
     * 不绘制就量不到真实尺寸（得到 0）。
     */
    private static float preferredHeight(JTextPane pane) {
        BufferedImage scratch = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scratch.createGraphics();
        try {
            pane.paint(graphics);
        } finally {
            graphics.dispose();
        }
        View root = pane.getUI().getRootView(pane);
        return root.getPreferredSpan(View.Y_AXIS);
    }
}
