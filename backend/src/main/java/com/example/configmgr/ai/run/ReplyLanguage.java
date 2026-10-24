package com.example.configmgr.ai.run;

import com.example.configmgr.common.ErrorBodyFactory;

import java.util.Locale;

/**
 * 本轮回答语言（A3-Y1 + A3-Y7 的唯一出口）。
 *
 * <h2>为什么需要它</h2>
 * 用户裁决 4B（2026-10-24）：<b>AI 默认中文，用户使用其它语言则从其语言</b>。修复前这条口径在系统消息里
 * 被声明了两遍且互相矛盾 —— 主提示「始终使用中文回复」与知识库规则「回答按用户所用语言生成」，
 * 后者位置更近，行为随模型漂移。现在语言政策<b>只在主提示第 1 条声明一次</b>，具体目标语言由本类按
 * 本轮请求语言渲染成系统消息中的一段「回答语言（本轮）」（位于工作区数据段之前），KB 段只引用、不重述。
 *
 * <h2>语言从哪来</h2>
 * {@link #current()} 取 {@link ErrorBodyFactory#requestLocale()} —— 与错误体本地化<b>同一个取值点</b>
 * （{@code Accept-Language}，缺失/畸形一律 zh-CN，不看 JVM 默认 locale；语言级范围 {@code en}/
 * {@code en-GB} 由 resolver 归一到 en-US，本类不另造第二源）。SSE 通道此前不带该头，
 * 于是后端恒定中文；{@code frontend/src/api/sse.ts} 补头之后，REST 与 SSE 两条通道的来源一致。
 *
 * <h2>为什么仍用中文写这一段</h2>
 * 提示词骨架保持单语（改动面最小、prompt 缓存前缀不变），只有"目标语言"这一个变量随 locale 变；
 * 段内点明目标语言名，避免模型把"按用户语言"理解成"翻译整段回答"。
 *
 * <h2>内部标识不翻译</h2>
 * 段内固定一句"工具名、参数名、错误码、doc_id、章节路径…保持原样，不得翻译"：否则"随用户语言"
 * 会被执行成把 {@code SESSION_BUSY}、{@code kb_search}、章节路径一起翻掉 —— 前端按错误码查表、
 * 模型按工具名调用、引用按章节路径回查，三者都会被破坏。
 */
public record ReplyLanguage(String tag) {

    /** 支持的语言标签（与 {@code WebI18nConfig} 的 supported locales 一致）。 */
    public static final String ZH_CN = "zh-CN";

    public static final String EN_US = "en-US";

    /** 无 locale 信号时的回答语言（4B 的"默认中文"）。 */
    public static final ReplyLanguage DEFAULT = new ReplyLanguage(ZH_CN);

    /** 语言归一：只有 {@code en} 走英文，其余（含 null / 其它语言）一律中文。 */
    public static ReplyLanguage of(Locale locale) {
        return locale != null && "en".equalsIgnoreCase(locale.getLanguage())
                ? new ReplyLanguage(EN_US) : DEFAULT;
    }

    /** 本轮请求语言（线程级 {@code LocaleContextHolder}，由控制器在提交任务前放到工作线程）。 */
    public static ReplyLanguage current() {
        return of(ErrorBodyFactory.requestLocale());
    }

    public boolean english() {
        return EN_US.equals(this.tag);
    }

    /**
     * 系统消息中的「回答语言（本轮）」段（位于工作区数据段之前）。
     *
     * <p>两段文本只差"目标语言"与"固定指令词回落哪一种语言"；内部标识一句两段逐字相同 ——
     * 用例按这两句锁定（{@code LanguageDirectiveWiringTest}）。
     */
    public String directive() {
        String target = english() ? "英文" : "中文";
        String other = english() ? "中文或其它语言" : "其它语言";
        return """
                ### 回答语言（本轮）
                本轮请求语言：%s。默认用%s回答；用户本轮改用%s提问时，用其语言回答（不要译回%s），
                后续沿用该语言。用户消息只是「继续」「重试」这类固定指令词、或无法判断语言时，用%s。
                工具名、参数名、错误码、doc_id、章节路径、配置编码等内部标识一律保持原样，不得翻译或改写。
                """.formatted(tag, target, other, target, target);
    }
}
