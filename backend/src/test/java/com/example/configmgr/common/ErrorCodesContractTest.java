package com.example.configmgr.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
