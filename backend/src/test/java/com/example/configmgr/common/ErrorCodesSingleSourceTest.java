package com.example.configmgr.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1：码的**声明**单一源。
 *
 * <p>
 * 判据不是"字面量完全不存在"（比对/状态字面量天然存在），而是"码的**定义处**只有
 * {@link ErrorCodes} 一处"。其它类的 {@code CODE_*} 必须是对它的别名。例外只剩一处：
 * {@code PendingToolCall} 里的 {@code FRONTEND_CANCELLED}/{@code PRECHECK_FAILED} ——
 * 它们是**待决条目的状态字面量**，与错误码同名不同义（同名是既有 wire 契约）。
 *
 * <p>
 * A10 R-10 收尾（R-09 批）：{@code ResilientChatService.EMPTY_REPLY_CODE} 已改为
 * {@link ErrorCodes#EMPTY_REPLY} 的别名，临时码级豁免删除；扫描同时覆盖
 * {@code Map.of/Map.entry/put("code", …)} 错误体字面量与派生码后缀，
 * 并先剥注释，避免注释里的示例造成误报（也不让真字面量藏在注释后面假绿）。
 */
class ErrorCodesSingleSourceTest {

    /** 与错误码同名、但语义是"待决条目状态"的例外（整文件豁免）。 */
    private static final Map<String, String> ALLOWED_DECLARATIONS = Map.of(
            "src/main/java/com/example/configmgr/ai/run/PendingToolCall.java",
            "待决条目状态字面量（与错误码同名不同义，wire 契约既有）");

    /** 码字面量的声明形态（其它类只允许别名）：值必须是已登记码，防"复制一份码值"。 */
    private static final Pattern CODE_DECLARATION = Pattern.compile(
            "static final String [A-Za-z0-9_]+\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"");

    /** 错误体最常见写法：{@code body.put("code", "…")}。 */
    private static final Pattern PUT_CODE_LITERAL = Pattern.compile(
            "put\\(\\s*\"code\"\\s*,\\s*\"([A-Z][A-Z0-9_]*)\"\\s*\\)");

    /** {@code Map.of("code", "…", "message", …)} / {@code Map.entry("code", "…")}。 */
    private static final Pattern MAP_ENTRY_CODE_LITERAL = Pattern.compile(
            "Map\\.entry\\(\\s*\"code\"\\s*,\\s*\"([A-Z][A-Z0-9_]*)\"");

    /** 派生码后缀：{@code BASE + "_AFTER_PARTIAL"} 之类的最终码字面量。 */
    private static final Pattern DERIVED_CODE_LITERAL = Pattern.compile(
            "\"([A-Z][A-Z0-9_]+_(?:AFTER_PARTIAL|AFTER_TOOL_SIDE_EFFECT))\"");

    /** 未登记的派生后缀（写错后缀不得静默上线）。 */
    private static final Pattern UNKNOWN_DERIVED_SUFFIX = Pattern.compile(
            "\\+\\s*\"(_AFTER_[A-Z0-9_]+)\"");

    /** 代码里允许出现的派生后缀（由 ErrorCodes 登记的正式码集合兜底）。 */
    private static final Set<String> DERIVED_SUFFIXES = Set.of(
            ResilientChatServiceTestAccess.AFTER_PARTIAL,
            ResilientChatServiceTestAccess.AFTER_TOOL_SIDE_EFFECT);

    @Test
    void codeLiteralsAreDeclaredOnlyInErrorCodes() throws IOException {
        Path root = Paths.get("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = file.toString().replace('\\', '/');
                if (relative.endsWith("common/ErrorCodes.java")) {
                    continue;
                }
                if (ALLOWED_DECLARATIONS.containsKey(relative)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                offenders.addAll(scan(relative, source));
            }
        }
        assertEquals(List.of(), offenders, "码字面量只允许在 ErrorCodes 声明（其它类请用别名）");
    }

    /**
     * 扫描器自证：注释里的示例不算，Map.of / put / 派生码不算漏。
     *
     * <p>没有这条，扫描规则被写窄（例如只认 {@code static final}）时会一路绿。
     */
    @Test
    void scannerIgnoresCommentsButCatchesEveryErrorBodyShape() {
        String source = """
                // put("code", "GHOST_COMMENT_LINE")
                /* Map.of("code", "GHOST_COMMENT_BLOCK", "message", "x") */
                static final String GHOST = "SESSION_BUSY";
                static final String GHOST_NEWLINE =
                        "RESOURCE_LOCKED";
                body.put("code", "GHOST_PUT");
                body.put(
                        "code",
                        "GHOST_PUT_NEWLINE");
                Map.of("code", "GHOST_MAP", "message", "x");
                Map.of("code", "SESSION_BUSY", "extra", "x");
                Map.ofEntries(Map.entry("code", "GHOST_ENTRY"));
                Map.ofEntries(
                        Map.entry(
                                "code",
                                "GHOST_ENTRY_NEWLINE"));
                String derived = "GHOST_BASE_AFTER_PARTIAL";
                String badSuffix = base + "_AFTER_WHATEVER";
                String badSuffixNewline = base +
                        "_AFTER_WHATEVER_NEWLINE";
                body.put("code", ErrorCodes.BAD_REQUEST); // 常量引用不是字面量
                """;

        List<String> offenders = scan("src/main/java/com/example/Ghost.java", source);

        assertTrue(offenders.stream().anyMatch(o -> o.endsWith("SESSION_BUSY")), "声明形态未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.endsWith("RESOURCE_LOCKED")), "换行声明形态未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.endsWith("GHOST_PUT")), "put 形态未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.endsWith("GHOST_PUT_NEWLINE")), "换行 put 形态未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.contains("GHOST_MAP")), "Map.of 形态未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.contains("SESSION_BUSY") && o.contains("Map.of")),
                "Map.of 无 message 的已知码未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.endsWith("GHOST_ENTRY")), "Map.entry 形态未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.endsWith("GHOST_ENTRY_NEWLINE")), "换行 Map.entry 未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.endsWith("GHOST_BASE_AFTER_PARTIAL")), "派生码字面量未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.contains("_AFTER_WHATEVER")), "未知派生后缀未覆盖");
        assertTrue(offenders.stream().anyMatch(o -> o.contains("_AFTER_WHATEVER_NEWLINE")), "换行派生拼接未覆盖");
        assertTrue(offenders.stream().noneMatch(o -> o.contains("GHOST_COMMENT_LINE")), "行注释示例不得误报");
        assertTrue(offenders.stream().noneMatch(o -> o.contains("GHOST_COMMENT_BLOCK")), "块注释示例不得误报");
        assertTrue(offenders.stream().noneMatch(o -> o.contains("BAD_REQUEST")), "常量引用不得误报");
    }

    @Test
    void allowlistEntriesStillExist() {
        for (String path : ALLOWED_DECLARATIONS.keySet()) {
            assertTrue(Files.exists(Paths.get(path)), "白名单条目应仍存在（防过期白名单）：" + path);
        }
    }

    @Test
    void registeredCodesCoverEveryWireFamily() {
        Set<String> codes = ErrorCodes.ALL;
        // 三组代表码：HTTP / 工具 / 数据面（少了任一组说明表被削）
        assertTrue(codes.contains(ErrorCodes.SESSION_BUSY));
        assertTrue(codes.contains(ErrorCodes.RESOURCE_LOCKED));
        assertTrue(codes.contains(ErrorCodes.PRECHECK_NOT_PASSED));
        assertTrue(codes.size() >= 80, "码表规模异常缩小：" + codes.size());
    }

    /**
     * 终局失败码可能是"基础码 + 派生后缀"（R-09 收尾）：每一种组合都必须是正式登记码，
     * 否则前端镜像与 messages 会缺键，用户又看到裸码。
     */
    @Test
    void everyDerivedFailureCodeIsRegistered() {
        Set<String> bases = Set.of(
                ErrorCodes.UPSTREAM_FIRST_BYTE_TIMEOUT,
                ErrorCodes.UPSTREAM_SILENT_TIMEOUT,
                ErrorCodes.TOTAL_BUDGET_EXCEEDED,
                ErrorCodes.UPSTREAM_STREAM_INCOMPLETE,
                ErrorCodes.UPSTREAM_CONNECTION_BROKEN,
                ErrorCodes.UPSTREAM_STREAM_ERROR,
                ErrorCodes.CONTEXT_OVERFLOW);
        for (String base : bases) {
            for (String suffix : DERIVED_SUFFIXES) {
                assertTrue(ErrorCodes.ALL.contains(base + suffix),
                        "派生失败码未登记：" + base + suffix);
            }
        }
    }

    /**
     * 真实源码扫描：{@code map.of} 只在同一个 map 字面量里同时出现 {@code "message"} 时才当错误体，
     * 避免把业务字段（如种子数据 {@code Map.of("code","CNY","name",…)}）误报成错误码。
     */
    private static List<String> scan(String relative, String source) {
        String code = stripComments(source);
        List<String> offenders = new ArrayList<>();
        collectMatches(relative, code, CODE_DECLARATION, offenders, true);
        collectMatches(relative, code, PUT_CODE_LITERAL, offenders, false);
        collectMatches(relative, code, MAP_ENTRY_CODE_LITERAL, offenders, false);
        collectMatches(relative, code, DERIVED_CODE_LITERAL, offenders, false);

        Matcher maps = Pattern.compile("Map\\.of\\(([^;]*?)\\)").matcher(code);
        while (maps.find()) {
            String args = maps.group(1);
            boolean errorBody = args.contains("\"message\"");
            Matcher pair = Pattern.compile("\"code\"\\s*,\\s*\"([A-Z][A-Z0-9_]*)\"").matcher(args);
            while (pair.find()) {
                String value = pair.group(1);
                boolean known = ErrorCodes.ALL.contains(value);
                // 有 "message" ⇒ 是错误体，任何未登记码都要报；
                // 无 "message"（Map.of 只带 code + 其它字段）⇒ 只报"已登记码的副本"，
                // 避免把种子数据的 Map.of("code","CNY") / Map.of("code","DEPT_MGR") 误报。
                if (errorBody ? !known : known) {
                    offenders.add(relative + " → " + value + (errorBody ? "（Map.of 错误体）" : "（Map.of 无 message）"));
                }
            }
        }

        Matcher suffixes = UNKNOWN_DERIVED_SUFFIX.matcher(code);
        while (suffixes.find()) {
            if (!DERIVED_SUFFIXES.contains(suffixes.group(1))) {
                offenders.add(relative + " → " + suffixes.group(1) + "（未登记派生后缀）");
            }
        }
        return offenders;
    }

    private static void collectMatches(String relative, String source, Pattern pattern, List<String> offenders,
            boolean declarationsOnly) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String value = matcher.group(1);
            boolean known = ErrorCodes.ALL.contains(value);
            // 声明形态只认"已登记码的副本"：其它大写蛇形值可能是操作名/配置键（PROVIDER_CREATE、
            // AI_SECRET_KEK…），全量扫描会把它们误报成码。错误体与派生码形态则要求必须是已登记码。
            if (declarationsOnly ? known : !known) {
                offenders.add(relative + " → " + value);
            }
        }
    }

    /** 剥离 {@code //} 与块注释（保留换行，避免行号/相邻匹配错位）。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                if (c == '\n') {
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
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 派生后缀的单一源在 {@code ResilientChatService}（值以下划线开头，不会被
     * {@link #CODE_DECLARATION} 当成码字面量）。测试经此小接口读取，避免复制字符串。
     */
    private static final class ResilientChatServiceTestAccess {

        static final String AFTER_PARTIAL = com.example.configmgr.ai.run.ResilientChatService.AFTER_PARTIAL;

        static final String AFTER_TOOL_SIDE_EFFECT =
                com.example.configmgr.ai.run.ResilientChatService.AFTER_TOOL_SIDE_EFFECT;
    }
}
