package com.example.configmgr.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 可视可操作专项的 7 个通用原语（`ui_*`）的下发前 / 回灌前复核。
 *
 * <h2>为什么需要（复用 {@link FrontendToolGuard} 扩展点）</h2>
 * `ui_*` 与生成式表单、表格写工具同属 {@link ToolMeta.Channel#FRONTEND}：后端方法体是哨兵桩，
 * 模型给的参数<b>一路直达浏览器</b>。通用原语的对象是页面上真实的按钮/输入框，闸门缺位的后果
 * 比"填错一张表单"更直接 —— 因此必须在<b>下发之前</b>把"选择器形态的入参"整条拒掉，
 * 而不是指望前端在解析阶段拦（勘察 §5.2 ④）。
 *
 * <p>挂起机制一行不改：{@code SpToolCallingManager#inspectFrontendArguments} 按
 * {@link #supports(String)} 首个命中即返回，`ui_` 前缀天然不与 {@code GenerativeFormGuard} /
 * {@link SheetWriteGuard} 相撞。
 *
 * <h2>入参六项</h2>
 * <ol>
 * <li><b>禁键</b>：出现 {@code selector/xpath/html/css/path/filePath/filename/file/dir} 任一键 ⇒ 整条拒绝
 *   （"模型产选择器"与"接受文件路径"是本设计明文不做的两件事）；</li>
 * <li><b>ref</b>：{@code ^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+){2,}$} —— <b>段字符集含大写与下划线</b>
 *   （裁决 1：真实 defCode 是 {@code CURRENCY} / {@code E2E_TEMP}，旧稿的 {@code [a-z0-9-]+} 会误杀）；</li>
 * <li><b>epoch</b>：{@code ^[a-z0-9]{6}-\d+$}（`nonce-counter`）；</li>
 * <li><b>长度</b>：{@code value} ≤200、{@code note} ≤60、{@code limit} 1..40、{@code delta} ±2000；</li>
 * <li><b>枚举</b>：{@code block ∈ {start,center,end,nearest}}，{@code kinds ⊆ 六角色+all}；</li>
 * <li><b>accept</b>：只能收窄到 {@code xlsx/zip} 的子集（放宽页面的上传白名单不是模型的权利）。</li>
 * </ol>
 *
 * <h2>回灌三项</h2>
 * 结果文本不得含盘符路径、{@code file://}、HTML/脚本片段（`<script` / `<iframe` / `javascript:`）——
 * 回灌文本会进模型上下文与台账，任何可执行片段都不该从这条通道流出去。
 */
@Component
public class UiPrimitiveGuard implements FrontendToolGuard {

    /** 本复核器认领的前缀（7 个原语全部以 ui_ 开头）。 */
    public static final String PREFIX = "ui_";

    /** 入参里**永不接受**的键（出现即整条拒绝）。 */
    static final Set<String> FORBIDDEN_KEYS = Set.of(
            "selector", "xpath", "html", "css", "path", "filePath", "filename", "file", "dir", "directory");

    /** 工具名 → 必填键（缺一即拒绝，不静默补默认）。 */
    private static final Set<String> KINDS = Set.of("button", "input", "select", "toggle", "tab", "link", "all");

    private static final Set<String> BLOCKS = Set.of("start", "center", "end", "nearest");

    private static final Set<String> ACCEPT_EXTENSIONS = Set.of("xlsx", "zip");

    /** ref：段字符集 `[A-Za-z0-9_-]+`，至少三段（`page.region.name`）。 */
    static final Pattern REF_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){2,}$");

    /** epoch：`<6 位 base36 nonce>-<计数器>`（与前端 `currentAiEpoch()` 同款）。 */
    static final Pattern EPOCH_PATTERN = Pattern.compile("^[a-z0-9]{6}-\\d+$");

    /** 盘符路径 / file 协议 / 脚本片段（回灌文本的禁用形态）。 */
    private static final Pattern RESULT_FORBIDDEN = Pattern.compile(
            "([A-Za-z]:[\\\\/])|(file://)|(<script)|(<iframe)|(javascript:)", Pattern.CASE_INSENSITIVE);

    private static final int VALUE_MAX = 200;

    private static final int NOTE_MAX = 60;

    private static final int LIMIT_MAX = 40;

    private static final int DELTA_MAX = 2000;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String toolName) {
        return toolName != null && toolName.startsWith(PREFIX);
    }

    @Override
    public Verdict inspectArguments(String argumentsJson) {
        JsonNode root;
        try {
            String text = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            root = this.mapper.readTree(text);
        } catch (Exception ex) {
            return Verdict.reject("UI_PRIMITIVE_ARGUMENTS_REJECTED", "入参不是合法 JSON，未下发到前端",
                    List.of("入参原文无法解析为 JSON 对象（工具未挂起、界面未被操作）"));
        }
        if (root == null || !root.isObject()) {
            return Verdict.reject("UI_PRIMITIVE_ARGUMENTS_REJECTED", "入参必须是 JSON 对象，未下发到前端",
                    List.of("顶层应为 {...}"));
        }

        List<String> reasons = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        root.fieldNames().forEachRemaining(fields::add);

        for (String key : fields) {
            if (FORBIDDEN_KEYS.contains(key)) {
                reasons.add("禁止键 `" + key + "`：本工具只接受 ref/epoch 一类语义参数，不接受选择器或文件路径");
            }
        }

        String ref = text(root, "ref");
        if (ref != null && !REF_PATTERN.matcher(ref).matches()) {
            reasons.add("`ref` 形如 import.upload.choose（至少三段，段字符集 [A-Za-z0-9_-]，实际：" + ref + "）");
        }
        String epoch = text(root, "epoch");
        if (epoch != null && !EPOCH_PATTERN.matcher(epoch).matches()) {
            reasons.add("`epoch` 形如 a1b2c3-7（6 位 base36 nonce + 计数器，实际：" + epoch + "）");
        }
        String value = text(root, "value");
        if (value != null && value.length() > VALUE_MAX) {
            reasons.add("`value` 不得超过 " + VALUE_MAX + " 字符（实际 " + value.length() + "）");
        }
        String note = text(root, "note");
        if (note != null && note.length() > NOTE_MAX) {
            reasons.add("`note` 不得超过 " + NOTE_MAX + " 字符（实际 " + note.length() + "）");
        }
        Integer limit = number(root, "limit");
        if (root.hasNonNull("limit")) {
            if (limit == null || limit < 1 || limit > LIMIT_MAX) {
                reasons.add("`limit` 必须是 1.." + LIMIT_MAX + " 的整数（实际：" + root.get("limit") + "）");
            }
        }
        Integer delta = number(root, "delta");
        if (root.hasNonNull("delta")) {
            if (delta == null || Math.abs(delta) > DELTA_MAX) {
                reasons.add("`delta` 必须是不超过 ±" + DELTA_MAX + " 的数字（实际：" + root.get("delta") + "）");
            }
        }
        String block = text(root, "block");
        if (block != null && !BLOCKS.contains(block)) {
            reasons.add("`block` 只能是 start/center/end/nearest（实际：" + block + "）");
        }
        JsonNode kinds = root.get("kinds");
        if (kinds != null && !kinds.isNull()) {
            if (!kinds.isArray()) {
                reasons.add("`kinds` 必须是字符串数组（button/input/select/toggle/tab/link/all）");
            } else {
                for (JsonNode item : kinds) {
                    if (!item.isTextual() || !KINDS.contains(item.asText())) {
                        reasons.add("`kinds` 里的角色不合法：" + item);
                    }
                }
            }
        }
        JsonNode regions = root.get("regions");
        if (regions != null && !regions.isNull() && !regions.isArray()) {
            reasons.add("`regions` 必须是区域 id 的字符串数组");
        }
        JsonNode accept = root.get("accept");
        if (accept != null && !accept.isNull()) {
            if (!accept.isArray()) {
                reasons.add("`accept` 必须是扩展名数组（只能收窄到 xlsx / zip）");
            } else {
                for (JsonNode item : accept) {
                    String ext = item.isTextual() ? item.asText().replaceFirst("^\\.", "").toLowerCase() : "";
                    if (!ACCEPT_EXTENSIONS.contains(ext)) {
                        reasons.add("`accept` 只能收窄到 xlsx / zip（收到：" + item + "）");
                    }
                }
            }
        }

        if (!reasons.isEmpty()) {
            return Verdict.reject("UI_PRIMITIVE_ARGUMENTS_REJECTED",
                    "界面原语入参不合法，未下发到前端（没有挂起、界面未被操作）", reasons);
        }
        return Verdict.accept();
    }

    @Override
    public Verdict inspectResult(String argumentsJson, String resultJson) {
        String text = resultJson == null ? "" : resultJson;
        if (RESULT_FORBIDDEN.matcher(text).find()) {
            return Verdict.reject("UI_PRIMITIVE_RESULT_REJECTED", "回灌结果含路径或可执行片段，已拒绝采用",
                    List.of("结果里出现了盘符路径 / file:// / <script> / <iframe> / javascript: 之一；"
                            + "界面原语的回灌只允许纯文本描述（不含任何文件路径与可执行片段）"));
        }
        return Verdict.accept();
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Integer number(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isNumber() ? node.asInt() : null;
    }
}
