package com.novelreader.reader;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.project.Project;
import com.novelreader.model.ReaderState;

/**
 * 通过 IDEA 通知（Notifications）输出正文。
 *
 * <p>通知组 id 为 {@code NovelReader}，需在 plugin.xml 中注册。
 *
 * <h3>为什么正文放在「标题」里</h3>
 * 需求是「通知里只显示小说内容」。IntelliJ 的通知把「标题」渲染为较大号的正文行，
 * 「内容」渲染为较小的次要说明文字。而 2024.1.7 的 {@code Notification} 构造函数
 * 对 title / content 只做 <b>null 校验</b>（{@code ifnonnull}）而<b>不校验空串</b>，
 * 因此空字符串是合法的（已实测：{@code new Notification("", "", INFORMATION)} 正常构造）。
 *
 * <p>据此这里采用最直白的做法：
 * <ul>
 *   <li>小说正文 → <b>标题</b>，并用 HTML 显式压小字号、调小行高；</li>
 *   <li><b>内容</b> → 空串，视觉上完全不存在。</li>
 * </ul>
 *
 * <p>通知上不再挂「上一页/下一页」按钮，翻页一律走
 * 菜单 {@code Tools → Novel Reader：下一页/上一页} 或对应快捷键。
 */
public class ReaderNotifier {

    /** 与 plugin.xml 中 {@code <notificationGroup id="NovelReader"/>} 保持一致。 */
    public static final String GROUP_ID = "NovelReader";

    /**
     * 状态提示类通知（info / error）使用的标题。
     *
     * <p>注意与正文通知区分：正文通知把小说内容放在标题栏，不用这个常量。
     * 取值统一来自 {@link ReaderPresenter#DEFAULT_TITLE}，避免各展示器文案漂移。
     */
    public static final String MESSAGE_TITLE = ReaderPresenter.DEFAULT_TITLE;

    /** 内容栏留空。经实测空串被 API 接受，无需零宽字符之类的占位技巧。 */
    public static final String EMPTY_CONTENT = "";

    /** 正文包裹用的字号与行高；通知标题默认偏大偏粗，这里压小以便长文阅读。 */
    private static final String TEXT_STYLE =
            "font-size:12px;font-weight:normal;line-height:1.5;";

    /**
     * 展示当前分段。
     *
     * <p>正文写入标题栏，内容栏为空，不附带任何操作按钮。
     */
    public void show(Project project, ReaderState state) {
        if (project == null || state == null || state.isEmpty()) {
            return;
        }
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(buildTitle(state), EMPTY_CONTENT, NotificationType.INFORMATION);
        notification.notify(project);
    }

    /** 普通提示。 */
    public void info(Project project, String title, String message) {
        notify(project, title, message, NotificationType.INFORMATION);
    }

    /** 错误提示。 */
    public void error(Project project, String title, String message) {
        notify(project, title, message, NotificationType.ERROR);
    }

    /**
     * 关闭本组此前发出的所有通知。
     *
     * <p>用于「关闭阅读」：正文通知默认 {@code displayType="NONE"}，
     * 会一直挂在右侧 Notifications 面板里，结束后应当一并清掉。
     *
     * <p>{@link NotificationGroup} 本身没有列举通知的能力，需要走
     * {@link NotificationsManager#getNotificationsOfType} 按类型取，再筛出本组的。
     */
    public void closeAll(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        NotificationsManager manager = NotificationsManager.getNotificationsManager();
        for (Notification notification : manager.getNotificationsOfType(Notification.class, project)) {
            if (GROUP_ID.equals(notification.getGroupId())) {
                notification.expire();
            }
        }
    }

    private void notify(Project project, String title, String message, NotificationType type) {
        if (project == null || project.isDisposed()) {
            return;
        }
        NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, message, type)
                .notify(project);
    }

    /**
     * 把当前分段正文渲染为通知标题。
     *
     * <p>换行转 {@code <br>}，整段包在带显式样式的 {@code <div>} 里；
     * 正文自身的尖括号与 & 全部转义，避免站点内容污染通知。
     *
     * <p>public 是为了让测试可以直接断言渲染结果（测试位于 {@code com.novelreader} 包下）。
     */
    public static String buildTitle(ReaderState state) {
        String segment = state.currentSegment();
        if (segment == null || segment.isEmpty()) {
            return EMPTY_CONTENT;
        }
        String escaped = escapeHtml(segment).replace("\n", "<br>");
        return "<div style='" + TEXT_STYLE + "'>" + escaped + "</div>";
    }

    /** HTML 转义，供正文输出与测试复用。 */
    public static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
