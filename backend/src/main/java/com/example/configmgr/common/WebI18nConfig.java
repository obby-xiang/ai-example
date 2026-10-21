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
 * 只有显式带 `en-US` 才取英文 messages。支持列表只两枚（区域变体经
 * `messages_zh_CN → messages_zh → messages` 链自然回落）。
 *
 * <p>
 * 不用 Spring 原装 `resolveLocale` 的理由：它对"头缺失/头畸形"的处理会经 `request.getLocale()`
 * 退到 **JVM 默认 locale**——CI 的 Ubuntu 是 en-US，本机是 zh-CN，同一份代码两地不同文案
 * （run 39613094682 的 5 红即此）。故这里显式解析：头缺失、头畸形、无可匹配语言，一律 zh-CN。
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
            return matched != null ? matched : DEFAULT_LOCALE;
        }
    }
}
