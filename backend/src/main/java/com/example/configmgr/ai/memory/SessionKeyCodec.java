package com.example.configmgr.ai.memory;

/**
 * 会话域 Redis 键的<b>会话 id 转义</b>唯一实现（B2）。
 *
 * <h2>为什么必须共用</h2>
 * {@code chat:mem:*}/{@code chat:mem:staging:*}/{@code ai:tx:*} 都把会话 id 放进 Redis hash tag
 * {@code {<escapedId>}}：同会话的多把键要落在同一个 slot（Cluster 下 staging→final 的 RENAME、
 * 以及同一会话的 transcript/记忆共槽）。如果记忆与 transcript 各自实现一份转义，任何一次
 * 字符集/顺序漂移都会让两套键<b>指向不同 slot 或不同会话</b> —— 租户隔离与销毁精度一起失守。
 *
 * <p>转义规则（逐字冻结）：先 {@code %} → {@code %25}，再 {@code {} → {@code %7B}、
 * {@code }} → {@code %7D}。顺序不能反：先把 {@code %} 变成 {@code %25}，后续替换产生的
 * {@code %} 才不会被二次转义。转义后 id 不含花括号 ⇒ 作为 hash tag 内容时非空且无碰撞。
 */
public final class SessionKeyCodec {

	private SessionKeyCodec() {
	}

	/** 非空白会话 id（空白会产出空 hash tag，Cluster 下 staging/final 不同槽）。 */
	public static String requireId(String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			throw new IllegalArgumentException("conversationId must not be blank");
		}
		return conversationId;
	}

	/** 无碰撞转义（规则见类注；同一份输入永远得到同一份输出）。 */
	public static String escapedId(String conversationId) {
		return requireId(conversationId).replace("%", "%25").replace("{", "%7B").replace("}", "%7D");
	}

	/** Redis hash tag 片段：{@code {<escapedId>}}。 */
	public static String hashTag(String conversationId) {
		return "{" + escapedId(conversationId) + "}";
	}

}
