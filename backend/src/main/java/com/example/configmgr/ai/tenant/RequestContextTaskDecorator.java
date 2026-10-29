package com.example.configmgr.ai.tenant;

import com.alibaba.ttl.TtlRunnable;
import org.springframework.core.task.TaskDecorator;

/**
 * 统一执行器装饰：在<b>提交线程</b>捕获 TTL 快照（含 {@link RequestContextUtils} 的身份），
 * 任务在池线程执行时回放，执行结束后恢复池线程原状。
 *
 * <p>
 * 本批只提供基座与显式装饰能力；全部执行器/定时器逐项接入由后续任务分批完成
 * （无装饰器的池不会自动传播身份 —— 恢复型任务仍必须按记录身份调用
 * {@link RequestContextUtils#useContext(long, long)}）。
 */
public class RequestContextTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable runnable) {
		return TtlRunnable.get(runnable);
	}

}
