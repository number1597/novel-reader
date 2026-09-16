package com.novelreader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.novelreader.cache.ChapterCache;
import com.novelreader.fetch.HtmlFetcher;
import com.novelreader.model.Chapter;
import com.novelreader.model.NovelRule;
import com.novelreader.model.ReaderState;
import com.novelreader.parser.ChapterReader;
import com.novelreader.parser.RuleLoader;
import com.novelreader.reader.ChapterLoader;
import com.novelreader.reader.ReaderManager;
import com.novelreader.reader.ReaderNotifier;
import com.novelreader.settings.NovelReaderSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 主入口：粘贴小说目录页 URL → 匹配规则 → 解析出<b>完整章节目录</b> → 选起始章节 → 开始阅读。
 *
 * <p>整个章节目录会保存在 {@link ReaderState} 中，因此后续可以：
 * <ul>
 *   <li>读完本章后自动加载下一章；</li>
 *   <li>通过「章节目录」动作随时跳到任意一章。</li>
 * </ul>
 */
public class PasteTocUrlAction extends AnAction {

    private final ReaderNotifier notifier = new ReaderNotifier();

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        String input = Messages.showInputDialog(project,
                "请输入小说【目录页】URL：",
                "Novel Reader",
                Messages.getQuestionIcon());
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        String tocUrl = input.trim();

        NovelReaderSettings settings = NovelReaderSettings.getInstance();
        Path rulesFile = settings.getRulesFile();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Novel Reader：解析目录", true) {
            private List<Chapter> chapters;
            private NovelRule rule;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    // 首次使用：把随插件打包的默认规则写到用户配置目录，
                    // 这样安装后开箱即用，无需手工新建规则文件。
                    if (!Files.exists(rulesFile)) {
                        RuleLoader.writeTemplateIfAbsent(rulesFile);
                    }

                    RuleLoader.RuleSet ruleSet = RuleLoader.load(rulesFile);
                    if (ruleSet.getRules().isEmpty()) {
                        error = "规则文件里还没有任何规则：\n" + rulesFile
                                + "\n\n可在设置页点「打开规则文件」新增规则后重试。";
                        return;
                    }

                    // 取全部命中规则而不只是第一条：站点改版后旧规则常常"匹配得上但解析不出"，
                    // 交给 loadTocWithFallback 依次尝试（最多 3 条）。
                    List<NovelRule> candidates = RuleLoader.matchAll(ruleSet.getRules(), tocUrl);
                    if (candidates.isEmpty()) {
                        error = "没有匹配 " + hostOf(tocUrl) + " 的规则。\n\n"
                                + "请在规则文件中新增一条 siteMatch.pattern = \"" + hostOf(tocUrl) + "\" 的规则。\n"
                                + "规则文件：" + rulesFile;
                        return;
                    }

                    HtmlFetcher fetcher = ChapterLoader.newFetcher(settings);
                    ChapterReader reader = new ChapterReader(fetcher);
                    ChapterReader.TocResult toc = reader.loadTocWithFallback(tocUrl, candidates);
                    rule = toc.getRule();
                    chapters = toc.getChapters();
                } catch (IOException e) {
                    error = e.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (error != null) {
                    notifier.error(project, "Novel Reader 解析失败", error);
                    return;
                }
                if (chapters == null || chapters.isEmpty()) {
                    notifier.error(project, "Novel Reader 解析失败", "目录页没有解析出章节。");
                    return;
                }
                // 顺手把目录快照写进离线缓存：没有它，断网时连「打开这本书」都过不去
                ChapterCache cache = settings.isCacheEnabled()
                        ? ChapterCache.getInstanceOrNull() : null;
                if (cache != null) {
                    cache.saveBookSnapshot(tocUrl, chapters.get(0).getTitle(),
                            rule == null ? "" : rule.getRuleName(), chapters);
                }
                askChapterThenRead(project, tocUrl, chapters, rule, settings);
            }
        });
    }

    /** 在 EDT 上询问起始章节序号，然后抓取正文并开始阅读。 */
    private void askChapterThenRead(Project project, String tocUrl, List<Chapter> chapters,
                                    NovelRule rule, NovelReaderSettings settings) {
        String prompt = "共解析到 " + chapters.size() + " 章。\n\n"
                + "第 1 章：" + chapters.get(0).getTitle() + "\n"
                + "第 " + chapters.size() + " 章：" + chapters.get(chapters.size() - 1).getTitle() + "\n\n"
                + "请输入起始章节序号（1 - " + chapters.size() + "），回车从第 1 章开始：";
        String input = Messages.showInputDialog(project, prompt, "Novel Reader", Messages.getQuestionIcon());
        if (input == null) {
            return;
        }
        int index = parseIndex(input, chapters.size());
        startReading(project, tocUrl, chapters, index, rule, settings);
    }

    /** 抓取起始章节正文，成功后带着完整目录启动会话。 */
    private void startReading(Project project, String tocUrl, List<Chapter> chapters, int index,
                              NovelRule rule, NovelReaderSettings settings) {
        Chapter chapter = chapters.get(index);

        ProgressManager.getInstance().run(new Task.Backgroundable(
                project, "Novel Reader：抓取《" + chapter.getTitle() + "》", true) {

            private ChapterLoader.Loaded loaded;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    loaded = ChapterLoader.load(chapter, rule, settings);
                } catch (IOException e) {
                    error = e.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (error != null) {
                    notifier.error(project, "Novel Reader 抓取失败", error);
                    return;
                }
                if (loaded == null || loaded.isEmpty()) {
                    notifier.error(project, "Novel Reader 解析失败",
                            "没有解析出正文，请检查规则中的 content.bodySelector 是否正确。");
                    return;
                }

                ReaderState state = new ReaderState(
                        chapters, index, loaded.getTitle(), loaded.getSegments(), rule, tocUrl);
                ReaderManager.getInstance().start(project, state);

                StringBuilder message = new StringBuilder()
                        .append("已载入目录：共 ").append(chapters.size())
                        .append(" 章，当前第 ").append(index + 1)
                        .append(" 章。本章读完后按「下一页」会自动加载下一章，"
                                + "也可用「章节目录」跳转任意章节。");
                if (loaded.getPages() > 1) {
                    message.append("\n本章由 ").append(loaded.getPages())
                            .append(" 页拼接而成，共 ").append(state.getTotal()).append(" 段。");
                }
                notifier.info(project, ReaderNotifier.MESSAGE_TITLE, message.toString());
            }
        });
    }

    static int parseIndex(String input, int total) {
        try {
            int value = Integer.parseInt(input.trim());
            if (value < 1) {
                return 0;
            }
            return Math.min(value, total) - 1;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String hostOf(String url) {
        return com.novelreader.util.UrlUtil.host(url);
    }
}
