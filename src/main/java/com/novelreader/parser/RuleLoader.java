package com.novelreader.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.novelreader.model.NovelRule;
import com.novelreader.util.UrlUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规则文件的读写与匹配。不依赖 IntelliJ 平台 API，便于单元测试。
 *
 * <p>规则文件为 JSON：{@code {"version":1,"rules":[...]}}。为了便于手写，
 * 解析前会先做宽松化处理（见 {@link #sanitize(String)}）：允许对象/数组结尾的
 * <b>尾随逗号</b>，以及 <b>{@code //} 与 block comment</b> 形式的注释。
 */
public final class RuleLoader {

    private static final Gson GSON = new GsonBuilder()
            .setLenient()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private RuleLoader() {
    }

    /** 规则文件的内存模型。 */
    public static class RuleSet {
        public int version = 1;
        public List<NovelRule> rules = new ArrayList<>();

        public List<NovelRule> getRules() {
            return rules == null ? Collections.emptyList() : rules;
        }
    }

    /** 从指定路径读取规则；文件不存在时抛出 {@link IOException}。 */
    public static RuleSet load(Path path) throws IOException {
        if (path == null) {
            throw new IOException("规则文件路径为空");
        }
        if (!Files.exists(path)) {
            throw new IOException("规则文件不存在：" + path);
        }
        String raw;
        try {
            raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("无法读取规则文件：" + path, e);
        }
        return parse(raw, path.toString());
    }

    /** 解析规则 JSON 文本。 */
    public static RuleSet parse(String rawJson, String sourceName) throws IOException {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw new IOException("规则文件内容为空：" + sourceName);
        }
        try {
            RuleSet set = GSON.fromJson(sanitize(rawJson), RuleSet.class);
            if (set == null) {
                set = new RuleSet();
            }
            if (set.rules == null) {
                set.rules = new ArrayList<>();
            }
            return set;
        } catch (JsonSyntaxException e) {
            throw new IOException("规则文件 JSON 解析失败（" + sourceName + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 清理手写 JSON 中常见的、但标准 JSON 不允许的内容：
     * <ul>
     *   <li>对象/数组结尾前的<b>尾随逗号</b></li>
     *   <li>{@code //} 行注释与 {@code /* *}{@code /} 块注释</li>
     * </ul>
     * 处理时会跳过字符串字面量内部，因此 URL 里的 {@code //} 不会被误删。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean inString = false;
        boolean escaped = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '/') {
                    inLineComment = true;
                    i++;
                    continue;
                }
                if (next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
            }
            if (c == ',') {
                int j = i + 1;
                while (j < raw.length() && Character.isWhitespace(raw.charAt(j))) {
                    j++;
                }
                if (j < raw.length() && (raw.charAt(j) == '}' || raw.charAt(j) == ']')) {
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 便捷重载：路径不存在时返回空规则集而不是抛异常之外的东西。 */
    public static RuleSet loadQuietly(Path path) {
        try {
            return load(path);
        } catch (IOException e) {
            RuleSet empty = new RuleSet();
            empty.rules = new ArrayList<>();
            return empty;
        }
    }

    /**
     * 找出第一条命中的规则。
     *
     * @return 命中的规则；没有命中返回 {@code null}
     */
    public static NovelRule match(List<NovelRule> rules, String url) {
        if (rules == null || url == null) {
            return null;
        }
        for (NovelRule rule : rules) {
            if (rule == null || !rule.enabled) {
                continue;
            }
            if (UrlUtil.matches(rule, url)) {
                return rule;
            }
        }
        return null;
    }

    /** 把规则集序列化为 JSON 文本。 */
    public static String toJson(RuleSet set) {
        return GSON.toJson(set);
    }

    /** 备份文件后缀。保存规则时原文件会被复制成 {@code rules.json.bak}。 */
    public static final String BACKUP_SUFFIX = ".bak";

    /**
     * 把规则集写回文件：<b>先备份原文件，再原子替换</b>。
     *
     * <p>为什么必须备份：规则是用户手写的 JSON，图形化编辑保存时会<b>重写整个文件</b>，
     * 文件里的 {@code //} 注释与排版一定会丢。备份是让用户「后悔了还能捞回来」的最低成本手段。
     *
     * <p>写入沿用阅读历史那套做法：先写 {@code .tmp} 再尝试 {@code ATOMIC_MOVE}，
     * 不支持原子移动的文件系统降级为普通替换 —— 避免写到一半失败留下半个 JSON。
     *
     * @return 备份文件路径；原文件不存在（没东西可备份）时返回 {@code null}
     */
    public static Path write(RuleSet set, Path path) throws IOException {
        if (path == null) {
            throw new IOException("规则文件路径为空");
        }
        if (set == null) {
            throw new IOException("规则集为空");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path backup = backupExisting(path);

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, toJson(set).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // 某些文件系统不支持原子移动，退回普通替换
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return backup;
    }

    /** 把现有规则文件复制为 {@code <文件名>.bak}；原文件不存在时返回 null。 */
    public static Path backupExisting(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        Path backup = path.resolveSibling(path.getFileName() + BACKUP_SUFFIX);
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    /**
     * 深拷贝一条规则。
     *
     * <p>走 Gson 往返而不是手写逐字段复制：以后给 {@link NovelRule} 加字段时
     * <b>不需要记得同步改这里</b>，否则「复制规则」会静默丢字段。
     */
    public static NovelRule copyRule(NovelRule rule) {
        if (rule == null) {
            return null;
        }
        NovelRule copy = GSON.fromJson(GSON.toJson(rule), NovelRule.class);
        return copy == null ? new NovelRule() : copy;
    }

    /**
     * 多规则兜底时最多尝试几条候选规则。
     *
     * <p>站点改版后旧规则常常「能匹配上但解析不出章节」，于是按顺序再试下一条。
     * 但候选不能太多，否则一次打开就打出一串请求。
     */
    public static final int MAX_FALLBACK_CANDIDATES = 3;

    /**
     * 找出<b>所有</b>命中的规则（按规则文件里的原顺序，只取启用的）。
     *
     * <p>存在的意义是「站点改版自愈」：{@link #match} 只给第一条，
     * 而第一条规则可能因为站点改版已经解析不出东西了，这时需要拿后面的候选再试。
     *
     * @return 命中的规则列表；没有命中返回空列表（不是 null）
     */
    public static List<NovelRule> matchAll(List<NovelRule> rules, String url) {
        List<NovelRule> matched = new ArrayList<>();
        if (rules == null || url == null) {
            return matched;
        }
        for (NovelRule rule : rules) {
            if (rule == null || !rule.enabled) {
                continue;
            }
            if (UrlUtil.matches(rule, url)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /** 随插件打包的默认规则文件（classpath 资源名）。 */
    public static final String DEFAULT_RULES_RESOURCE = "/rules-default.json";

    /**
     * 若规则文件不存在，则把<b>随插件打包的默认规则</b>写入该路径（自动创建父目录）。
     *
     * <p>默认规则来自 {@code src/main/resources/rules-default.json}，即安装后自带一份
     * 开箱即用的规则，无需用户手工新建。
     */
    public static void writeTemplateIfAbsent(Path path) throws IOException {
        if (path == null || Files.exists(path)) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, defaultTemplateJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取随插件打包的默认规则 JSON 文本。
     *
     * <p>从 classpath 加载 {@value #DEFAULT_RULES_RESOURCE}。若资源缺失则抛出
     * {@link IOException} 而<b>不是</b>静默返回空规则——后者会让用户「装完却读不了书」
     * 且毫无提示，排查成本极高。资源缺失只可能是构建/打包出错。
     */
    public static String defaultTemplateJson() throws IOException {
        try (InputStream in = RuleLoader.class.getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            if (in == null) {
                throw new IOException("默认规则资源缺失：" + DEFAULT_RULES_RESOURCE
                        + "（插件包可能未正确打包）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 便于调用方使用的路径构造。 */
    public static Path of(String path) {
        return Paths.get(path);
    }
}
