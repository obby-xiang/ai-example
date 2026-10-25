package com.example.configmgr.ai.kb;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.tool.PlainTextToolResultConverter;
import com.example.configmgr.ai.tool.ToolCategory;
import com.example.configmgr.ai.tool.ToolMeta;
import com.example.configmgr.ai.tool.ToolOutcome;
import com.example.configmgr.ai.tool.ToolResultLimiter;
import com.example.configmgr.ai.tool.ToolRisk;
import com.example.configmgr.ai.tool.ToolScope;
import com.example.configmgr.common.ConflictException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 业务知识问答三工具（定稿 §3）：{@code kb_search} / {@code kb_read_doc} / {@code kb_lookup_term}。
 *
 * <h2>agentic 检索的分工</h2>
 * 模型拿到的不是"一次性向量召回"，而是三个可组合动作：
 * <ol>
 * <li>{@code kb_search} —— 先找候选（原词 + 同义词各查一次是提示词侧的规则）；</li>
 * <li>{@code kb_read_doc} —— snippet 不足时读原文，按 offset 连续翻页（不靠片段推测条款）；</li>
 * <li>{@code kb_lookup_term} —— 术语的<b>精确</b>解释（预制答案），未命中必须明说，不得模糊兜底。</li>
 * </ol>
 *
 * <h2>安全与披露</h2>
 * <ul>
 * <li>三个工具都是 {@code @ToolScope("*")}：知识问答可能在任意页面发生，且它们只读、无副作用；</li>
 * <li>风险 READ / 通道 BACKEND / 类别 R ⇒ 不走确认门、不进前端挂起（走既有披露-裁剪-台账全链）；</li>
 * <li>所有正文结果过 {@link KbText}：{@code <knowledge>} 定界 + "数据非指令"声明 + 定界符剥离 +
 * 8000 字符闸（分数与非必要调试信息一律不出现）。</li>
 * <li>结果转换器 = {@link PlainTextToolResultConverter}（L-88）：三个工具都返回文本，
 * 不能走官方默认的 JSON 编码（那会把正文再套一层字符串字面量）。</li>
 * </ul>
 *
 * <h2>分页与模型侧限流同口径（L-87）</h2>
 * 页大小同时受 {@link ToolResultLimiter} 的<b>行预算</b>与字符预算约束：一页必须能被模型
 * <b>整页</b>看到 —— 否则限流器会按行切掉页尾（{@code </knowledge>} 与
 * {@code next_offset} 一起丢失），模型照 next_offset 翻页就会漏读被切掉的那段。
 * 故 {@link #kbReadDoc} 用限流器预算派生每页的行/字符上限，并在回填前按同一份判定复核
 * （不满足就缩小页重读），而不是让限流器去切。
 */
@Component
public class KbTools {

    /**
     * 未就绪时的统一回执（fail-open：不抛异常、不阻塞对话）。
     *
     * <p>两种"未就绪"必须可区分（批 D · D4 / 问题 7）：
     * <ul>
     * <li>{@code lastError} 为空 = 首次建索引还在跑（启动异步建索引的正常窗口）⇒
     * 「首次建索引进行中」，让模型稍后重试；</li>
     * <li>{@code lastError} 非空 = 索引<b>真的没建成</b>（目录配错 / manifest 坏 / HTTP 源不可达）⇒
     * 把原因原样带上，并给出两条可行动的修复路径（检查 {@code app.ai.kb.dir} 或
     * {@code POST /api/ai/kb/reload}）。</li>
     * </ul>
     * 旧文案把两者都说成"加载中，请稍后重试"：配错目录时模型会一直让用户等，
     * 用户永远等不到，也永远看不到"为什么"。英文句尾供英文提问的会话直接读懂。
     */
    static final String NOT_READY_TEXT = "知识库加载中：首次建索引进行中，请稍后重试。"
            + "若持续超过 1 分钟，请联系管理员检查知识源（manifest/目录/HTTP 服务）。"
            + " (Knowledge base is still building its first index; please retry shortly.)";

    static final String DISABLED_TEXT = "知识库未启用（app.ai.kb.enabled=false），无法检索业务知识。";

    /** 渲染开销预留行数（定界/声明/表头/目录框/正文框/尾行 + 目录行余量）。 */
    private static final int RENDER_ROWS_RESERVE = 12;

    /** 页字符收缩的下限（再小就失去翻页意义；只为避免空页死循环）。 */
    private static final int MIN_PAGE_CHARS = 200;

    /** 缩页重读的最多轮次（正常 1 轮即收敛：正文行数初值已扣预留，目录过长的首屏才需要第二轮）。 */
    private static final int PAGE_FIT_ATTEMPTS = 4;

    private static final String READ_DOC = "kb_read_doc";

    private final KbService kbService;

    private final ToolResultLimiter limiter;

    /** 生产装配：限流器与回填路径同一实例（同一份 {@code app.ai.result-budget.model.*}）。 */
    @Autowired
    public KbTools(KbService kbService, ToolResultLimiter limiter) {
        this.kbService = kbService;
        this.limiter = limiter;
    }

    /** 用例/轻装配：无显式限流器时按配置缺省值（与生产默认口径一致：200 行 / 20000 字符）。 */
    public KbTools(KbService kbService) {
        this(kbService, new ToolResultLimiter(new AiProperties()));
    }

    /** 未就绪回执（按 {@code status().lastError()} 区分"建索引中"与"建索引失败"）。 */
    private String notReadyText() {
        KbStatus status = this.kbService.status();
        String reason = status.lastError() == null ? "" : status.lastError().trim();
        if (reason.isEmpty()) {
            return NOT_READY_TEXT;
        }
        return "知识库索引未建成：" + reason
                + "。请检查 app.ai.kb.dir 指向的知识目录（或 HTTP 知识源）后调用 POST /api/ai/kb/reload 重建索引。"
                + " (Knowledge base index is unavailable: " + reason
                + "; check app.ai.kb.dir or POST /api/ai/kb/reload.)";
    }

    // ---------------------------------------------------------------- kb_search

    @Tool(name = "kb_search", description = "检索业务知识库（制度/流程/口径/术语类问题先用它）。"
            + "每条返回 doc_id/title/section/snippet/updated_at/match（high/medium/low）；不返回检索分数。"
            + "空结果状态为 no_match（含检索词与近似标题；近似标题只用于换词重查，不是命中，"
            + "不得在答复中列举或引用）或 low_confidence（仅 1–2 条，全部标 low）；"
            + "拿到片段后如需条款原文，用 kb_read_doc 读取，不要仅凭片段推测",
            resultConverter = PlainTextToolResultConverter.class)
    @ToolScope("*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolCategory(ToolMeta.Category.R)
    public String kbSearch(
            @ToolParam(description = "检索词（原词；必要时把同义词/口头叫法再查一次）") String query,
            @ToolParam(description = "返回条数 1..10，默认 5（超过上限按 10 截断）", required = false) Integer topK) {
        int effectiveTopK = this.kbService.effectiveTopK(topK);
        KbSearchResult result = this.kbService.search(query, effectiveTopK);
        if (result.state() == KbSearchResult.State.INVALID_QUERY) {
            throw new ConflictException(ToolOutcome.CODE_ILLEGAL_ARGUMENT, result.error());
        }
        return switch (result.state()) {
            case NOT_READY -> KbText.plain(notReadyText());
            case DISABLED -> KbText.plain(DISABLED_TEXT);
            case ERROR -> KbText.plain("知识库检索失败：" + result.error()
                    + "。可稍后重试；若持续失败，请联系管理员检查知识源（不要把失败当成“没有这条制度”）。");
            default -> KbText.wrap(renderSearch(result, effectiveTopK));
        };
    }

    private String renderSearch(KbSearchResult result, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(result.state().name().toLowerCase()).append('\n');
        sb.append("查询词: ").append(String.join(", ", result.searchedTerms())).append('\n');
        if (result.state() == KbSearchResult.State.NO_MATCH) {
            sb.append("未检索到相关知识。\n");
            if (!result.suggestions().isEmpty()) {
                sb.append("可参考的标题: ").append(String.join("；", result.suggestions())).append('\n');
                // 候选只用于换词重查：不带 doc_id（真机 K-10 的泄漏面），且必须说清它不是命中
                sb.append("提示: 以上仅为近似标题，不是命中；只用于换用同义词/正式名称重查，"
                        + "不得在答复中列举或引用。\n");
            }
            sb.append("建议: 换用业务口径中的正式名称或同义词再查一次；仍无结果时明确说明“知识库中未找到”，"
                    + "并建议用户联系对应业务负责人。\n");
            return sb.toString();
        }
        sb.append("命中: ").append(result.hits().size()).append(" 条（top_k=").append(topK).append("）\n");
        int index = 1;
        for (KbSearchHit hit : result.hits()) {
            sb.append(index++).append(") doc_id=").append(hit.docId())
                    .append(" | title=").append(hit.title())
                    .append(" | section=").append(hit.section())
                    .append(" | match=").append(hit.match())
                    .append(" | updated_at=").append(hit.updatedAt())
                    .append('\n')
                    .append("   摘要: ").append(hit.snippet()).append('\n');
        }
        if (result.state() == KbSearchResult.State.LOW_CONFIDENCE) {
            sb.append("提示: 仅命中 1–2 条，属低置信结果；请用 kb_read_doc 核对原文后再作答，"
                    + "并在回答中说明依据有限。\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- kb_read_doc

    @Tool(name = "kb_read_doc", description = "读取知识文档原文（按 offset 连续翻页）。"
            + "省略 section 时先回目录 + 前 1200 字；max_chars 默认 4000、上限 8000（单页同时受行数预算约束，"
            + "实际页可能短于 max_chars，next_offset 才是唯一翻页依据）；"
            + "返回 truncated/next_offset —— 还有下一页就用 next_offset 继续读，不要跳读或凭片段推测。"
            + "doc_id/section 不存在时返回候选，不会报错中断",
            resultConverter = PlainTextToolResultConverter.class)
    @ToolScope("*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolCategory(ToolMeta.Category.R)
    public String kbReadDoc(
            @ToolParam(description = "文档 id（来自 kb_search 的 doc_id 或知识目录）") String docId,
            @ToolParam(description = "章节路径（来自 kb_search 的 section 或目录；省略 = 读整篇）", required = false) String section,
            @ToolParam(description = "字符偏移（省略 = 0；翻页时用上一页返回的 next_offset）", required = false) Integer offset,
            @ToolParam(description = "本次最多读取的字符数（默认 4000，上限 8000）", required = false) Integer maxChars) {
        KbReadResult result = this.kbService.readDoc(docId, section, offset, maxChars);
        return switch (result.state()) {
            case NOT_READY -> KbText.plain(notReadyText());
            case DISABLED -> KbText.plain(DISABLED_TEXT);
            case ERROR -> KbText.plain("读取知识文档失败：" + result.error()
                    + "。可稍后重试，或用 kb_search 换一种检索词。");
            case DOC_NOT_FOUND -> KbText.plain("未找到文档 id=" + docId + "。\n"
                    + candidateLine(result.candidates())
                    + "建议: 用 kb_search 检索主题获取正确的 doc_id。");
            case SECTION_NOT_FOUND -> KbText.plain("文档 " + result.docId() + "（" + result.title()
                    + "）中没有章节“" + section + "”。\n"
                    + candidateLine(result.candidates())
                    + "建议: 省略 section 读取目录，或改用目录中的完整章节路径。");
            default -> readPage(result, docId, section, offset, maxChars);
        };
    }

    /**
     * 读一页并按<b>模型侧预算</b>复核（L-87）：一页必须整页可见。
     *
     * <p>页大小 = 限流器预算 − 渲染开销（{@link #RENDER_ROWS_RESERVE}）；
     * 复核用与回填<b>同一个</b> {@link ToolResultLimiter} 判定 + {@link KbText} 的 8000 字闸：
     * 任一超限就按观测超出量缩小页重读（页在行/字符边界上切，next_offset 仍连续），
     * 绝不把"被截断的页"交给模型 —— 那样 {@code </knowledge>} 与 next_offset 会一起丢失，
     * 模型按 next_offset 翻页就会漏读被切掉的那段。
     */
    private String readPage(KbReadResult first, String docId, String section, Integer offset, Integer maxChars) {
        ToolResultLimiter.ModelBudget budget = this.limiter.modelBudget(READ_DOC);
        int pageChars = Math.min(maxChars == null || maxChars <= 0 ? KbText.MAX_RESULT_CHARS : maxChars,
                KbText.MAX_RESULT_CHARS);
        int pageRows = budget.maxRows() > 0 ? Math.max(1, budget.maxRows() - RENDER_ROWS_RESERVE) : 0;

        KbReadResult page = first;
        String body = renderRead(page, docId);
        for (int attempt = 0; attempt < PAGE_FIT_ATTEMPTS; attempt++) {
            String rendered = KbText.wrap(body);
            if (fitsModelSide(rendered, body, budget)) {
                return rendered;
            }
            int nextRows = pageRows;
            if (pageRows > 1 && budget.maxRows() > 0 && ToolResultLimiter.rowsOf(rendered) > budget.maxRows()) {
                nextRows = Math.max(1, pageRows - (ToolResultLimiter.rowsOf(rendered) - budget.maxRows()) - 1);
            }
            int nextChars = pageChars;
            int excessChars = Math.max(
                    budget.maxChars() > 0 ? rendered.length() - budget.maxChars() : 0,
                    body.length() - KbText.bodyBudget());
            if (excessChars > 0 && pageChars > MIN_PAGE_CHARS) {
                nextChars = Math.max(MIN_PAGE_CHARS, pageChars - excessChars - 1);
            }
            if (nextRows == pageRows && nextChars == pageChars) {
                // 已到可缩下限：仍按当前页如实返回（限流器的 notice 会说明"被截断"）
                return rendered;
            }
            pageRows = nextRows;
            pageChars = nextChars;
            page = this.kbService.readDoc(docId, section, offset, pageChars, pageRows);
            if (page.state() != KbReadResult.State.OK) {
                return KbText.plain(page.error().isEmpty() ? "读取知识文档失败。" : "读取知识文档失败：" + page.error());
            }
            body = renderRead(page, docId);
        }
        return KbText.wrap(body);
    }

    /**
     * 本页是否能被模型<b>完整</b>看到：限流器不截断 + KbText 8000 字闸不截断 + 结构尾巴在。
     *
     * <p>结构尾巴（{@code [truncated=…]} 行）在 = 正文没被切掉尾部；与限流器判定同源，
     * 因此"模型看到的这页"与"工具算出的 next_offset"说的是同一段文字。
     */
    private boolean fitsModelSide(String rendered, String body, ToolResultLimiter.ModelBudget budget) {
        if (!KbText.fits(body)) {
            return false;
        }
        if (budget.maxRows() > 0 && ToolResultLimiter.rowsOf(rendered) > budget.maxRows()) {
            return false;
        }
        if (budget.maxChars() > 0 && rendered.length() > budget.maxChars()) {
            return false;
        }
        return rendered.endsWith(KbText.CLOSE) && rendered.contains("[truncated=");
    }

    private String renderRead(KbReadResult result, String docId) {
        StringBuilder sb = new StringBuilder();
        sb.append("doc_id=").append(result.docId()).append(" | title=").append(result.title());
        if (result.section() != null) {
            sb.append(" | section=").append(result.section());
        }
        sb.append('\n');
        if (!result.toc().isEmpty()) {
            sb.append("--- 目录 ---\n");
            for (String line : result.toc()) {
                sb.append(line).append('\n');
            }
        }
        sb.append("--- 正文 ---\n");
        sb.append(result.text()).append('\n');
        if (result.truncated()) {
            sb.append("[truncated=true next_offset=").append(result.nextOffset()).append("]\n");
        }
        else {
            sb.append("[truncated=false 已到末尾]\n");
        }
        return sb.toString();
    }

    private static String candidateLine(List<String> candidates) {
        if (candidates.isEmpty()) {
            return "";
        }
        return "候选: " + candidates.stream().limit(3).collect(Collectors.joining("；")) + "\n";
    }

    // ---------------------------------------------------------------- kb_lookup_term

    @Tool(name = "kb_lookup_term", description = "查询术语的预制解释（精确匹配 + 别名/口头叫法）。"
            + "命中返回答案与 source_doc/section/updated_at；未命中返回 found=false 与 next=use kb_search，"
            + "不做模糊兜底 —— 不要把近似词当成命中，也不要自行补写解释",
            resultConverter = PlainTextToolResultConverter.class)
    @ToolScope("*")
    @ToolRisk(ToolMeta.RiskLevel.READ)
    @ToolCategory(ToolMeta.Category.R)
    public String kbLookupTerm(
            @ToolParam(description = "术语（正式叫法或口头叫法，例如“标准费率”“费率表”）") String term) {
        KbLookupResult result = this.kbService.lookup(term);
        if (result.notReady()) {
            return KbText.plain(notReadyText());
        }
        if (result.disabled()) {
            return KbText.plain(DISABLED_TEXT);
        }
        if (!result.error().isEmpty()) {
            return KbText.plain("术语查询失败：" + result.error()
                    + "。可稍后重试，或改用 kb_search 检索。");
        }
        if (!result.found()) {
            StringBuilder sb = new StringBuilder();
            sb.append("found=false | term=").append(result.term())
                    .append(" | normalized=").append(result.normalized()).append('\n');
            sb.append("未找到该术语的预制解释。\n");
            if (!result.suggestions().isEmpty()) {
                sb.append("相近的预制术语: ").append(String.join("；", result.suggestions())).append('\n');
            }
            sb.append("next=use kb_search：请用 kb_search 检索该术语（原词 + 同义词并行），"
                    + "必要时用 kb_read_doc 读原文；仍无结果时明说未找到，不要编造解释。\n");
            return KbText.plain(sb.toString());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("found=true | term=").append(result.term())
                .append(" | normalized=").append(result.normalized()).append('\n');
        sb.append("answer: ").append(result.answer()).append('\n');
        sb.append("source_doc=").append(result.docId())
                .append(" | title=").append(result.title());
        if (result.section() != null && !result.section().isBlank()) {
            sb.append(" | section=").append(result.section());
        }
        sb.append(" | updated_at=").append(result.updatedAt()).append('\n');
        return KbText.wrap(sb.toString());
    }

}
