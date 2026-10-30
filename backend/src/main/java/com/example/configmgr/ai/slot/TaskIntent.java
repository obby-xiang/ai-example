package com.example.configmgr.ai.slot;

import com.example.configmgr.ai.history.SyntheticMessages;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户话里的<b>任务链意图</b>（纯函数、无 Spring）：只判"主动新建任务"这一种，
 * 用来决定宿主任务类型表单能不能弹。
 *
 * <h2>为什么要它（而不是问模型）</h2>
 * 宿主类型表单是<b>确定性</b>的：类型未给时给单选 —— 这个判断不能依赖模型这一轮的输出
 * （模型可能直接写成文字反问）。于是"这一轮到底是不是一个主动新建任务链"必须由宿主自己
 * 从<b>用户原文</b>判定。判定口径刻意保守：
 * <ul>
 * <li><b>新建</b>：创建/新建/另建/再建/建一个/开一个/发起 +（至多 8 字）+ 任务/作业；
 * 问句（怎么/如何/为什么…）与否定（不要/别/不用）一律不算 —— 那是在<b>问规则</b>或<b>撤回</b>，
 * 不是在建任务。</li>
 * </ul>
 *
 * <p>这只是"能不能弹"的一道门，不是授权：真正能不能推进仍由步骤守卫、槽位放行与工具 scope 决定。
 */
public final class TaskIntent {

	/** 主动新建：动词 +（短修饰）+ 任务/作业。 */
	private static final Pattern NEW_TASK = Pattern.compile(
			"(创建|新建|另建|再建|建一个|建个|开一个|开个|发起)[^。；;！!？?\\n]{0,8}?(任务|作业)");

	/** 否定/撤回：不是新建意图（"不要创建任务"/"别新建了"）。 */
	private static final Pattern NEGATED_CREATE = Pattern.compile(
			"(不要|不用|不需要|无需|别|请勿|取消)\\s*(再)?\\s*(创建|新建|另建|再建|发起)");

	/** 问句/求助：在问规则、找文档，不是在建任务。 */
	private static final Pattern QUESTION = Pattern.compile(
			"(怎么|如何|为什么|为何|是什么|什么是|说明一下|介绍一下|规则|文档|教程)");

	private TaskIntent() {
	}

	/** 这一句是否"主动新建任务"（类型未知时宿主据此先给类型单选）。 */
	public static boolean isNewTaskIntent(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		if (QUESTION.matcher(text).find() || NEGATED_CREATE.matcher(text).find()) {
			return false;
		}
		return NEW_TASK.matcher(text).find();
	}

	/**
	 * 这一句<b>明确</b>给出的任务类型（{@code EXPORT}/{@code IMPORT}，说不清 = {@code null}）。
	 *
	 * <p>与槽位解析同一个实现（{@link TaskSlotParser} 的导出/导入判定）：两处各写一套正则必然漂移
	 * ——"槽位认了导出、宿主还以为类型未知"就会多弹一张表。否定（"不要导出…"）按没提处理。
	 */
	public static String explicitTaskType(String text) {
		return TaskSlotParser.explicitTaskType(text);
	}

	/**
	 * 历史里最近一条<b>真实用户</b>原文（跳过合成消息：压缩摘要 / 步数或预算收尾提示）。
	 *
	 * <h2>为什么由本类持有</h2>
	 * "用户这一轮到底说了什么"由交棒前的槽位重算（{@code ResumeService}）读取。判据只留这一份 ——
	 * 某处忘了跳合成消息，症状是"最后一句话变成了系统提示"（意图凭空消失/旧选择复活）。
	 *
	 * <p>与 {@link SyntheticMessages#isSynthetic} 同一口径（同一判据，两个消费方同源）。
	 */
	public static String lastRealUserText(List<Message> history) {
		if (history == null) {
			return null;
		}
		for (int i = history.size() - 1; i >= 0; i--) {
			Message message = history.get(i);
			if (message instanceof UserMessage user && !SyntheticMessages.isSynthetic(message)) {
				return user.getText();
			}
		}
		return null;
	}

}
