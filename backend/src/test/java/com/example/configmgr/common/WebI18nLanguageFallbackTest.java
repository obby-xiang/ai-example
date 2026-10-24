package com.example.configmgr.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M-3（4B 独立评审）：`Accept-Language` 的**语言级回退** —— 只给语言、不给受支持区变体的客户端
 * （`en`、`en-GB`、`zh`、`zh-Hans`…）也要落到受支持 locale 上，而不是一律回落中文。
 *
 * <h2>为什么这算 4B 的一部分</h2>
 * 4B 的语言来源是"UI locale → `Accept-Language` → resolver → `LocaleContextHolder`"这一条链
 * （`ErrorBodyFactory.requestLocale()` 与 `ReplyLanguage.current()` 都取这条链）。修复前
 * `Locale.lookup` 对语言级范围返回 {@code null} ⇒ 直连 API 的英文客户端（curl、脚本、非本仓前端）
 * 会拿到中文错误体与中文回答。语言映射必须只此一处，不能在前端或 `ReplyLanguage` 另造第二源。
 *
 * <p><b>证红方式</b>：把解析器改回只 `Locale.lookup` ⇒ `en`/`en-GB` 两例红（回落 zh-CN）。
 */
class WebI18nLanguageFallbackTest {

	private final LocaleResolver resolver = new WebI18nConfig().localeResolver();

	@Test
	void exactSupportedLocalesAreUnchanged() {
		assertEquals(Locale.US, resolve("en-US"));
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh-CN"));
	}

	@Test
	void languageOnlyEnglishHeaderMapsToEnglishUnitedStates() {
		assertEquals(Locale.US, resolve("en"));
	}

	@Test
	void regionalEnglishHeaderMapsToEnglishUnitedStates() {
		assertEquals(Locale.US, resolve("en-GB"));
		assertEquals(Locale.US, resolve("en-AU"));
		// q 权重形态同样按语言级回退
		assertEquals(Locale.US, resolve("en-GB,en;q=0.9"));
	}

	@Test
	void languageOnlyAndRegionalChineseMapToSimplifiedChinese() {
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh"));
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh-Hans"));
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("zh-TW"));
	}

	@Test
	void missingHeaderStaysChinese() {
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve(null));
	}

	@Test
	void malformedBlankAndUnsupportedHeadersStayChinese() {
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("en_US;;q=x"));
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve(" "));
		assertEquals(Locale.SIMPLIFIED_CHINESE, resolve("de-DE"));
	}

	private Locale resolve(String header) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (header != null) {
			request.addHeader("Accept-Language", header);
		}
		return this.resolver.resolveLocale(request);
	}
}
