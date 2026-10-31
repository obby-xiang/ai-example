package com.example.configmgr.ai.memory;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

/**
 * 记忆窗口的<b>按轮 UPSERT 写入口</b>（{@link ChatMemoryRepository} 的扩展面）。
 *
 * <p>会话只有一份窗口，但同一会话可以同时有多条 run 在写（挂起轮续跑 + 新开一轮）。官方
 * {@link ChatMemoryRepository#saveAll} 的语义是"整窗替换"，用它写"我这一个 run 的历史"就会把
 * 另一个 run 的整轮删掉。本接口把写入单位改成<b>run 块</b>：
 * <ul>
 * <li>{@code replaceRunBlock=true}：整份历史写回（轮末收敛 / 压缩写回），替换<b>本 run 的块</b>；</li>
 * <li>{@code replaceRunBlock=false}：增量写（记忆 advisor 的 before/after），追加到本 run 的块尾。</li>
 * </ul>
 * 其余 run 的块原样保留，块间按 {@link MemoryTurn#turnStartedAtMs()} 有序；合并结果按
 * {@code maxMessages} 裁剪且不拆 tool 配对。所有写者（advisor / 人工收敛 / 压缩写回）都必须走这里，
 * 且读-改-写必须在同一个会话级 CAS 里完成（多实例并发写不得互相覆盖）。
 */
public interface RunUpsertChatMemoryRepository extends ChatMemoryRepository {

	/**
	 * 按轮合并写回。
	 *
	 * @param conversationId 会话 id
	 * @param turn           本轮归属（runId + 起点）
	 * @param messages       本 run 的历史或增量消息（不得为 {@code null}）
	 * @param maxMessages    合并后的窗口条数上限（{@code <=0} = 不限）
	 * @param replaceRunBlock {@code true} = 用 {@code messages} 替换本 run 的块；{@code false} = 追加
	 * @return 本次是否真的写入了窗口（CAS 落空且重试用尽时为 {@code false}）
	 */
	boolean upsertRun(String conversationId, MemoryTurn turn, List<Message> messages, int maxMessages,
			boolean replaceRunBlock);

}
