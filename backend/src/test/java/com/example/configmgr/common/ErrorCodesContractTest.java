package com.example.configmgr.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-1 / M4-1：三方码表对账（后端 {@link ErrorCodes} ↔ 前端 {@code ErrorCode} ↔ {@code messages_*}）。
 *
 * <p>
 * 证红证据（施工前）：前端 13 条里 {@code RATE_LIMITED}/{@code AI_CONTEXT_UNAVAILABLE}/
 * {@code TASK_FINALIZED} 三条后端已发、前端 0 命中；8 个 {@code DEFINITION_*}/{@code JOB_*}
 * 冲突码前端全缺；后端无任何 {@code messages_*} 文件 ⇒ 三向差集都不为空。
 */
class ErrorCodesContractTest {

    /** 前端独有的网络层码（后端不发，白名单）。 */
    private static final Set<String> FRONTEND_ONLY = Set.of("NETWORK_ERROR", "UNKNOWN_ERROR");

    @Test
    void frontendMirrorMatchesBackendCodes() throws IOException {
        Set<String> frontend = parseFrontendErrorCodes();
        assertTrue(frontend.size() > 50, "前端镜像应已扩展为全量码表，实际 " + frontend.size());

        Set<String> backend = new TreeSet<>(ErrorCodes.ALL);
        Set<String> frontendBackend = new TreeSet<>(frontend);
        frontendBackend.removeAll(FRONTEND_ONLY);

        Set<String> missingInFrontend = new TreeSet<>(backend);
        missingInFrontend.removeAll(frontendBackend);
        Set<String> extraInFrontend = new TreeSet<>(frontendBackend);
        extraInFrontend.removeAll(backend);

        assertEquals(Set.of(), missingInFrontend, "后端有、前端镜像缺的码");
        assertEquals(Set.of(), extraInFrontend, "前端镜像有、后端未登记的码");
    }

    @Test
    void messagesCoverEveryCodeInBothLocales() throws IOException {
        Set<String> zh = parseMessageKeys("messages_zh.properties");
        Set<String> en = parseMessageKeys("messages_en.properties");
        assertEquals(Set.of(), difference(ErrorCodes.ALL, zh), "messages_zh 缺键");
        assertEquals(Set.of(), difference(zh, ErrorCodes.ALL), "messages_zh 多出未登记的键");
        assertEquals(Set.of(), difference(ErrorCodes.ALL, en), "messages_en 缺键");
        assertEquals(Set.of(), difference(en, ErrorCodes.ALL), "messages_en 多出未登记的键");
    }

    /**
     * A10 R-10：真实发给前端但长期未登记的 9 个码（SSE error 帧与 /chat 拒绝出口）。
     *
     * <p>清单取自 A10 报告 §2.1 R-10；本断言只锁"已登记"，文案与前端镜像分别由下方两条用例锁。
     */
    @Test
    void a10WireCodesAreRegistered() {
        Set<String> expected = Set.of(
                "REASON_TOO_LONG",
                "EMPTY_REPLY",
                "AI_RUN_CANCELLED",
                "UPSTREAM_FIRST_BYTE_TIMEOUT",
                "UPSTREAM_SILENT_TIMEOUT",
                "TOTAL_BUDGET_EXCEEDED",
                "UPSTREAM_STREAM_INCOMPLETE",
                "UPSTREAM_CONNECTION_BROKEN",
                "CONTEXT_OVERFLOW");
        assertEquals(Set.of(), difference(expected, ErrorCodes.ALL), "A10 R-10 的 9 个码未全部登记");
    }

    /**
     * A10 R-10（后半）：响应体里写死的 {@code put("code", "…")} 字面量必须登记进 {@link ErrorCodes}。
     *
     * <p>为什么只扫这一种形态：它是"码绕过常量直接上线"的实际写法（{@code REASON_TOO_LONG} 即如此），
     * 而 {@code Map.of("code", "CNY")} 之类是业务字段、不是错误码，扫进去只会制造误报。
     */
    @Test
    void literalCodesWrittenToBodiesAreRegistered() throws IOException {
        Pattern literal = Pattern.compile("put\\(\\s*\"code\"\\s*,\\s*\"([A-Z][A-Z0-9_]*)\"\\s*\\)");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = literal.matcher(source);
                while (matcher.find()) {
                    if (!ErrorCodes.ALL.contains(matcher.group(1))) {
                        offenders.add(file.toString().replace('\\', '/') + " → " + matcher.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "响应体里写死的 code 必须先在 ErrorCodes 登记");
    }

    /**
     * I-2 / I-3 强化：可用性三档（+ Redis 503）必须同时存在于后端、前端镜像与双 locale。
     *
     * <p>{@link #frontendMirrorMatchesBackendCodes()} 已做全量差集；这里单独锁"会用错码/漏映射"
     * 的四个可用性出口，避免以后新增一档只改后端。
     */
    @Test
    void availabilityTiersAreMirroredInFrontendAndMessages() throws IOException {
        Set<String> tiers = Set.of(ErrorCodes.AI_UNAVAILABLE, ErrorCodes.AI_KEY_MISSING,
                ErrorCodes.AI_KEY_UNREADABLE, ErrorCodes.AI_REDIS_UNAVAILABLE);
        Set<String> frontend = parseFrontendErrorCodes();
        Set<String> zh = parseMessageKeys("messages_zh.properties");
        Set<String> en = parseMessageKeys("messages_en.properties");
        assertEquals(Set.of(), difference(tiers, frontend), "可用性码前端镜像缺");
        assertEquals(Set.of(), difference(tiers, zh), "可用性码 messages_zh 缺键");
        assertEquals(Set.of(), difference(tiers, en), "可用性码 messages_en 缺键");
    }

    @Test
    void codeValuesAreUpperSnakeAndUnique() {
        Set<String> seen = new LinkedHashSet<>();
        for (String code : ErrorCodes.ALL) {
            assertTrue(code.matches("[A-Z][A-Z0-9_]*"), "码值必须是大写蛇形：" + code);
            assertTrue(seen.add(code), "码值重复：" + code);
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> out = new TreeSet<>(left);
        out.removeAll(right);
        return out;
    }

    private static Set<String> parseFrontendErrorCodes() throws IOException {
        String source = Files.readString(repoFile("frontend/src/types/api.ts"), StandardCharsets.UTF_8);
        int start = source.indexOf("export const ErrorCode = {");
        assertTrue(start >= 0, "前端 ErrorCode 声明缺失");
        int end = source.indexOf("} as const", start);
        String block = source.substring(start, end);
        Matcher matcher = Pattern.compile("([A-Z][A-Z0-9_]*):\\s*'([A-Z][A-Z0-9_]*)'").matcher(block);
        Set<String> codes = new TreeSet<>();
        while (matcher.find()) {
            assertEquals(matcher.group(1), matcher.group(2), "前端镜像键名与码值必须一致");
            codes.add(matcher.group(2));
        }
        return codes;
    }

    private static Set<String> parseMessageKeys(String file) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(repoFile("backend/src/main/resources/" + file), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.startsWith(ErrorBodyFactory.KEY_PREFIX)) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            assertTrue(eq > 0, "非法 messages 行：" + line);
            keys.add(trimmed.substring(ErrorBodyFactory.KEY_PREFIX.length(), eq));
        }
        return keys;
    }

    /** 从测试工作目录（backend）或仓根向上找到含 `frontend/src` 的目录。 */
    private static Path repoFile(String relative) {
        Path cursor = Paths.get("").toAbsolutePath();
        List<Path> candidates = List.of(cursor, cursor.getParent(), cursor.getParent() == null ? cursor : cursor.getParent().getParent());
        for (Path candidate : candidates) {
            Path resolved = candidate == null ? null : candidate.resolve(relative);
            if (resolved != null && Files.exists(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("找不到文件：" + relative + "（cwd=" + cursor + "）");
    }
}
