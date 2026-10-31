package com.example.configmgr.ai.memory;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * {@link ChatMemory} 的<b>按轮写入口</b>：读写时机/窗口语义仍是官方的，但 {@code add} 不再
 * 走"读整窗 → 追加 → 整窗覆盖"，而是把增量交给 {@link RunUpsertChatMemoryRepository#upsertRun}
 * 与其它写者一起按轮合并。
 *
 * <h2>为什么必须换掉 add</h2>
 * 官方 {@code MessageWindowChatMemory#add} 是"读整窗 + 合并 + {@code saveAll(整窗)}"。在
 * "挂起轮 A 尚可续跑、用户又开了新轮 B"的场景里，B 的 advisor 写入拿着 A 写之前的旧窗 + 自己两条
 * 消息整窗覆盖 ⇒ A 的整轮从 {@code /history}（唯一读源）里消失；反过来 A 续跑的收敛会把 B 删掉。
 * 官方 add 的读-改-写在多实例下也没有 CAS：两个实例同时写，后到的整窗覆盖先到的。
 *
 * <p>本装饰器只替换写路径：{@code get}/{@code clear} 原样委托（读是只读的；清空是显式整会话删除），
 * 系统提示置顶/窗口裁剪仍由官方窗口实现与 {@link RunMemoryMerge} 共同保证。
 */
public interface RunScopedChatMemory extends ChatMemory {

	/**
	 * 把消息写进<b>指定轮</b>的块（追加），其余 run 的块保持不变。
	 *
	 * @param conversationId 会话 id
	 * @param turn           本轮归属（advisor 从本轮执行信封/日志锚点解析）
	 * @param messages       增量消息
	 */
	void add(String conversationId, MemoryTurn turn, List<Message> messages);

}
