package com.example.configmgr.ai.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 切块器（定稿 §2：按 {@code ##} 切章节，300–800 字目标，标题/章节路径前缀）。
 *
 * <h2>三条不可破的形态</h2>
 * <ol>
 * <li><b>代码块 / mermaid 原样保留</b>：围栏块（``` / ~~~）整体作为一个不可分块进入 chunk，
 * 不按内部空行切分、不把 ``` 之后的行误判为标题 —— 知识文里的流程图/时序图被切碎等于丢失；</li>
 * <li><b>表格整块</b>：连续以 {@code |} 开头的行视作一个块（宽表也不按行切）；只有单块超过硬上限
 * 时才按字符硬切（宁可切表格也不产出超长 chunk）；</li>
 * <li><b>深层标题可追溯</b>：{@code ###} 及更深的标题会另起一个 chunk，并把完整路径
 * （{@code 文档标题 > 二级 > 三级 > …}）写进 {@code indexText} 前缀与 {@code sectionPath} 字段。</li>
 * </ol>
 *
 * <p>标题识别只在围栏外进行（``` 内的 {@code #} 是内容不是标题）；切块目标区间取配置
 * {@code app.ai.kb.chunk-min-chars|chunk-max-chars}（默认 300/800），硬上限是刚性约束，
 * 下限是"尽量合并"的目标（很短的独立小节允许短于下限，不跨章节拼凑）。
 */
public final class MarkdownChunker {

    private MarkdownChunker() {
    }

    /** 统一换行与 BOM（sha 基于归一化后的文本，跨平台一致）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    /** 解析章节（仅 2–6 级；1 级视为文档标题，不单独成节）。 */
    public static List<KbSection> sections(String content, String docTitle) {
        List<Heading> headings = scanHeadings(content);
        List<KbSection> out = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading h = headings.get(i);
            if (h.level < 2) {
                continue;
            }
            int end = content.length();
            for (int j = i + 1; j < headings.size(); j++) {
                if (headings.get(j).level <= h.level) {
                    end = headings.get(j).lineStart;
                    break;
                }
            }
            out.add(new KbSection(h.level, h.title, pathOf(headings, i, docTitle),
                    h.lineStart, h.bodyStart, end));
        }
        return out;
    }

    /** 切块。 */
    public static List<KbChunk> chunk(String docId, String docTitle, String content,
            int minChars, int maxChars) {
        List<KbChunk> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int[] bufferStart = { 0 };
        String[] path = { docTitle };
        List<Heading> stack = new ArrayList<>();

        boolean inFence = false;
        String fenceMarker = null;
        int fenceStartLine = 0;
        StringBuilder fence = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        int paragraphStart = -1;
        StringBuilder table = null;
        int tableStart = -1;

        List<Block> blocks = new ArrayList<>();

        int pos = 0;
        while (pos <= content.length()) {
            int lineEnd = content.indexOf('\n', pos);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String line = content.substring(pos, lineEnd);

            String trimmed = line.stripLeading();
            boolean fenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            if (inFence) {
                fence.append(line).append('\n');
                if (fenceLine && trimmed.startsWith(fenceMarker)
                        && trimmed.substring(fenceMarker.length()).isBlank()) {
                    blocks.add(new Block(fence.toString(), fenceStartLine, true));
                    fence.setLength(0);
                    inFence = false;
                }
                pos = lineEnd + 1;
                continue;
            }
            if (fenceLine) {
                flushParagraph(blocks, paragraph, paragraphStart);
                flushTable(blocks, table, tableStart);
                table = null;
                inFence = true;
                fenceMarker = trimmed.startsWith("```") ? "```" : "~~~";
                fenceStartLine = pos;
                fence.append(line).append('\n');
                pos = lineEnd + 1;
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(blocks, paragraph, paragraphStart);
                flushTable(blocks, table, tableStart);
                table = null;
                pos = lineEnd + 1;
                continue;
            }
            if (trimmed.startsWith("|")) {
                flushParagraph(blocks, paragraph, paragraphStart);
                if (table == null) {
                    table = new StringBuilder();
                    tableStart = pos;
                }
                table.append(line).append('\n');
                pos = lineEnd + 1;
                continue;
            }
            flushTable(blocks, table, tableStart);
            table = null;
            if (isHeading(line)) {
                flushParagraph(blocks, paragraph, paragraphStart);
                Heading heading = parseHeading(line, pos, lineEnd + 1);
                blocks.add(new Block(heading.rawLine + "\n", pos, true, heading));
            }
            else {
                if (paragraph.length() == 0) {
                    paragraphStart = pos;
                }
                paragraph.append(line).append('\n');
            }
            pos = lineEnd + 1;
        }
        flushParagraph(blocks, paragraph, paragraphStart);
        flushTable(blocks, table, tableStart);
        if (inFence && fence.length() > 0) {
            blocks.add(new Block(fence.toString(), fenceStartLine, true));
        }

        for (Block block : blocks) {
            if (block.heading != null) {
                Heading h = block.heading;
                flush(out, docId, path[0], buffer, bufferStart);
                if (h.level < 2) {
                    stack.clear();
                    path[0] = docTitle;
                }
                else {
                    while (!stack.isEmpty() && stack.get(stack.size() - 1).level >= h.level) {
                        stack.remove(stack.size() - 1);
                    }
                    stack.add(h);
                    path[0] = docTitle + " > " + joinStack(stack);
                }
                addBlock(out, docId, path[0], buffer, bufferStart, block, maxChars);
                continue;
            }
            addBlock(out, docId, path[0], buffer, bufferStart, block, maxChars);
        }
        flush(out, docId, path[0], buffer, bufferStart);
        // 重新编号（硬切/合并后 ordinal 必须连续）
        List<KbChunk> renumbered = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            KbChunk c = out.get(i);
            renumbered.add(new KbChunk(c.docId(), c.sectionPath(), i, c.body(),
                    c.startOffset(), c.endOffset(), c.indexText()));
        }
        return renumbered;
    }

    // ---------------------------------------------------------------- internals

    private static void addBlock(List<KbChunk> out, String docId, String path, StringBuilder buffer,
            int[] bufferStart, Block block, int maxChars) {
        if (block.text.length() > maxChars) {
            flush(out, docId, path, buffer, bufferStart);
            List<String> pieces = block.text.stripLeading().startsWith("|")
                    ? splitTable(block.text, maxChars)
                    : splitLong(block.text, maxChars);
            for (String piece : pieces) {
                if (piece.isBlank()) {
                    continue;
                }
                out.add(new KbChunk(docId, path, out.size(), piece.strip(), block.start, block.start + piece.length(),
                        path + "\n" + piece.strip()));
            }
            return;
        }
        int extra = buffer.length() == 0 ? 0 : 1;
        if (buffer.length() + extra + block.text.length() > maxChars) {
            flush(out, docId, path, buffer, bufferStart);
        }
        if (buffer.length() == 0) {
            bufferStart[0] = block.start;
        }
        else {
            buffer.append('\n');
        }
        buffer.append(block.text.strip());
    }

    private static void flush(List<KbChunk> out, String docId, String path, StringBuilder buffer,
            int[] bufferStart) {
        String body = buffer.toString().strip();
        buffer.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        out.add(new KbChunk(docId, path, out.size(), body, bufferStart[0],
                bufferStart[0] + body.length(), path + "\n" + body));
    }

    private static List<String> splitLong(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        String rest = text;
        while (rest.length() > maxChars) {
            int cut = maxChars;
            int windowStart = (int) (maxChars * 0.75);
            int nl = rest.lastIndexOf('\n', cut - 1);
            if (nl >= windowStart) {
                cut = nl + 1;
            }
            else {
                int punctuation = lastIndexOfAny(rest, windowStart, cut - 1, "。！？；，、.!?;,");
                if (punctuation >= windowStart) {
                    cut = punctuation + 1;
                }
            }
            pieces.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut);
        }
        if (!rest.isBlank()) {
            pieces.add(rest);
        }
        return pieces;
    }

    /**
     * 超宽表格的分块：按行装包，<b>每块都重复表头与分隔行</b>。
     *
     * <p>直接按字符硬切会把表格切成"没有表头的裸行"，模型读到第二块时无法判断列义 —— 那是
     * 比超长更糟的失真。这里以行（{@code | ... |}）为最小单位，单行 + 表头仍超上限时才退回硬切。
     */
    private static List<String> splitTable(String table, int maxChars) {
        String[] lines = table.split("\n", -1);
        if (lines.length <= 2) {
            return splitLong(table, maxChars);
        }
        String header = lines[0] + "\n" + lines[1];
        if (header.length() + 1 + lines[2].length() > maxChars) {
            return splitLong(table, maxChars);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder(header);
        for (int i = 2; i < lines.length; i++) {
            String row = lines[i];
            if (piece.length() > header.length() && piece.length() + 1 + row.length() > maxChars) {
                pieces.add(piece.toString());
                piece = new StringBuilder(header);
            }
            piece.append('\n').append(row);
        }
        if (piece.length() > header.length()) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    private static int lastIndexOfAny(String text, int from, int to, String chars) {
        for (int i = Math.min(to, text.length() - 1); i >= from; i--) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static void flushParagraph(List<Block> blocks, StringBuilder paragraph, int start) {
        if (paragraph.length() == 0) {
            return;
        }
        String text = paragraph.toString().stripTrailing();
        paragraph.setLength(0);
        if (!text.isBlank()) {
            blocks.add(new Block(text, start, true));
        }
    }

    private static void flushTable(List<Block> blocks, StringBuilder table, int start) {
        if (table == null || table.length() == 0) {
            return;
        }
        String text = table.toString().stripTrailing();
        table.setLength(0);
        if (!text.isBlank()) {
            blocks.add(new Block(text, start, true));
        }
    }

    private static final class Block {

        private final String text;

        private final int start;

        private final boolean atomic;

        private final Heading heading;

        private Block(String text, int start, boolean atomic) {
            this(text, start, atomic, null);
        }

        private Block(String text, int start, boolean atomic, Heading heading) {
            this.text = text;
            this.start = start;
            this.atomic = atomic;
            this.heading = heading;
        }
    }

    private static final class Heading {

        private final int level;

        private final String title;

        private final int lineStart;

        private final int bodyStart;

        private final String rawLine;

        private Heading(int level, String title, int lineStart, int bodyStart, String rawLine) {
            this.level = level;
            this.title = title;
            this.lineStart = lineStart;
            this.bodyStart = bodyStart;
            this.rawLine = rawLine;
        }
    }

    private static boolean isHeading(String line) {
        return parseHeadingOrNull(line) != null;
    }

    private static Heading parseHeading(String line, int lineStart, int bodyStart) {
        Heading heading = parseHeadingOrNull(line);
        return new Heading(heading.level, heading.title, lineStart, bodyStart, line);
    }

    private static Heading parseHeadingOrNull(String line) {
        String stripped = line.stripLeading();
        if (!stripped.startsWith("#")) {
            return null;
        }
        int level = 0;
        while (level < stripped.length() && stripped.charAt(level) == '#') {
            level++;
        }
        if (level > 6 || level >= stripped.length() || !Character.isWhitespace(stripped.charAt(level))) {
            return null;
        }
        String title = stripped.substring(level).strip();
        while (title.endsWith("#")) {
            title = title.substring(0, title.length() - 1).strip();
        }
        if (title.isEmpty()) {
            return null;
        }
        return new Heading(level, title, -1, -1, line);
    }

    private static List<Heading> scanHeadings(String content) {
        List<Heading> out = new ArrayList<>();
        boolean inFence = false;
        String marker = null;
        int pos = 0;
        int lineStart = 0;
        while (pos <= content.length()) {
            int lineEnd = content.indexOf('\n', pos);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String line = content.substring(pos, lineEnd);
            String trimmed = line.stripLeading();
            boolean fenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            if (inFence) {
                if (fenceLine && trimmed.startsWith(marker)
                        && trimmed.substring(marker.length()).isBlank()) {
                    inFence = false;
                }
            }
            else if (fenceLine) {
                inFence = true;
                marker = trimmed.startsWith("```") ? "```" : "~~~";
            }
            else {
                Heading heading = parseHeadingOrNull(line);
                if (heading != null) {
                    out.add(new Heading(heading.level, heading.title, pos, lineEnd + 1, line));
                }
            }
            pos = lineEnd + 1;
        }
        return out;
    }

    private static String pathOf(List<Heading> headings, int index, String docTitle) {
        Heading target = headings.get(index);
        List<Heading> stack = new ArrayList<>();
        for (int i = 0; i <= index; i++) {
            Heading h = headings.get(i);
            if (h.level < 2 && i != index) {
                stack.clear();
                continue;
            }
            if (h.level < 2) {
                continue;
            }
            while (!stack.isEmpty() && stack.get(stack.size() - 1).level >= h.level) {
                stack.remove(stack.size() - 1);
            }
            stack.add(h);
        }
        String suffix = joinStack(stack);
        return suffix.isEmpty() ? docTitle : docTitle + " > " + suffix;
    }

    private static String joinStack(List<Heading> stack) {
        StringBuilder sb = new StringBuilder();
        for (Heading h : stack) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(h.title);
        }
        return sb.toString();
    }
}
