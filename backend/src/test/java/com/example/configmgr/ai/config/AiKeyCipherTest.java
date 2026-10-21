package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B1 / B2（加密往返 + AAD 绑定 + IV 随机 + 不泄密）与 B5 的加密侧（KEK 缺失 fail-closed）。
 *
 * <p>
 * <b>证红说明</b>
 * <ul>
 * <li>B1：把 {@code AiKeyCipher} 里的 {@code updateAAD} 去掉（或把 AAD 置空）⇒ "把密文搬到另一
 * provider/另一 keyRef"两段立刻变红（密文能被解出来）；</li>
 * <li>B2：把随机 IV 改成固定值 ⇒ "两次加密不同"变红；</li>
 * <li>B5 加密侧：去掉 {@code requireKek()} 的判空（例如缺 KEK 时用全零密钥兜底）⇒ 变红。</li>
 * </ul>
 *
 * <p>B5 的<b>端点</b>形态（{@code POST /api/ai/providers} 无 KEK ⇒ 400 {@code AI_SECRET_KEK_MISSING}）
 * 属于批② 的写路径 —— 批① 没有写端点，故这里锚的是加密设施自身的 fail-closed。
 */
class AiKeyCipherTest {

	private static final String PROVIDER_CODE = "deepseek";

	private static final String KEY_REF = "DB";

	/** 形态像真 key 的字符串（用于"密文/指纹不含明文"断言）。 */
	private static final String SECRET = "cipher-probe-value-0123456789abcdef";

	private final AiKeyCipher cipher = new AiKeyCipher(AiConfigTestFixture.KEK_A);

	// ── B1：加解密往返 + AAD 绑定 ────────────────────────────────────────────

	@Test
	void encryptsAndDecryptsWithAadBoundToProviderAndKeyRef() {
		String envelope = this.cipher.encrypt(SECRET, PROVIDER_CODE, KEY_REF);

		// 信封自描述：v1.<kid>.<iv>.<ct+tag>
		assertThat(envelope).matches(Pattern.compile("^v1\\.k1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"));
		assertThat(this.cipher.decrypt(envelope, PROVIDER_CODE, KEY_REF)).isEqualTo(SECRET);
	}

	@Test
	void ciphertextMovedToAnotherProviderRowCannotBeDecrypted() {
		String envelope = this.cipher.encrypt(SECRET, PROVIDER_CODE, KEY_REF);

		assertCipherException(() -> this.cipher.decrypt(envelope, "another-provider", KEY_REF),
				AiKeyCipher.CODE_KEY_UNREADABLE);
		// keyRef 也参与 AAD（DB → ENV 的搬运同样拒绝）
		assertCipherException(() -> this.cipher.decrypt(envelope, PROVIDER_CODE, "ENV"),
				AiKeyCipher.CODE_KEY_UNREADABLE);
	}

	// ── B2：IV 随机 + 密文/指纹不含明文 ──────────────────────────────────────

	@Test
	void eachEncryptionUsesAFreshIvSoCiphertextDiffers() {
		String first = this.cipher.encrypt(SECRET, PROVIDER_CODE, KEY_REF);
		String second = this.cipher.encrypt(SECRET, PROVIDER_CODE, KEY_REF);

		assertThat(first).isNotEqualTo(second);
		assertThat(first.split("\\.")[2]).as("IV 段每次不同").isNotEqualTo(second.split("\\.")[2]);
		// 两段密文都能解开（IV 不同不影响往返）
		assertThat(this.cipher.decrypt(first, PROVIDER_CODE, KEY_REF)).isEqualTo(SECRET);
		assertThat(this.cipher.decrypt(second, PROVIDER_CODE, KEY_REF)).isEqualTo(SECRET);
	}

	@Test
	void ciphertextAndFingerprintNeverContainThePlaintext() {
		String envelope = this.cipher.encrypt(SECRET, PROVIDER_CODE, KEY_REF);
		String fingerprint = this.cipher.fingerprint(SECRET);

		assertThat(envelope).doesNotContain(SECRET);
		assertThat(fingerprint).matches("[0-9a-f]{16}").doesNotContain(SECRET);
		// 指纹确定（同一 key 同一 KEK ⇒ 同一指纹，才能当运行时缓存键）
		assertThat(this.cipher.fingerprint(SECRET)).isEqualTo(fingerprint);
		// 不同 key ⇒ 不同指纹
		assertThat(this.cipher.fingerprint(SECRET + "x")).isNotEqualTo(fingerprint);
	}

	@Test
	void last4IsTheOnlyAllowedFragmentOfTheKey() {
		assertThat(AiKeyCipher.last4(SECRET)).isEqualTo("cdef");
		assertThat(AiKeyCipher.last4("abc")).isEqualTo("abc");
		assertThat(AiKeyCipher.last4("  ")).isNull();
	}

	// ── 换 KEK：解不开（不猜、不降级） ───────────────────────────────────────

	@Test
	void ciphertextFromAnotherKekIsUnreadableAndKekKidIsEnforced() {
		String envelope = new AiKeyCipher(AiConfigTestFixture.KEK_B).encrypt(SECRET, PROVIDER_CODE, KEY_REF);
		assertCipherException(() -> this.cipher.decrypt(envelope, PROVIDER_CODE, KEY_REF),
				AiKeyCipher.CODE_KEY_UNREADABLE);

		// kid 不认识（P1 单 kid：轮换前只有 k1 被接受）
		String foreignKid = this.cipher.encrypt(SECRET, PROVIDER_CODE, KEY_REF).replaceFirst("^v1\\.k1\\.", "v1.k9.");
		assertCipherException(() -> this.cipher.decrypt(foreignKid, PROVIDER_CODE, KEY_REF),
				AiKeyCipher.CODE_KEY_UNREADABLE);
	}

	// ── B5（加密侧）：KEK 缺失 fail-closed ───────────────────────────────────

	@Test
	void missingKekFailsClosedForEncryptFingerprintAndDecrypt() {
		AiKeyCipher noKek = new AiKeyCipher("");

		assertThat(noKek.isConfigured()).isFalse();
		assertCipherException(() -> noKek.encrypt(SECRET, PROVIDER_CODE, KEY_REF), AiKeyCipher.CODE_KEK_MISSING);
		assertCipherException(() -> noKek.fingerprint(SECRET), AiKeyCipher.CODE_KEK_MISSING);

		// 已有密文：没有 KEK 也解不开（同样是稳定 code，而不是抛 NPE 或返回明文）
		String envelope = this.cipher.encrypt(SECRET, PROVIDER_CODE, KEY_REF);
		assertCipherException(() -> noKek.decrypt(envelope, PROVIDER_CODE, KEY_REF), AiKeyCipher.CODE_KEK_MISSING);
	}

	@Test
	void malformedKekIsTreatedAsMissingInsteadOfSilentlyDerivingOne() {
		AiKeyCipher shortKek = new AiKeyCipher(AiConfigTestFixture.kek(0x2a).substring(0, 20));
		assertThat(shortKek.isConfigured()).isFalse();
		assertCipherException(() -> shortKek.encrypt(SECRET, PROVIDER_CODE, KEY_REF), AiKeyCipher.CODE_KEK_MISSING);
	}

	private static void assertCipherException(Runnable action, String expectedCode) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(AiKeyCipher.KeyCipherException.class,
				ex -> assertThat(ex.getCode()).isEqualTo(expectedCode));
	}

}
