package com.example.configmgr.ai.run;

import com.example.configmgr.common.ErrorBodyFactory;

import java.util.Locale;

/**
 * 系统消息中「回答语言（本轮）」段的唯一出口。
 *
 * <h2>统一语言优先级</h2>
 * 助手生成内容按“显式语言要求 &gt; 当前实质请求主导语言 &gt; 短指令沿用上一条可判定实质请求 &gt;
 * UI locale &gt; zh-CN”判定。主提示与 KB 段只引用本段，不各自重述另一套语言政策。
 *
 * <h2>UI locale 从哪来</h2>
 * {@link #current()} 取 {@link ErrorBodyFactory#requestLocale()} —— 与错误体本地化<b>同一个取值点</b>
 * （{@code Accept-Language}，缺失/畸形一律 zh-CN，不看 JVM 默认 locale；语言级范围 {@code en}/
 * {@code en-GB} 由 resolver 归一到 en-US，本类不另造第二源）。SSE 通道此前不带该头，
 * 于是后端恒定中文；{@code frontend/src/api/sse.ts} 补头之后，REST 与 SSE 两条通道的来源一致。
 *
 * <h2>为什么仍用中文写这一段</h2>
 * 提示词骨架保持稳定中文单语，只有 UI locale 标签随请求环境变化；助手输出语言由本段独立控制，
 * 不根据系统提示词、代码、专名、原始业务数据或工具结果的语言改答。
 *
 * <h2>术语与原始标识不翻译</h2>
 * 只翻译说明性自然语言。工具调用、引用和外部系统所需的术语、标识与原始值保持原样，避免破坏
 * 工具名调用、错误码查表、章节路径回查以及逐字引文和原始业务数据。
 */
public record ReplyLanguage(String tag) {

    /** 支持的语言标签（与 {@code WebI18nConfig} 的 supported locales 一致）。 */
    public static final String ZH_CN = "zh-CN";

    public static final String EN_US = "en-US";

    /** 无 locale 信号时的 UI locale，也是最终语言回退值。 */
    public static final ReplyLanguage DEFAULT = new ReplyLanguage(ZH_CN);

    /** 语言归一：只有 {@code en} 走英文，其余（含 null / 其它语言）一律中文。 */
    public static ReplyLanguage of(Locale locale) {
        return locale != null && "en".equalsIgnoreCase(locale.getLanguage())
                ? new ReplyLanguage(EN_US) : DEFAULT;
    }

    /** 当前 UI locale（线程级 {@code LocaleContextHolder}，由控制器在提交任务前放到工作线程）。 */
    public static ReplyLanguage current() {
        return of(ErrorBodyFactory.requestLocale());
    }

    public boolean english() {
        return EN_US.equals(this.tag);
    }

    /** 系统消息中的「回答语言（本轮）」段（位于工作区数据段之前）。 */
    public String directive() {
        return """
                ### 回答语言（本轮）
                语言优先级：显式语言要求 > 当前实质请求主导语言 > 短指令沿用上一条可判定实质请求 > UI locale > zh-CN。
                当前 UI locale：%s。用户明确指定回答语言时服从该要求；否则按当前实质请求的主导语言回答，
                不根据系统提示词、代码、专名、原始业务数据或工具结果的语言改答。
                「继续」「重试」等短指令本身不判定新语言，沿用上一条可判定实质请求；
                短指令没有上一条可判定实质请求时，或当前实质请求无法判定主导语言时，使用 UI locale，最终回退 zh-CN。
                只翻译说明性自然语言；产品名、项目术语、API/工具名、代码标识、路径、URL、命令及选项、字段、枚举、
                错误码、版本号、ID、原始标题和逐字引文保持原有拼写、大小写、数字、空格与标点，不翻译或改写；
                工具参数和外部系统原始值不得本地化。
                """.formatted(tag);
    }
}
