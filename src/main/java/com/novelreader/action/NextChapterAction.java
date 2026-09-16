package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.novelreader.reader.ReaderManager;
import org.jetbrains.annotations.NotNull;

/** 直接跳到下一章（落在该章第 1 段）。 */
public class NextChapterAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project != null) {
            ReaderManager.getInstance().nextChapter(project);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabled(
                project != null && ReaderManager.getInstance().canNextChapter(project));
    }
}
