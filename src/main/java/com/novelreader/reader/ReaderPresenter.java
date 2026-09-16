package com.novelreader.reader;

import com.intellij.openapi.project.Project;
import com.novelreader.model.ReaderState;

/**
 * 「把当前阅读内容呈现给用户」的抽象。
 *
 * <h3>为什么需要这层抽象</h3>
 * 原先 {@link ReaderManager} 直接持有 {@link ReaderNotifier}，渲染目标被写死成「通知」。
 * 而通知有硬伤：超长被截断、不能滚动、不能选中复制、翻页后旧通知要么堆积要么消失。
 * 因此把渲染抽成接口，让「通知」与「专用阅读面板」可以并存、按设置切换，
 * 而 {@link ReaderManager} 不必知道到底是谁在显示。
 *
 * <h3>调用契约</h3>
 * <ul>
 *   <li>{@link #present} 在<b>每次正文变化时</b>调用（翻页、跳章、恢复），
 *       由 {@code ReaderManager.showCurrent()} 统一触发；</li>
 *   <li>{@link #close} 只在「关闭阅读」时调用一次；</li>
 *   <li>{@link #info} / {@link #error} 是<b>与阅读位置无关</b>的状态提示，
 *       不改变正文展示。</li>
 * </ul>
 *
 * <p><b>实现必须是线程安全的</b>：{@code present} 既可能在 EDT（翻页）也可能在
 * 后台任务回调（章节加载完成）中被调用，实现方需要自己切回 EDT 再碰 Swing 组件。
 */
public interface ReaderPresenter {

    /**
     * 状态提示（{@link #info} / {@link #error}）默认使用的标题文案。
     *
     * <p>各展示器共用同一个常量，避免「通知里叫这个、面板里叫那个」的文案漂移。
     */
    String DEFAULT_TITLE = "Novel Reader";

    /** 展示当前阅读位置。 */
    void present(Project project, ReaderState state);

    /** 收起 / 清空本次会话的展示（结束阅读时调用）。 */
    void close(Project project);

    /** 普通提示（到头了、多页拼接等），与阅读位置无关。 */
    void info(Project project, String title, String message);

    /** 错误提示。 */
    void error(Project project, String title, String message);

    /**
     * 进度状态（例如「正在加载《第 12 章》…」），显示在阅读面板的状态行上。
     *
     * <p>与 {@link #info} 的区别是受众：这类消息<b>只对正盯着面板的人有意义</b>。
     * 加载提示会随每次翻页出现，若也往通知里写一条，Event Log 很快就被刷满；
     * 而且加载中/加载完是"当前状态"，不是"发生过的事"，不需要留痕。
     *
     * <p>因此通知模式的实现是空实现 —— 不显示比显示了更好。
     */
    void status(Project project, String message);
}
