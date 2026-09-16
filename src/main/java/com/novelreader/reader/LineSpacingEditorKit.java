package com.novelreader.reader;

import javax.swing.text.AbstractDocument;
import javax.swing.text.Element;
import javax.swing.text.LabelView;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import java.util.function.DoubleSupplier;

/**
 * 行距可调的 {@link StyledEditorKit}。
 *
 * <h3>为什么必须绕到视图层</h3>
 * Swing 没有现成的行距开关，两条「看起来像」的路都是死的（都反编译 JDK 21 确认过）：
 * <ul>
 *   <li>{@code StyleConstants.setLineSpacing(...)} 设置的值，{@code ParagraphView}
 *       只把它存进一个私有字段，<b>之后从不读取</b>（整个类里只有 {@code putfield}，没有 {@code getfield}）；</li>
 *   <li>{@code JTextArea} 用的 {@code PlainView} 行高完全由 {@code FontMetrics} 决定
 *       （{@code paint} / {@code modelToView} 里直接读 metrics），连可覆盖的钩子都没有。</li>
 * </ul>
 * 可行入口在视图层：{@code ParagraphView}（FlowView）的<b>一行高度取自行内子视图的竖直尺寸</b>，
 * 所以把每个文本叶子视图的竖直 span 按倍数放大，行距就变大。
 *
 * <h3>两个实测踩出来的前提（都曾让"实现"看起来完全没接线）</h3>
 * <ol>
 *   <li><b>判断元素类型必须用 {@code Element.getName()}</b>，<u>不能</u>用
 *       {@code element.getAttributes().getAttribute(StyleConstants.NameAttribute)}：
 *       后者在文本叶子上取不到值，条件静默不成立，所有叶子仍旧是默认的 {@code LabelView} ——
 *       界面上与「完全没实现」一模一样，没有任何报错。</li>
 *   <li><b>改了倍数必须重建视图才起作用</b>：行高是 {@code FlowView$Row} 在<b>布局</b>时
 *       算好并缓存的，单靠 {@code revalidate()} / {@code repaint()} 不会重算。
 *       调用方（{@code ReadingPanel}）每次渲染都会 {@code setText}，正好完成重建 ——
 *       将来若改动那条渲染路径，这一点要一并考虑。</li>
 * </ol>
 *
 * <p>倍数用 {@link DoubleSupplier} 现取而不是构造时定格，这样用户改了设置不必换掉 EditorKit。
 */
public class LineSpacingEditorKit extends StyledEditorKit {

    private final DoubleSupplier lineSpacing;

    /** 非文本元素（段落、图标等）继续走 StyledEditorKit 的默认实现。 */
    private final ViewFactory fallback;

    /** 每次创建视图都会问一次工厂，lambda 建一次复用即可。 */
    private final ViewFactory viewFactory;

    public LineSpacingEditorKit(DoubleSupplier lineSpacing) {
        this.lineSpacing = lineSpacing;
        this.fallback = super.getViewFactory();
        this.viewFactory = element -> {
            if (AbstractDocument.ContentElementName.equals(element.getName())) {
                return new SpacingLabelView(element, lineSpacing);
            }
            return fallback.create(element);
        };
    }

    @Override
    public ViewFactory getViewFactory() {
        return viewFactory;
    }

    /** 竖直方向按倍数放大的文本视图；水平方向完全不动。 */
    private static class SpacingLabelView extends LabelView {

        private final DoubleSupplier lineSpacing;

        SpacingLabelView(Element element, DoubleSupplier lineSpacing) {
            super(element);
            this.lineSpacing = lineSpacing;
        }

        @Override
        public float getPreferredSpan(int axis) {
            return scaled(super.getPreferredSpan(axis), axis);
        }

        @Override
        public float getMinimumSpan(int axis) {
            return scaled(super.getMinimumSpan(axis), axis);
        }

        @Override
        public float getMaximumSpan(int axis) {
            return scaled(super.getMaximumSpan(axis), axis);
        }

        /**
         * 只放大竖直方向。
         *
         * <p>水平方向的 span 参与断行计算，动了会把换行位置算错；
         * 竖直方向的「无限大」（{@code View.getMaximumSpan} 的默认返回值）也不能乘，
         * 否则直接变成 Infinity。
         */
        private float scaled(float span, int axis) {
            if (axis != View.Y_AXIS || span <= 0 || Float.isInfinite(span) || span > 10000f) {
                return span;
            }
            double factor = lineSpacing.getAsDouble();
            return factor <= 1.0 ? span : (float) (span * factor);
        }
    }
}
