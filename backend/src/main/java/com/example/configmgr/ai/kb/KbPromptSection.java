package com.example.configmgr.ai.kb;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 系统提示词的"业务知识库"后缀段：只保留按需检索规则与索引状态，不再逐轮展开全目录。
 * 目录与正文均通过 KB 工具按需获得；安全定界和未命中口径不变。
 */
@Component
@RequiredArgsConstructor
public class KbPromptSection {

    static final String CITATION_DOC_ID = "region-tariff";
    static final String CITATION_TITLE = "区域费率管理口径（样例）";
    static final String CITATION_SECTION = CITATION_TITLE + " > 1 定义";
    static final String CITATION_EXAMPLE = "[" + CITATION_DOC_ID + " §" + CITATION_SECTION + "]";
    static final int CATALOG_MAX_CHARS = 4000;

    static final String RULES = """
            ### 知识库使用规则
            1. 只查某个术语的含义、定义或别名（正式术语、简称、口头叫法）时先调用 kb_lookup_term；未命中再调用 kb_search。
            2. 制度、流程、规范、口径、操作步骤、系统使用说明、FAQ 类问题不得先调用 kb_lookup_term，应直接调用 kb_search；无需检索的问题不强制调用。
            3. snippet 不足时用 kb_read_doc 按 next_offset 连续翻页，不得仅凭片段推测。
            4. 引用格式为 [doc_id §章节]，只引用工具实际返回的命中内容；近似标题只用于换词重查，不是命中依据。
            5. 未命中时可改写一次；仍无结果就明确说明“知识库中未找到”并建议联系对应业务负责人，禁止编造。
            6. 多篇冲突时以 updated_at 较新者为准并说明版本差异；回答正文不展示工具名、检索分数、文件名或调试信息。
            7. 英文提问先用英文术语检索；回答语言遵循「回答语言（本轮）」段的统一优先级，引用仍锚定原始 doc_id §章节并附原始标题，不翻译或改写。
            """;

    private final KbService kbService;

    /** 渲染精简后缀；未启用时不占系统消息，目录不再进入提示词。 */
    public String render() {
        if (!this.kbService.enabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 业务知识库\n");
        if (this.kbService.catalog().isEmpty()) {
            sb.append("（知识库当前不可用：索引尚未就绪。"
                    + "不要用通用知识冒充官方口径 —— 涉及制度/流程/口径/术语/系统使用说明/操作指南/FAQ 的问题，"
                    + "应说明知识库暂不可用，并建议用户联系对应业务负责人。）\n");
        }
        sb.append(RULES);
        return sb.toString();
    }
}
