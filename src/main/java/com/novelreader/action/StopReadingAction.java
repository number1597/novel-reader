package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.reader.ReaderManager;
import org.jetbrains.annotations.NotNull;

/** 结束当前阅读会话并清掉正文通知。 */
public class StopReadingAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) {
            ReaderManager.getInstance().stop(project);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(
                project != null && ReaderManager.getInstance().hasState(project));
    }
}
