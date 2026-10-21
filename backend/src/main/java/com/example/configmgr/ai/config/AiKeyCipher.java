package com.example.configmgr.ai.config;

import com.example.configmgr.common.ErrorCodes;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * key 加密（模型配置专项 §3.2）：AES-256-GCM，JDK 内置，<b>零新依赖</b>。
 *
 * <h2>信封</h2>
 * {@code v1.<kid>.<iv_b64url>.<ct_with_tag_b64url>} —— 单列自描述，将来轮换 KEK 时按 {@code kid}
 * 选钥匙，不必改表结构。IV 每次加密随机 12 字节；tag 128 bit；密文与 tag 是同一段字节
 * （GCM 的 {@code doFinal} 输出），所以只有一段 base64url。
 *
 * <h2>AAD 绑定行</h2>
 * {@code AAD = provider.code + "|" + keyRef}：把密文从一行搬到另一行（改 code 或改 keyRef）后
 * 解密必然失败，防"把 A 的密文贴到 B 上"这种越权搬运。
 *
 * <h2>KEK 来源与 fail-closed</h2>
 * 环境变量 {@code AI_SECRET_KEK}（base64 的 32 字节；可选 {@code AI_SECRET_KEK_KID} 标识版本，
 * 缺省 {@value #DEFAULT_KID}）。<b>KEK 缺失/格式不对 ⇒ 不降级</b>：加密/指纹直接抛
 * {@code AI_SECRET_KEK_MISSING}，解密抛 {@code KEY_UNREADABLE} —— 没有明文降级、没有默认 KEK、
 * 没有"自动生成后落盘"。进程启动<b>不</b>因缺 KEK 失败（批① 的 env 种子根本不需要 KEK）。
 *
 * <h2>指纹为什么是 HMAC 而不是 SHA-256</h2>
 * 指纹是运行时缓存键的一部分；裸 {@code SHA-256(key)} 在低熵 key 上等于把"无 KEK 也能做离线
 * 字典"的口子打开（设计稿 §10-8）。故用 KEK 作 HMAC 密钥、取前 16 hex。
 *
 * <h2>已知限制（复审 N2 的其余半条，登记不改）</h2>
 * KEK/明文的 {@code byte[]} 在方法返回后<b>不主动清零</b>：JDK 无法阻止 JVM 内部（GC、JIT、
 * 拷贝构造）把同一份字节复制到别处，{@code Arrays.fill} 只会制造"已擦除"的错觉；真正的
 * 内存擦除需要 {@code ByteBuffer}/{@code Destroyable} 级别的全程约定，超出本专项范围。
 * 现有边界改为：明文只在 {@code encrypt}/{@code decrypt} 的局部变量里存在、绝不进日志/异常消息。
 */
@Component
public class AiKeyCipher {

    /** KEK 的环境变量名（base64 的 32 字节）。 */
    public static final String KEK_ENV = "AI_SECRET_KEK";

    /** KEK 版本标识的环境变量名（P1 单 kid；轮换挂账）。 */
    public static final String KEK_KID_ENV = "AI_SECRET_KEK_KID";

    /** 缺省 kid（KEK 轮换前唯一支持的版本标识）。 */
    public static final String DEFAULT_KID = "k1";

    /** 信封格式版本前缀。 */
    public static final String ENVELOPE_VERSION = "v1";

    /** KEK 缺失/不可用时的稳定错误码（保存 key 的 400 码，批② 消费）。 */
    public static final String CODE_KEK_MISSING = ErrorCodes.AI_SECRET_KEK_MISSING;

    /**
     * 待加密的 key 为空/空白（批① 复审登记 M6，批② 与 KEK 缺失分离）。
     *
     * <p>
     * 旧实现把 {@code encrypt(null)} 也报成 {@link #CODE_KEK_MISSING}：调用方传了个空值，却让运维
     * 去查环境变量。现在它是独立的 400 码（BAD_REQUEST 语义）—— "请求本身不合法" 与 "部署没配好"
     * 是两种修法，排障方向不能混。
     */
    public static final String CODE_KEY_EMPTY = ErrorCodes.KEY_EMPTY;

    /** 密文解不开（KEK 换了/错了、kid 不认识、AAD 不匹配）的稳定错误码。 */
    public static final String CODE_KEY_UNREADABLE = ErrorCodes.KEY_UNREADABLE;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String HMAC = "HmacSHA256";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final int KEK_BYTES = 32;

    private static final int FINGERPRINT_HEX_CHARS = 16;

    /** kid 的字符集与长度约束：只允许进信封点分段而不破坏分段完整性的字符（复审 N2 半条）。 */
    private static final java.util.regex.Pattern KID_PATTERN = java.util.regex.Pattern
        .compile("[A-Za-z0-9_-]{1,16}");

    private final String kekBase64;

    private final String kid;

    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AiKeyCipher(Environment environment) {
        this(environment.getProperty(KEK_ENV, ""), environment.getProperty(KEK_KID_ENV, DEFAULT_KID));
    }

    /** 单测/工具入口：直接给 base64 KEK（空串 = 未配置）。 */
    public AiKeyCipher(String kekBase64) {
        this(kekBase64, DEFAULT_KID);
    }

    public AiKeyCipher(String kekBase64, String kid) {
        this.kekBase64 = kekBase64;
        this.kid = normalizeKid(kid);
    }

    /**
     * kid 归一化（复审 N2 半条）：只接受 {@code [A-Za-z0-9_-]{1,16}}。
     *
     * <p>
     * 为什么必须校验：信封是 {@code v1.<kid>.<iv>.<ct>} 的<b>点分段</b>格式 —— kid 里混进
     * {@code "."} 会把段数变成 5，解密侧按 {@code split("\\.")} 判"格式不合法"，一条本来完好的密文
     * 就被自己的 kid 打成 {@code KEY_UNREADABLE}；而 {@code "\n"} 等控制字符还会污染落库/日志。
     * 非法 kid <b>不静默改写</b>：直接回落到 {@link #DEFAULT_KID}（P1 只有一个 kid，回落不会造成
     * 密钥混淆），并由 {@link #isKidValid(String)} 的返回值给调用方留复核口。
     */
    private static String normalizeKid(String kid) {
        if (!StringUtils.hasText(kid)) {
            return DEFAULT_KID;
        }
        String trimmed = kid.trim();
        return isKidValid(trimmed) ? trimmed : DEFAULT_KID;
    }

    /** kid 是否满足信封分段完整性约束（{@code [A-Za-z0-9_-]{1,16}}）。 */
    public static boolean isKidValid(String kid) {
        return kid != null && KID_PATTERN.matcher(kid).matches();
    }

    /** KEK 是否可用（能解出 32 字节）。<b>不</b>暴露 KEK 本身。 */
    public boolean isConfigured() {
        return resolveKekOrNull() != null;
    }

    public String kid() {
        return this.kid;
    }

    /**
     * 加密并返回信封串。
     *
     * @param plaintext key 明文（只在本方法内存在，绝不落日志）
     * @param providerCode 行标识（AAD 第一段）
     * @param keyRef key 位置（AAD 第二段，{@code DB}/{@code ENV}/{@code NONE}）
     * @throws KeyCipherException {@code KEY_EMPTY}（明文为空：入参错误，先于 KEK 判定）或
     * {@code AI_SECRET_KEK_MISSING}（KEK 缺失/格式不对）
     */
    public String encrypt(String plaintext, String providerCode, String keyRef) {
        // M6：空明文先判 —— 它是"调用方传错了"，不是"部署没配 KEK"（两者的修法完全不同）
        if (!StringUtils.hasText(plaintext)) {
            throw new KeyCipherException(CODE_KEY_EMPTY, "待加密的 key 为空（该项必填；如需清空请用 clearKey）");
        }
        byte[] kek = requireKek();
        try {
            byte[] iv = new byte[IV_BYTES];
            this.random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(providerCode, keyRef));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return ENVELOPE_VERSION + "." + this.kid + "." + encoder.encodeToString(iv) + "."
                    + encoder.encodeToString(sealed);
        }
        catch (Exception ex) {
            // 不回显任何密文/明文片段：异常消息只描述失败类别
            throw new KeyCipherException(CODE_KEY_UNREADABLE, "加密失败（AES/GCM 不可用）：" + ex.getClass().getSimpleName());
        }
    }

    /**
     * 解密信封串。
     *
     * @throws KeyCipherException {@code KEY_UNREADABLE}（信封损坏 / AAD 不匹配 / KEK 不同 / kid 不认识）
     * 或 {@code AI_SECRET_KEK_MISSING}（本进程没有 KEK）
     */
    public String decrypt(String envelope, String providerCode, String keyRef) {
        byte[] kek = requireKek();
        String[] parts = envelope == null ? new String[0] : envelope.split("\\.");
        if (parts.length != 4 || !ENVELOPE_VERSION.equals(parts[0])) {
            throw new KeyCipherException(CODE_KEY_UNREADABLE, "密文信封格式不合法");
        }
        if (!this.kid.equals(parts[1])) {
            // P1 单 kid：kid 不认识 = 这把钥匙不在本进程手里，绝不猜
            throw new KeyCipherException(CODE_KEY_UNREADABLE, "密文 kid 与本进程 KEK 不匹配（需要重启换 KEK 或重新录入 key）");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[2]);
            byte[] sealed = decoder.decode(parts[3]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(providerCode, keyRef));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        }
        catch (Exception ex) {
            throw new KeyCipherException(CODE_KEY_UNREADABLE,
                    "密文无法解密（KEK 变更、kid 不符或密文被搬到别的 provider 行）：" + ex.getClass().getSimpleName());
        }
    }

    /**
     * key 指纹：{@code HMAC-SHA256(KEK, key)} 的前 {@value #FINGERPRINT_HEX_CHARS} 个 hex 字符。
     *
     * @throws KeyCipherException {@code AI_SECRET_KEK_MISSING}
     */
    public String fingerprint(String plaintext) {
        byte[] kek = requireKek();
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(kek, HMAC));
            byte[] digest = mac.doFinal(plaintext == null ? new byte[0] : plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, FINGERPRINT_HEX_CHARS);
        }
        catch (Exception ex) {
            throw new KeyCipherException(CODE_KEK_MISSING, "无法计算 key 指纹：" + ex.getClass().getSimpleName());
        }
    }

    /** key 末 4 位（唯一允许出库/出口的 key 片段；短于 4 位原样返回）。 */
    public static String last4(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private byte[] requireKek() {
        byte[] kek = resolveKekOrNull();
        if (kek == null) {
            throw new KeyCipherException(CODE_KEK_MISSING,
                    "未配置可用的 AI_SECRET_KEK（base64 的 32 字节）：key 的加密/解密一律 fail-closed，不做明文降级");
        }
        return kek;
    }

    private byte[] resolveKekOrNull() {
        if (!StringUtils.hasText(this.kekBase64)) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(this.kekBase64.trim());
            return decoded.length == KEK_BYTES ? decoded : null;
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static byte[] aad(String providerCode, String keyRef) {
        return (String.valueOf(providerCode) + "|" + String.valueOf(keyRef)).getBytes(StandardCharsets.UTF_8);
    }

    /** 加密面唯一异常类型：只带稳定 code + 不含任何密钥材料的消息。 */
    public static class KeyCipherException extends RuntimeException {

        private final String code;

        public KeyCipherException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return this.code;
        }
    }
}
