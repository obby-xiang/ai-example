package com.example.configmgr.ai.kb;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 系统提示词的"业务知识库"后缀段（定稿 §4 + 追加 4：不动既有段，只在 {@code SYSTEM_PROMPT} 末尾追加）。
 *
 * <h2>为什么是后缀而不是插进人设</h2>
 * 既有 {@code SYSTEM_PROMPT} 一字不改（prompt 缓存的最长稳定前缀因此不被击穿）；本段只在
 * manifest 变化（人工热重载/定时刷新拉到新版本）时才变，变化点尽量靠后。
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

    /** 固定规则（逐条对定稿 §4；改动即改提示词契约，用例按关键短语锁定）。 */
    static final String RULES = """
            ### 知识库使用规则
            1. 涉及制度、流程、口径、术语解释、系统使用说明、操作指南、FAQ 类问题，先调用 kb_search 检索，再基于检索结果作答。
            2. 检索时原词与同义词/别名并行：第一轮用用户原词，必要时换业务口头叫法再查一次。
            3. snippet 不足以回答时用 kb_read_doc 读取原文（按 next_offset 连续翻页）；不得仅凭片段推测条款。
            4. 引用格式为 [doc_id §章节]，只引用工具实际返回过的内容；工具没返回的条款不得引用。
            5. 未命中时明确说明“知识库中未找到”，并建议用户联系对应业务负责人；禁止编造条款、编号或口径。
            6. 多篇内容冲突时，以 updated_at 较新者为准，并说明存在不同版本。
            7. 回答正文不要展示工具名、检索分数（match 档位）、文件名或任何调试信息。
            8. 用户用英文提问时，先用其英文术语检索（词表已通过 aliases_en 桥接到中文语料）；
               回答按用户所用语言生成，引用仍锚定中文 doc_id §章节，并附中文原标题（例如
               [region-tariff §区域费率管理口径 > 计算规则 · 区域费率管理口径]）。
            """;

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
