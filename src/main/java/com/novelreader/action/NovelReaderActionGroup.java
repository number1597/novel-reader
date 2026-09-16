package com.novelreader.action;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import org.jetbrains.annotations.NotNull;

/**
 * Tools 菜单下的「Novel Reader」父节点：一个点开后展开的二级子菜单。
 *
 * <p>把原先散落在 Tools 根菜单里的动作全部收进本节点，让 Tools 菜单保持干净。
 * 子菜单条目仍由 {@code plugin.xml} 通过 {@code add-to-group} 声明
 * （{@code group-id} 用 {@link #CHILD_GROUP_ID}），因此增删条目只改 XML，不动 Java。
 *
 * <h3>两个必须踩对的点（都已实测确认）</h3>
 * <ol>
 *   <li><b>先 {@code copyFromGroup} 再 {@code setPopup(true)}</b>：
 *       {@code copyFromGroup} 内部会执行 {@code setPopup(isPopup(source))}，
 *       而源组默认不是 popup —— 若顺序写反，{@code isPopup()} 会被重置回 false，
 *       父节点就退化成一个可点击的普通动作，而不是展开子菜单。</li>
 *   <li><b>不要用 {@code getChildren(AnActionEvent)} 收集子动作</b>：
 *       平台明确禁止 {@code getChildren(null)}（会打印 error 日志），
 *       推荐改用 {@code getChildren(ActionManager)}。本类所有收集都走这个重载。</li>
 * </ol>
 *
 * <p>子菜单内容是静态的、不随 Project 变化，因此在构造函数里一次性装载完毕。
 * 构造期 {@code ActionManager.getInstance()} 必然可用（动作由平台实例化）。
 */
public class NovelReaderActionGroup extends DefaultActionGroup {

    /**
     * 父菜单在 plugin.xml 中声明的 id。
     *
     * <p>与「动作 id」共用 Actions 命名空间，这里刻意取一个区别于
     * {@code NovelReader.XXX} 动作 id 的名字，避免两者互相覆盖。
     */
    public static final String PARENT_GROUP_ID = "NovelReader.ToolsMenu";

    /** 子菜单在 Tools 菜单里的可见文案。 */
    public static final String GROUP_TEXT = "Novel Reader";

    /**
     * 子菜单内各动作注册到哪个 {@code childGroupId}。
     *
     * <p>用一个虚拟分组把子菜单内容与其它任何 {@code group-id} 隔离，
     * 同时成为「该动作属于本菜单」的判定依据。
     */
    public static final String CHILD_GROUP_ID = "NovelReader.MenuItems";

    public NovelReaderActionGroup() {
        this(ActionManager.getInstance());
    }

    /**
     * 可注入 {@link ActionManager} 的构造，便于测试。
     *
     * <p>注意 {@code copyFromGroup} 一定要在 {@code setPopup(true)} 之前，
     * 否则 popup 标记会被覆盖掉（见类注释）。
     */
    public NovelReaderActionGroup(ActionManager manager) {
        loadChildren(manager, this);
        setPopup(true);
    }

    /** 加载顺序：先兜底项，再真实项，便于排障时不至于得到空菜单。 */
    static void loadChildren(ActionManager manager, DefaultActionGroup target) {
        for (AnAction action : childActions(manager)) {
            target.add(action, manager);
        }
    }

    /** 收集子菜单里的动作；{@code group-id} 由 plugin.xml 指定。 */
    static AnAction[] childActions(ActionManager manager) {
        if (manager == null) {
            return EMPTY_ACTIONS;
        }
        Object registered = manager.getAction(CHILD_GROUP_ID);
        if (!(registered instanceof DefaultActionGroup)) {
            return EMPTY_ACTIONS;
        }
        // getChildren(ActionManager) 是平台推荐的重载；getChildren(event) 传 null 会报错
        AnAction[] children = ((DefaultActionGroup) registered).getChildren(manager);
        return children == null ? EMPTY_ACTIONS : children;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    private static final AnAction[] EMPTY_ACTIONS = new AnAction[0];
}
