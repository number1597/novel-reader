package com.novelreader;

import com.intellij.openapi.project.Project;
import com.novelreader.model.Chapter;
import com.novelreader.model.ReaderState;
import com.novelreader.reader.ReaderManager;
import com.novelreader.reader.ReaderPresenter;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 渲染解耦：{@link ReaderManager} 应当把「呈现」全部交给 {@link ReaderPresenter}，
 * 自己不碰通知与 UI。
 *
 * <p>为什么值得单独测：渲染入口分散在十来处（翻页、跳章、到头提示、加载失败、多页拼接……），
 * 一旦有人新增一条路径忘了走 presenter（例如直接调通知），在通知模式下<b>看不出任何异常</b>，
 * 但换成阅读面板后那条路径就会静默不刷新。这里用「记录调用的假展示器」把契约钉死。
 *
 * <p>不启动平台的技巧：
 * <ul>
 *   <li>{@link Project} 是接口，用 {@code Proxy} 造一个只响应 {@code getLocationHash()} 的假项目；</li>
 *   <li>用<b>单章模式</b>（无章节目录）的状态，这样 {@code saveHistory} 会提前返回，
 *       不会去碰需要平台的 {@code ReadingHistoryStore}；</li>
 *   <li>需要「有目录但无规则」的场景时，把 {@code tocUrl} 留空 —— 同样能让写历史提前返回。</li>
 * </ul>
 */
public class ReaderManagerPresenterTest {

    private static Project fakeProject() {
        return (Project) Proxy.newProxyInstance(
                ReaderManagerPresenterTest.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    if ("getLocationHash".equals(method.getName())) {
                        return "test-project";
                    }
                    if ("getName".equals(method.getName())) {
                        return "test";
                    }
                    if ("toString".equals(method.getName())) {
                        return "fakeProject";
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    /** 只记录调用、不做任何 UI 动作的假展示器。 */
    private static final class RecordingPresenter implements ReaderPresenter {
        private final List<String> presentedTexts = new ArrayList<>();
        private final List<String> infos = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private int closeCount;

        @Override
        public void present(Project project, ReaderState state) {
            presentedTexts.add(state == null ? null : state.currentSegment());
        }

        @Override
        public void close(Project project) {
            closeCount++;
        }

        @Override
        public void info(Project project, String title, String message) {
            infos.add(message);
        }

        @Override
        public void error(Project project, String title, String message) {
            errors.add(message);
        }

        @Override
        public void status(Project project, String message) {
            statuses.add(message);
        }
    }

    /** 单章模式：没有章节目录，因此不会触发写历史（也就不会碰平台服务）。 */
    private static ReaderState singleChapter(String... segments) {
        return new ReaderState("第1章 标题", Arrays.asList(segments));
    }

    /** 有目录但目录 URL 为空：既能触发"缺规则"错误，又不会写历史。 */
    private static ReaderState chaptersWithoutTocUrl() {
        List<Chapter> chapters = Arrays.asList(
                new Chapter("第1章 A", "https://example.com/1.html"),
                new Chapter("第2章 B", "https://example.com/2.html"));
        return new ReaderState(chapters, 0, "第1章 A", Arrays.asList("正文一"), null, "");
    }

    @Test
    public void startPresentsOnce() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);

        manager.start(fakeProject(), singleChapter("第一段", "第二段"));

        assertEquals("开始阅读应渲染一次", 1, presenter.presentedTexts.size());
        assertEquals("第一段", presenter.presentedTexts.get(0));
    }

    @Test
    public void everyPageTurnPresentsExactlyOnce() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, singleChapter("第一段", "第二段", "第三段"));

        manager.next(project);
        manager.next(project);
        manager.prev(project);

        assertEquals("每次翻页都应当渲染，且只渲染一次", 4, presenter.presentedTexts.size());
        assertEquals("第一段", presenter.presentedTexts.get(0));
        assertEquals("第二段", presenter.presentedTexts.get(1));
        assertEquals("第三段", presenter.presentedTexts.get(2));
        assertEquals("第二段", presenter.presentedTexts.get(3));
    }

    @Test
    public void nothingIsPresentedWithoutASession() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();

        manager.next(project);
        manager.prev(project);
        manager.showCurrent(project);

        assertEquals("没有会话时不该有任何渲染", 0, presenter.presentedTexts.size());
        assertEquals("也不该冒出提示", 0, presenter.infos.size());
    }

    @Test
    public void reachingTheEndOnlyInformsInsteadOfRepainting() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, singleChapter("唯一一段"));

        manager.next(project);

        assertEquals("到头时不该重复渲染同一段", 1, presenter.presentedTexts.size());
        assertEquals("应当给一条提示", 1, presenter.infos.size());
        assertTrue("提示应说明到头了，实际：" + presenter.infos.get(0),
                presenter.infos.get(0).contains("最后一段"));

        manager.prev(project);
        assertEquals("往回到头时同样不重复渲染", 1, presenter.presentedTexts.size());
        assertEquals(2, presenter.infos.size());
    }

    @Test
    public void jumpingWithoutAChapterListInformsInsteadOfCrashing() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, singleChapter("唯一一段"));

        manager.nextChapter(project);

        assertEquals("单章模式按下一章应当给提示", 1, presenter.infos.size());
        assertTrue("提示应引导用户先粘贴目录 URL，实际：" + presenter.infos.get(0),
                presenter.infos.get(0).contains("目录"));
    }

    @Test
    public void missingRuleIsReportedThroughThePresenter() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, chaptersWithoutTocUrl());

        manager.jumpToChapter(project, 1);

        assertEquals("缺规则应当报错而不是静默失败", 1, presenter.errors.size());
        assertTrue("错误信息应说明缺规则，实际：" + presenter.errors.get(0),
                presenter.errors.get(0).contains("规则"));
        assertEquals("报错时不该有额外渲染", 1, presenter.presentedTexts.size());
    }

    @Test
    public void stopClosesThePresenterOnlyWhenThereWasASession() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, singleChapter("唯一一段"));

        manager.stop(project);
        assertEquals("结束阅读应通知展示器收起", 1, presenter.closeCount);

        manager.stop(project);
        assertEquals("本来就没有会话时不该重复通知收起", 1, presenter.closeCount);
    }

    @Test
    public void startIgnoresNullProjectOrState() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);

        manager.start(null, singleChapter("一段"));
        manager.start(fakeProject(), null);

        assertEquals("非法入参不该触发渲染", 0, presenter.presentedTexts.size());
        assertNull("没有会话时读取状态应为 null", manager.getState(fakeProject()));
    }

    @Test
    public void emptyStateIsNotPresented() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);

        manager.start(fakeProject(), new ReaderState("空章节", new ArrayList<>()));

        assertEquals("没有任何分段时不该渲染空内容", 0, presenter.presentedTexts.size());
    }

    /** 有目录、多段、但目录 URL 为空：跳转可用且不会写历史。 */
    private static ReaderState multiSegmentState() {
        List<Chapter> chapters = Arrays.asList(
                new Chapter("第1章 A", "https://example.com/1.html"),
                new Chapter("第2章 B", "https://example.com/2.html"));
        return new ReaderState(chapters, 0, "第1章 A",
                Arrays.asList("正文一", "正文二", "正文三"), null, "");
    }

    @Test
    public void jumpToPositionWithinSameChapterMovesCursorAndPresentsOnce() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, multiSegmentState());

        manager.jumpToPosition(project, 0, 2);

        assertEquals("同章跳转应渲染（开始 1 次 + 跳转 1 次）", 2, presenter.presentedTexts.size());
        assertEquals("应落到书签保存的那一段", "正文三", presenter.presentedTexts.get(1));
        assertEquals("段游标应停在目标段", 2, manager.getState(project).getIndex());
    }

    @Test
    public void jumpToPositionClampsSegmentBeyondChapterLength() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, multiSegmentState());

        // 站点正文变短了，存的段号未必还在
        manager.jumpToPosition(project, 0, 99);

        assertEquals("越界段号应夹到最后一段，而不是报错或跳回第 1 段",
                "正文三", presenter.presentedTexts.get(1));
    }

    @Test
    public void jumpToPositionIgnoresUnknownChapter() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, multiSegmentState());

        manager.jumpToPosition(project, 99, 0);

        assertEquals("目录里没有这一章时不该有任何渲染", 1, presenter.presentedTexts.size());
        assertEquals("也不该报错（调用方已按当前目录还原过下标）", 0, presenter.errors.size());
    }

    @Test
    public void jumpToPositionWithoutSessionDoesNothing() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);

        manager.jumpToPosition(fakeProject(), 0, 1);

        assertEquals("没有会话时不该渲染", 0, presenter.presentedTexts.size());
        assertEquals("也不该报错", 0, presenter.errors.size());
    }

    @Test
    public void jumpToPositionOnSingleChapterModeDoesNothing() {
        RecordingPresenter presenter = new RecordingPresenter();
        ReaderManager manager = new ReaderManager(presenter);
        Project project = fakeProject();
        manager.start(project, singleChapter("第一段", "第二段"));

        manager.jumpToPosition(project, 0, 1);

        assertEquals("单章模式没有书签概念，跳转应为空操作", 1, presenter.presentedTexts.size());
    }

    @Test
    public void nullPresenterFallsBackWithoutThrowingOnConstruction() {
        // 构造器对 null 做了兜底（退回通知展示器）。这里只验证构造与查询不会抛异常：
        // 真去渲染会碰平台 API，而默认展示器是通知，单测里没有平台可依赖。
        ReaderManager manager = new ReaderManager(null);

        assertNull("兜底构造后没有任何会话", manager.getState(fakeProject()));
        assertFalse("兜底构造后不应有会话", manager.hasState(fakeProject()));
    }
}
