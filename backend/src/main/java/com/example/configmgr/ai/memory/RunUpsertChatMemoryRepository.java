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
 *
 * <h2>两个写面，分工固定</h2>
 * <ul>
 * <li>{@link #upsertRun}：<b>一轮自己的</b>历史（轮末/取消/失败收敛、记忆 advisor 的 before/after）
 *     —— 只动本 run 的块，别的块原样保留；</li>
 * <li>{@link #replaceWindow}：<b>压缩 / 记忆窗口</b>的跨 run 整窗改写（L1 占位、L2 摘要、L3 裁轮），
 *     以读到的 {@link MemoryWindowSnapshot} 为 CAS 基线一次落盘；基线变了就放弃（不重试）。</li>
 * </ul>
 * 两者混用会让"跨 run 的改写"被按 run 合并跳过（台账说压了、窗口没动），或让"本轮收敛"覆盖别的块，
 * 因此调用点不得互换。
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

	/**
	 * 读一次窗口快照（版本 + 清空代次 + 当时的消息）。
	 *
	 * <p>供<b>压缩 / 记忆窗口写回</b>用：它们要重写整份窗口（含别的 run 的块），必须拿"读到的那一份"
	 * 当 CAS 基线，才能在写入时发现"这期间有人改过窗口"。
	 */
	MemoryWindowSnapshot readWindow(String conversationId);

	/**
	 * <b>整窗替换</b>（只给压缩 / 记忆窗口写回用）。
	 *
	 * <p>与 {@link #upsertRun} 的区别：整窗改写的内容就是"压缩算出来的那一份"，不重读、不合并 ——
	 * 但<b>只能</b>在快照基线仍然成立时落盘：
	 * <ul>
	 * <li>版本与 {@code baseline} 一致 ⇒ 写入（staging + RENAME，失败安全同整窗写）；</li>
	 * <li>版本已变（别的写者落了新消息）或代次已变（会话被清空）⇒ <b>返回 {@code false}，不写、
	 *     不重试、不拿旧内容重算</b>；调用方记 WARN + 降级计数，下一轮重新压缩。</li>
	 * </ul>
	 *
	 * <p>为什么不能"整窗覆盖 + CAS 重试"：压缩的输入是<b>旧窗口</b>，重试仍是旧内容 ⇒ 一旦重试成功，
	 * 别的写者在这期间写入的新消息就被覆盖掉了。放弃是唯一不丢新消息的做法。
	 *
	 * @param conversationId 会话 id
	 * @param baseline       压缩计算时读到的快照（不得为 {@code null}）
	 * @param messages       压缩后的整份窗口（原样写入，不再裁剪）
	 * @return 是否真的落盘
	 */
	boolean replaceWindow(String conversationId, MemoryWindowSnapshot baseline, List<Message> messages);

}
