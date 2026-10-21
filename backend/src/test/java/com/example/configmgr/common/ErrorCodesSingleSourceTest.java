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
 * {@link ErrorCodes} 一处"。其它类的 {@code CODE_*} 必须是对它的别名；唯一例外是
 * {@code PendingToolCall} 里的 {@code FRONTEND_CANCELLED}/{@code PRECHECK_FAILED} ——
 * 它们是**待决条目的状态字面量**，与错误码同名不同义（同名是既有 wire 契约）。
 */
class ErrorCodesSingleSourceTest {

    /** 与错误码同名、但语义是"待决条目状态"的例外（保留原值，不迁入 ErrorCodes）。 */
    private static final Map<String, String> ALLOWED_DECLARATIONS = Map.of(
            "src/main/java/com/example/configmgr/ai/run/PendingToolCall.java",
            "待决条目状态字面量（与错误码同名不同义，wire 契约既有）");

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
                Matcher matcher = Pattern.compile("static final String [A-Za-z0-9_]+ = \"([A-Z][A-Z0-9_]+)\"")
                        .matcher(source);
                while (matcher.find()) {
                    if (ErrorCodes.ALL.contains(matcher.group(1))) {
                        offenders.add(relative + " → " + matcher.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "码字面量只允许在 ErrorCodes 声明（其它类请用别名）");
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
}
