package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.reader.ReaderManager;
import org.jetbrains.annotations.NotNull;

/** 阅读上一页（上一段）。 */
public class PrevSegmentAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) {
            ReaderManager.getInstance().prev(project);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        boolean enabled = project != null && ReaderManager.getInstance().hasState(project);
        event.getPresentation().setEnabled(enabled);
    }
}
