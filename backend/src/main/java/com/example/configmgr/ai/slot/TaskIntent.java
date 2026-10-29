package com.example.configmgr.ai.slot;

import java.util.regex.Pattern;

/**
 * 用户话里的<b>任务链意图</b>（纯函数、无 Spring）：只有"主动新建"与"继续既有任务"两种，
 * 用来决定宿主表单能不能弹。
 *
 * <h2>为什么要它（而不是问模型）</h2>
 * 宿主表单是<b>确定性</b>的：类型未给时给单选、条件未给时给自由文本框 —— 这两个判断不能依赖模型
 * 这一轮的输出（模型可能直接写成文字反问）。于是"这一轮到底是不是一个主动任务链"必须由宿主自己
 * 从<b>用户原文</b>判定。判定口径刻意保守：
 * <ul>
 * <li><b>新建</b>：创建/新建/另建/再建/建一个/开一个/发起 +（至多 8 字）+ 任务/作业；
 * 问句（怎么/如何/为什么…）与否定（不要/别/不用）一律不算 —— 那是在<b>问规则</b>或<b>撤回</b>，
 * 不是在建任务；</li>
 * <li><b>继续</b>：继续/接着/往下走/下一步/恢复，或"这个/该/当前/现有任务"这类指向既有任务的说法。
 * "打开任务 7 看看"<b>不是</b>继续意图（普通浏览不得被自动打扰）。</li>
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

	/** 继续既有任务。 */
	private static final Pattern CONTINUE = Pattern.compile(
			"(继续|接着|往下走|下一步|恢复|接着做|继续做|继续完成|继续弄|接着弄)");

	/** 指向既有任务（"这个任务/当前导出/刚才那条任务"）。 */
	private static final Pattern CURRENT_TASK = Pattern.compile(
			"(这个|该|当前|现有|刚才那[个条]?|原来的?)\\s*(任务|导出|导入|向导|流程)");

	/** 问句/求助：在问规则、找文档，不是在建/继续任务。 */
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
	 * 这一句是否"继续既有任务"（宿主据此在该任务上补一次条件框；普通浏览不算）。
	 *
	 * <p>问句同样排除：{"这个任务},{"条件是什么"} 是在问，不是在推进。
	 */
	public static boolean isContinueTaskIntent(String text) {
		if (text == null || text.isBlank() || QUESTION.matcher(text).find()) {
			return false;
		}
		return CONTINUE.matcher(text).find() || CURRENT_TASK.matcher(text).find();
	}

}
