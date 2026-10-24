package com.example.configmgr.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * 语言协商：读 `Accept-Language`，**默认 zh-CN，且与 JVM 默认 locale 解耦**。
 *
 * <p>
 * 默认值就是今天的现实：不带该头的调用方（E2E / curl / 桩）拿到中文文案，与迁移前逐字一致；
 * 带英文范围（`en` / `en-US` / `en-GB`…）才取英文 messages。支持列表只两枚（区域变体经
 * `messages_zh_CN → messages_zh → messages` 链自然回落）。
 *
 * <p>
 * 不用 Spring 原装 `resolveLocale` 的理由：它对"头缺失/头畸形"的处理会经 `request.getLocale()`
 * 退到 **JVM 默认 locale**——CI 的 Ubuntu 是 en-US，本机是 zh-CN，同一份代码两地不同文案
 * （run 39613094682 的 5 红即此）。故这里显式解析：头缺失、头畸形、无可匹配语言，一律 zh-CN。
 *
 * <p>
 * <b>语言级回退（M-3，4B 评审）</b>：`Locale.lookup` 只认"语言 + 区变体"都在支持表里的范围，
 * 于是 `en` / `en-GB` 这类只给语言的客户端会回落中文。这里补一层按语言的归一
 * （`en`→en-US、`zh`→zh-CN），保证这一条解析链仍是**唯一取值点**：`ErrorBodyFactory.requestLocale()`
 * 与 `ReplyLanguage.current()` 都从它写入的 `LocaleContextHolder` 读，不另造第二源。
 */
@Configuration
public class WebI18nConfig {

    @Bean
    public LocaleResolver localeResolver() {
        return new ZhDefaultAcceptHeaderLocaleResolver();
    }

    /** 显式解析 `Accept-Language`；任何"解析不出结果"的情形都回落 zh-CN（不看 JVM 默认 locale）。 */
    static final class ZhDefaultAcceptHeaderLocaleResolver extends AcceptHeaderLocaleResolver {

        static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

        static final List<Locale> SUPPORTED_LOCALES = List.of(Locale.SIMPLIFIED_CHINESE, Locale.US);

        ZhDefaultAcceptHeaderLocaleResolver() {
            setDefaultLocale(DEFAULT_LOCALE);
            setSupportedLocales(SUPPORTED_LOCALES);
        }

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            String header = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
            if (header == null || header.isBlank()) {
                return DEFAULT_LOCALE;
            }
            List<Locale.LanguageRange> ranges;
            try {
                ranges = Locale.LanguageRange.parse(header);
            } catch (IllegalArgumentException malformed) {
                return DEFAULT_LOCALE;
            }
            Locale matched = Locale.lookup(ranges, SUPPORTED_LOCALES);
            if (matched != null) {
                return matched;
            }
            // M-3（4B 独立评审）：`Locale.lookup` 只认"语言 + 区变体"都落在支持表里的范围，
            // 因此 `en`、`en-GB`、`zh-Hans` 这类**语言级**范围会返回 null 并一律回落中文 ——
            // 直连 API 的英文客户端就拿到中文错误体与中文回答。这里补语言级回退：按范围声明的语言
            // 匹配支持表里的同语言 locale（`en`→en-US、`zh`→zh-CN）；没有同语言项则仍回落 zh-CN。
            // 口径仍是"单一取值点"：该 resolver 写入 `LocaleContextHolder`，`ErrorBodyFactory` 与
            // `ReplyLanguage` 都从那里读，不另造第二源。
            for (Locale.LanguageRange range : ranges) {
                Locale byLanguage = byLanguage(range.getRange());
                if (byLanguage != null) {
                    return byLanguage;
                }
            }
            return DEFAULT_LOCALE;
        }

        /** 语言级匹配（忽略区变体/脚本）：`en-GB` → en-US，`zh-TW` → zh-CN，`de-DE` → null。 */
        private static Locale byLanguage(String range) {
            if (range == null || range.isBlank() || "*".equals(range)) {
                return null;
            }
            String tag = range.startsWith("*-") ? "" : range;
            Locale declared;
            try {
                declared = Locale.forLanguageTag(tag);
            } catch (RuntimeException malformed) {
                return null;
            }
            String language = declared.getLanguage();
            if (language.isEmpty()) {
                return null;
            }
            for (Locale supported : SUPPORTED_LOCALES) {
                if (supported.getLanguage().equalsIgnoreCase(language)) {
                    return supported;
                }
            }
            return null;
        }
    }
}
