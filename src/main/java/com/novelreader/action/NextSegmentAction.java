package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.reader.ReaderManager;
import org.jetbrains.annotations.NotNull;

/** 阅读下一页（下一段）。 */
public class NextSegmentAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) {
            ReaderManager.getInstance().next(project);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        boolean enabled = project != null && ReaderManager.getInstance().hasState(project);
        event.getPresentation().setEnabled(enabled);
    }
}
