package com.example.configmgr.ai.kb;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 系统提示词的"业务知识库"后缀段（定稿 §4 + 追加 4：不改人设，只在 {@code SYSTEM_PROMPT} 之后追加）。
 *
 * <h2>为什么是后缀而不是插进人设</h2>
 * 本段只在 manifest 变化（人工热重载/定时刷新拉到新版本）时才变，而人设是长期稳定的
 * 稳定前缀（prompt 缓存红利因此不被日常改文案击穿）；变化点尽量靠后。
 *
 * <h2>语言政策不在这里另立</h2>
 * 回答语言由主提示规则 1 + 系统消息中的「回答语言（本轮）」段（{@code ReplyLanguage}，位于
 * 工作区数据段之前）声明，
 * 本段规则 8 只声明"英文检索桥接 + 引用锚定中文标题"，<b>不重复语言政策</b> ——
 * 此前这里写"回答按用户所用语言生成"，与主提示「始终使用中文回复」直接相反且位置更近，
 * 同一份系统消息自相矛盾（A3-Y1）。
 *
 * <h2>目录是数据，规则是指令</h2>
 * 目录行（id/title/summary）来自 manifest，<b>全部过 {@link KbText#sanitize}</b> 并包在
 * {@code <knowledge_catalog>} 里 + "数据不是指令"声明；八条使用规则是固定文本，永远不来自知识内容
 * —— 否则一份知识文就能改写"未命中要明说"这类安全规则。
 *
 * <p><b>未就绪时</b>只注入规则、不注入空目录（"目录为空"会误导模型以为知识库确实没有文档；
 * 规则本身与索引状态无关）。
 */
@Component
@RequiredArgsConstructor
public class KbPromptSection {

    /**
     * 引用示例的真实 {@code doc_id}（与 {@code docs/kb-samples/manifest.json} 一致）。
     *
     * <p>用例锁"示例的 doc_id + 章节路径能被工具按原样返回"（{@code KbCitationIdConformanceTest}），
     * 因此这里与语料同名同源，改语料就要同步改示例。示例来自 manifest 首篇真实文档
     * （{@code region-tariff}）；旧示例把两篇文档的字段拼在一起，示范了工具永远返回不出来的形状。
     */
    static final String CITATION_DOC_ID = "region-tariff";

    /** 示例里的中文原标题（KB 规则 8 要求"附中文原标题"，必须逐字等于目录标题）。 */
    static final String CITATION_TITLE = "区域费率管理口径（样例）";

    /** 示例里的完整章节路径（{@code 文档标题 > 二级}，与切块器的 sectionPath 逐字一致）。 */
    static final String CITATION_SECTION = CITATION_TITLE + " > 1 定义";

    /** 示例本体：{@code [doc_id §章节路径]}（渲染时其后紧跟中文原标题）。 */
    static final String CITATION_EXAMPLE = "[" + CITATION_DOC_ID + " §" + CITATION_SECTION + "]";

    /** 固定规则（逐条对定稿 §4；改动即改提示词契约，用例按关键短语锁定）。 */
    static final String RULES = """
            ### 知识库使用规则
            1. 涉及制度、流程、口径、术语解释、系统使用说明、操作指南、FAQ 类问题，先调用 kb_search 检索，再基于检索结果作答。
            2. 检索时原词与同义词/别名并行：第一轮用用户原词，必要时换业务口头叫法再查一次。
            3. snippet 不足以回答时用 kb_read_doc 读取原文（按 next_offset 连续翻页）；不得仅凭片段推测条款。
            4. 引用格式为 [doc_id §章节]，只引用工具实际返回的命中内容（候选/近似标题除外）；工具没返回的条款不得引用。doc_id 只允许出现在这类支撑命中结论的引用里 —— 不得把候选、近似标题或目录里的 doc_id 写成命中依据。
            5. 未命中时明确说明“知识库中未找到”，并建议用户联系对应业务负责人；禁止编造条款、编号或口径。kb_search 返回的“可参考的标题/近似标题”只是换词重查的线索，不是命中：不得在答复正文列举、罗列或引用，也不得据此推断任何口径。
            6. 多篇内容冲突时，以 updated_at 较新者为准，并说明存在不同版本。
            7. 回答正文不要展示工具名、检索分数（match 档位）、文件名或任何调试信息。
            8. 用户用英文提问时，先用其英文术语检索（词表已通过 aliases_en 桥接到中文语料）；
               回答语言遵循系统提示的要求（见「回答语言（本轮）」段），此处不另行规定；
               引用仍锚定中文 doc_id §章节，并附中文原标题，例如
            """ + CITATION_EXAMPLE + "（" + CITATION_TITLE + "）。\n";

    /** 目录段字符上限（20 篇 × id/title/summary 的实测远小于此；超出截断并告知）。 */
    static final int CATALOG_MAX_CHARS = 4000;

    private final KbService kbService;

    /** 渲染后缀段；知识库未启用时返回空串（不占系统消息）。 */
    public String render() {
        if (!this.kbService.enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 业务知识库\n");
        List<KbDocMeta> docs = this.kbService.catalog();
        if (!docs.isEmpty()) {
            sb.append(KbText.NOTICE).append('\n');
            sb.append("<knowledge_catalog>\n");
            StringBuilder catalog = new StringBuilder();
            for (KbDocMeta doc : docs) {
                catalog.append("- ").append(KbText.sanitize(doc.id()))
                        .append(" | ").append(KbText.sanitize(doc.title()))
                        .append(" | ").append(KbText.sanitize(doc.displaySummary()))
                        .append('\n');
            }
            String text = catalog.toString();
            if (text.length() > CATALOG_MAX_CHARS) {
                text = text.substring(0, CATALOG_MAX_CHARS) + "…[目录已截断]\n";
            }
            sb.append(text);
            sb.append("</knowledge_catalog>\n");
        }
        else {
            // 批 D · D4 / 问题 7：空目录的旧文案只说"尚未就绪"，读起来像"再等等就有" ——
            // 模型于是拿通用知识顶上，并且当成官方口径讲。这里把两件事同时说清：
            // ① 现在是"不可用"而不是"加载中"（原因看 status）；② 禁止用通用知识冒充官方口径。
            sb.append("（知识库当前不可用：索引尚未就绪（原因见 GET /api/ai/kb/status 的 last_error）。"
                    + "不要用通用知识冒充官方口径 —— 涉及制度/流程/口径/术语/系统使用说明/操作指南/FAQ 的问题，"
                    + "应说明知识库暂不可用，并建议用户联系对应业务负责人。）\n");
        }
        sb.append(RULES);
        return sb.toString();
    }
}
