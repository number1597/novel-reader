package com.novelreader;

import com.novelreader.action.ShowHistoryAction;
import com.novelreader.history.ReadingHistoryEntry;
import com.novelreader.history.ReadingHistoryStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 阅读历史对话框的「选择 → 动作」逻辑。
 *
 * <p>这些是那次「点删除图标却进入阅读」事故的直接回归点，因此重点钉死两条：
 * <ul>
 *   <li><b>没选中就绝不能打开任何书</b> —— {@link ShowHistoryAction#pickForOpen} 必须
 *       返回 null，不能退化成「默认打开第一本」；</li>
 *   <li><b>删除只删选中的</b> —— 多选删除时其余的书一本都不能少，
 *       且脏数据（null / 空 URL / 重复项）不能连累别人。</li>
 * </ul>
 *
 * <p>对话框本身是平台 UI，无法在无头测试里构造，所以把这两段判断抽成了
 * {@code ShowHistoryAction} 上的静态方法，由本测试覆盖。
 */
public class HistorySelectionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ReadingHistoryStore storeAt(Path file) throws Exception {
        Constructor<ReadingHistoryStore> ctor =
                ReadingHistoryStore.class.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(file);
    }

    private Path historyFile() {
        return folder.getRoot().toPath().resolve("novelReader/history.json");
    }

    private static ReadingHistoryEntry entry(String tocUrl, String title) {
        return new ReadingHistoryEntry(tocUrl, title);
    }

    // ---------- pickForOpen ----------

    @Test
    public void pickForOpenReturnsNullWhenNothingSelected() {
        assertNull("没选中就不该有可打开的书", ShowHistoryAction.pickForOpen(null));
        assertNull("空选择不该退化成一个默认项", ShowHistoryAction.pickForOpen(new ArrayList<>()));
    }

    @Test
    public void pickForOpenSkipsNullAndInvalidEntries() {
        List<ReadingHistoryEntry> selected = Arrays.asList(
                null,
                entry("", "没有URL的脏数据"),
                entry("   ", "只有空格的脏数据"),
                entry("https://real.com/1/", "真正的书"));

        ReadingHistoryEntry picked = ShowHistoryAction.pickForOpen(selected);

        assertNotNull("应跳过脏数据取到有效条目", picked);
        assertEquals("https://real.com/1/", picked.getTocUrl());
    }

    @Test
    public void pickForOpenReturnsNullWhenAllEntriesAreInvalid() {
        List<ReadingHistoryEntry> selected = Arrays.asList(null, entry("", "空URL"));

        assertNull("全是脏数据时也不能随便挑一个", ShowHistoryAction.pickForOpen(selected));
    }

    @Test
    public void pickForOpenTakesTheFirstOfMultipleSelected() {
        ReadingHistoryEntry first = entry("https://a.com/1/", "A书");
        ReadingHistoryEntry second = entry("https://b.com/1/", "B书");

        ReadingHistoryEntry picked = ShowHistoryAction.pickForOpen(Arrays.asList(first, second));

        assertSame("多选时应取列表里的第一个", first, picked);
    }

    // ---------- deleteSelected ----------

    @Test
    public void deleteSelectedRemovesOnlyTheSelectedBook() throws Exception {
        ReadingHistoryStore store = storeAt(historyFile());
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "a1", 10, "r", 300L);
        store.record("https://b.com/1/", "B书", 0, 0, "第1章", "b1", 10, "r", 200L);
        store.record("https://c.com/1/", "C书", 0, 0, "第1章", "c1", 10, "r", 100L);

        int removed = ShowHistoryAction.deleteSelected(store,
                List.of(entry("https://b.com/1/", "B书")));

        assertEquals("应只删掉 1 条", 1, removed);
        assertEquals("其余书一本都不能少", 2, store.size());
        assertNull("被选中的那本应已删除", store.getHistory().find("https://b.com/1/"));
        assertNotNull("未选中的 A 必须保留", store.getHistory().find("https://a.com/1/"));
        assertNotNull("未选中的 C 必须保留", store.getHistory().find("https://c.com/1/"));
    }

    @Test
    public void deleteSelectedRemovesMultipleSelectedBooks() throws Exception {
        ReadingHistoryStore store = storeAt(historyFile());
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "a1", 10, "r", 300L);
        store.record("https://b.com/1/", "B书", 0, 0, "第1章", "b1", 10, "r", 200L);
        store.record("https://c.com/1/", "C书", 0, 0, "第1章", "c1", 10, "r", 100L);

        int removed = ShowHistoryAction.deleteSelected(store, List.of(
                entry("https://a.com/1/", "A书"),
                entry("https://c.com/1/", "C书")));

        assertEquals(2, removed);
        assertEquals("只应剩下 B", 1, store.size());
        assertNotNull(store.getHistory().find("https://b.com/1/"));
    }

    @Test
    public void deleteSelectedIgnoresDirtyEntries() throws Exception {
        ReadingHistoryStore store = storeAt(historyFile());
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "a1", 10, "r", 200L);
        store.record("https://b.com/1/", "B书", 0, 0, "第1章", "b1", 10, "r", 100L);

        int removed = ShowHistoryAction.deleteSelected(store, Arrays.asList(
                null,
                entry("", "空URL"),
                entry("   ", "空格URL"),
                entry("https://a.com/1/", "A书"),
                entry("https://a.com/1/", "A书（重复选中）"),
                entry("https://not-exist.com/9/", "不存在的书")));

        assertEquals("只应删掉真实存在的 A，且重复项只算一次", 1, removed);
        assertEquals("B 必须保留", 1, store.size());
        assertNotNull(store.getHistory().find("https://b.com/1/"));
        assertNull("不存在的 URL 不应被当成删除成功", store.getHistory().find("https://not-exist.com/9/"));
    }

    @Test
    public void deleteSelectedHandlesNullInputsAndEmptyStore() throws Exception {
        ReadingHistoryStore store = storeAt(historyFile());

        assertEquals(0, ShowHistoryAction.deleteSelected(null, List.of(entry("https://a.com/1/", "A"))));
        assertEquals(0, ShowHistoryAction.deleteSelected(store, null));
        assertEquals(0, ShowHistoryAction.deleteSelected(store, new ArrayList<>()));
        assertTrue("store 就没人删的东西", store.getHistory().isEmpty());
    }

    @Test
    public void deletedBooksStayDeletedAfterRestart() throws Exception {
        Path file = historyFile();
        ReadingHistoryStore store = storeAt(file);
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "a1", 10, "r", 200L);
        store.record("https://b.com/1/", "B书", 0, 0, "第1章", "b1", 10, "r", 100L);

        ShowHistoryAction.deleteSelected(store, List.of(entry("https://a.com/1/", "A书")));

        // 模拟关掉 IDEA 再打开
        ReadingHistoryStore afterRestart = storeAt(file);
        assertEquals("删除必须落盘，重启后不能复活", 1, afterRestart.size());
        assertNull(afterRestart.getHistory().find("https://a.com/1/"));
        assertNotNull(afterRestart.getHistory().find("https://b.com/1/"));
    }

    @Test
    public void deletingEverySelectedBookLeavesStoreEmpty() throws Exception {
        ReadingHistoryStore store = storeAt(historyFile());
        store.record("https://a.com/1/", "A书", 0, 0, "第1章", "a1", 10, "r", 200L);

        int removed = ShowHistoryAction.deleteSelected(store,
                List.of(entry("https://a.com/1/", "A书")));

        assertEquals(1, removed);
        assertTrue("删空后 store 应为空（对话框据此自动关闭）", store.getHistory().isEmpty());
    }
}
