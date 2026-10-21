package com.example.configmgr.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * 语言协商：读 `Accept-Language`，**默认 zh-CN**。
 *
 * <p>
 * 默认值就是今天的现实：不带该头的调用方（E2E / curl / 桩）拿到中文文案，与迁移前逐字一致；
 * 只有显式带 `en-US` 才取英文 messages。支持列表只两枚（区域变体经
 * `messages_zh_CN → messages_zh → messages` 链自然回落）。
 */
@Configuration
public class WebI18nConfig {

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.US));
        return resolver;
    }
}
