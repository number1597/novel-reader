package com.novelreader;

import com.novelreader.settings.NovelReaderSettings;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/**
 * 规则文件路径的归一化：设置页显示什么、XML 里存什么。
 *
 * <p>为什么需要这一步：设置页会把<b>当前生效的路径直接显示在输入框里</b> ——
 * 一个空输入框既让人看不出文件在哪，也看不出「留空」是什么意思。
 * 但如果把显示出来的默认路径原样存进 XML，就等于把路径钉死了：
 * 将来 IDEA 换配置目录（升级、换机器）时，这个绝对路径会指向一个不存在的位置，
 * 而用户完全不知道发生了什么。
 *
 * <p>所以规则是：<b>显示生效路径，存储仍为空串</b>（空串 = 跟随默认），
 * 两者靠本方法换算。这里只测纯函数 —— 传入默认位置，不依赖平台。
 */
public class RulesPathNormalizeTest {

    /** 假装这是当前的默认位置。 */
    private static final Path DEFAULT = Path.of("C:\\cfg\\novelReader\\rules.json");

    @Test
    public void nullBecomesEmptyInsteadOfStayingNull() {
        assertEquals("null 也要归一成空串，否则会写进 XML 造成 NPE 隐患",
                "", NovelReaderSettings.normalizeRulesPath(null, DEFAULT));
    }

    @Test
    public void blankIsTreatedAsFollowTheDefault() {
        assertEquals("", NovelReaderSettings.normalizeRulesPath("", DEFAULT));
        assertEquals("只有空格的也算留空", "", NovelReaderSettings.normalizeRulesPath("   ", DEFAULT));
    }

    @Test
    public void pathEqualToDefaultIsStoredAsEmptySoItKeepsFollowingTheDefault() {
        assertEquals("与默认位置等价时必须归一成空串，否则 IDEA 换配置目录就跟不上了",
                "", NovelReaderSettings.normalizeRulesPath(DEFAULT.toString(), DEFAULT));
    }

    @Test
    public void equivalenceIgnoresSeparatorsCaseAndDotSegments() {
        // 用户手改路径时这些写法都会出现，必须都认出来是同一个位置
        assertEquals("正斜杠与反斜杠等价", "",
                NovelReaderSettings.normalizeRulesPath("C:/cfg/novelReader/rules.json", DEFAULT));
        assertEquals("大小写不同等价", "",
                NovelReaderSettings.normalizeRulesPath("c:\\CFG\\novelreader\\RULES.JSON", DEFAULT));
        assertEquals("多余的 . 片段应被规范化掉", "",
                NovelReaderSettings.normalizeRulesPath("C:\\cfg\\novelReader\\.\\rules.json", DEFAULT));
        assertEquals("首尾空格应被忽略", "",
                NovelReaderSettings.normalizeRulesPath("  " + DEFAULT + "  ", DEFAULT));
    }

    @Test
    public void customPathIsKeptAsTyped() {
        assertEquals("自定义路径要原样保留（仅去掉首尾空格）",
                "D:\\我的规则\\novel.json",
                NovelReaderSettings.normalizeRulesPath("  D:\\我的规则\\novel.json  ", DEFAULT));
    }

    @Test
    public void customPathThatMerelyLooksSimilarIsNotTreatedAsDefault() {
        // 只差一个文件名 / 一级目录，都是另一份规则，绝不能归成「跟随默认」
        assertEquals("C:\\cfg\\novelReader\\other.json",
                NovelReaderSettings.normalizeRulesPath("C:\\cfg\\novelReader\\other.json", DEFAULT));
        assertEquals("C:\\cfg\\rules.json",
                NovelReaderSettings.normalizeRulesPath("C:\\cfg\\rules.json", DEFAULT));
    }

    @Test
    public void invalidPathSyntaxIsKeptInsteadOfBeingSwallowed() {
        // 路径里有非法字符（用户手打了个 "*"）：原样留着，等真正去用时再报错。
        // 若在这里悄悄变成空串，用户会以为自己的配置生效了，实际用的是默认规则 —— 更难查。
        String broken = "C:\\cfg\\novel*Reader\\rules.json";
        assertEquals("非法路径不该被当成「跟随默认」",
                broken, NovelReaderSettings.normalizeRulesPath(broken, DEFAULT));
    }

    @Test
    public void missingDefaultFileLeavesTheTextAlone() {
        assertEquals("拿不到默认位置时不做任何判断",
                "D:\\x\\rules.json",
                NovelReaderSettings.normalizeRulesPath("D:\\x\\rules.json", null));
    }
}
