package com.example.configmgr.ai.gate;

import com.example.configmgr.ai.config.AiProperties;
import com.example.configmgr.ai.run.LeaseKeys;
import com.example.configmgr.ai.run.LeaseService;
import com.example.configmgr.ai.run.LeaseService.AcquireOutcome;
import com.example.configmgr.ai.run.LeaseService.AcquireResult;
import com.example.configmgr.ai.run.LeaseService.Lease;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 目标资源的<b>互斥锁</b>（B23L1 批③ 的 L1 面，设计稿 §2.3）——{@link LeaseService} 之上的一层薄包装。
 *
 * <h2>锁什么</h2>
 * 两个键、固定顺序、同一 owner：
 * <ol>
 * <li>{@code ai:lock:task:<taskId>}（{@link LeaseKeys#task(long)}）—— 同一任务上的破坏性动作互斥；</li>
 * <li>{@code ai:lock:job:<taskId>:<JOBTYPE>}（{@link LeaseKeys#job(long, String)}）—— 同一任务的同类作业
 * 互斥；不同类型的作业（导出 vs 发布）<b>不该</b>互相阻塞，所以第二把锁带作业类型。</li>
 * </ol>
 * <b>顺序固定</b>（先任务后作业）：两个调用方若一个先抢任务、另一个先抢作业，就会出现
 * "各持一把、互等对方"的活锁。顺序一致 + 抢不到即放弃（<b>不等待、不轮询</b>）让这个问题在结构上不存在。
 *
 * <h2>三态与降级</h2>
 * <ul>
 * <li>{@link LockOutcome#ACQUIRED}：两把都拿到（或同 owner 重入）⇒ 执行；</li>
 * <li>{@link LockOutcome#HELD_ELSEWHERE}：任一把被别人持有 ⇒ 调用方按 {@code blocked/RESOURCE_LOCKED}
 * 结算，<b>不抛异常不断流</b>，也不占 {@code ai:claim:}；已拿到的那把（任务级）会就地归还 ——
 * 否则一次被拒的调用会白占任务级锁一个 TTL；</li>
 * <li>{@link LockOutcome#UNAVAILABLE}：租约设施不可用（Redis 抖动）⇒ <b>放行</b> + WARN。与
 * {@link LeaseService} 的降级口径一致：把"不知道有没有人持有"报成"有人持有"会让一次抖动
 * 变成全线拒绝执行；</li>
 * <li>{@link LockOutcome#DISABLED}：{@code app.ai.lock.enabled=false} 或调用方没给可锁的目标
 * （无 taskId / 非作业发起工具）⇒ 整段跳过（零 Redis 往返）。</li>
 * </ul>
 *
 * <h2>释放口径：谁创建谁释放</h2>
 * 只有 {@link AcquireResult#created()} 为真的那一把才进 {@link LockHandle#created()}，关闭时才 release：
 * 同 owner 重入拿到的"我也持有"没有释放权（放掉会把创建者的锁删了，对方的互斥随之静默失效）。
 * <b>本层不续租</b>（B23L1 批③b 复审 I-1）：{@code resource-ttl}（默认 60s）是<b>临界区上界</b>，
 * 不是"可以一直续下去"的软约束 —— 动作若真超过上界，宁可让锁按 TTL 自动放开（别的实例可以接手），
 * 也不让一把没有旁观者的锁被持有者无限续租成"事实永锁"。
 */
@Slf4j
@Component
public class ToolResourceLocks {

	/** 抢锁结论（见类注"三态与降级"）。 */
	public enum LockOutcome {

		/** 拿到（含同 owner 重入）。 */
		ACQUIRED,

		/** 被别人持有：调用方按 blocked/RESOURCE_LOCKED 结算，不执行。 */
		HELD_ELSEWHERE,

		/** 设施不可用：放行（按进程内互斥继续），留 WARN。 */
		UNAVAILABLE,

		/** 未启用 / 无可锁目标：整段跳过。 */
		DISABLED

	}

	/**
	 * 一次抢锁的句柄（{@code try-with-resources} 或 finally 关闭）。
	 *
	 * @param outcome 结论
	 * @param created 本次<b>创建</b>的租约（只有它们有释放权；重入的那把不在其中）
	 * @param facility 归还时用的设施（{@code DISABLED}/{@code UNAVAILABLE} 为 null）
	 */
	public record LockHandle(LockOutcome outcome, List<Lease> created, ToolResourceLocks facility)
			implements AutoCloseable {

		/** 无需释放的空句柄。 */
		public static LockHandle none(LockOutcome outcome) {
			return new LockHandle(outcome, List.of(), null);
		}

		/** 是否被别人持有（调用方据此走 blocked）。 */
		public boolean heldElsewhere() {
			return this.outcome == LockOutcome.HELD_ELSEWHERE;
		}

		@Override
		public void close() {
			if (this.facility != null) {
				this.facility.release(this.created);
			}
		}

	}

	private final LeaseService leaseService;

	private final AiProperties properties;

	@Autowired
	public ToolResourceLocks(LeaseService leaseService, AiProperties properties) {
		this.leaseService = leaseService;
		this.properties = properties;
	}

	/** 是否启用资源互斥（{@code app.ai.lock.enabled}）。 */
	public boolean enabled() {
		return this.properties.getLock().isEnabled();
	}

	/** 该工具是否走资源互斥（四个作业发起工具：它们以 taskId 定位一个可锁的业务对象）。 */
	public static boolean supports(String toolName) {
		return ToolTargetResolver.jobTypeOf(toolName) != null;
	}

	/**
	 * 抢任务级 + 作业级两把锁（固定顺序）。
	 *
	 * @param taskId 目标任务（&gt; 0；调用方已由 {@link ToolTargetResolver} 校验过）
	 * @param jobType 作业类型（{@code EXPORT}/{@code PRECHECK}/{@code IMPORT}/{@code PUBLISH}）
	 * @param owner 持有者（{@code instanceId:runId:toolCallId}）—— 同一实例内的不同调用也是不同 owner
	 */
	public LockHandle acquire(long taskId, String jobType, String owner) {
		if (!enabled()) {
			return LockHandle.none(LockOutcome.DISABLED);
		}
		if (this.leaseService == null) {
			log.warn("资源锁设施未装配，按不可用放行 taskId={} job={} owner={}", taskId, jobType, owner);
			return LockHandle.none(LockOutcome.UNAVAILABLE);
		}
		Duration ttl = this.properties.getLease().getResourceTtl();
		List<Lease> created = new ArrayList<>(2);
		boolean degraded = false;
		// ① 任务级
		AcquireResult taskLock = acquireQuietly(LeaseKeys.task(taskId), ttl, owner);
		if (taskLock != null && taskLock.outcome() == AcquireOutcome.HELD_ELSEWHERE) {
			return LockHandle.none(LockOutcome.HELD_ELSEWHERE);
		}
		if (taskLock == null || taskLock.outcome() == AcquireOutcome.UNAVAILABLE) {
			degraded = true;
		}
		else if (taskLock.created()) {
			created.add(taskLock.lease());
		}
		// ② 作业级（同一任务上不同类型的作业可以并行）
		if (jobType != null && !jobType.isBlank()) {
			AcquireResult jobLock = acquireQuietly(LeaseKeys.job(taskId, jobType), ttl, owner);
			if (jobLock != null && jobLock.outcome() == AcquireOutcome.HELD_ELSEWHERE) {
				// 被拒就不该白占任务级锁：就地归还本次创建的那把
				release(created);
				return LockHandle.none(LockOutcome.HELD_ELSEWHERE);
			}
			if (jobLock == null || jobLock.outcome() == AcquireOutcome.UNAVAILABLE) {
				degraded = true;
			}
			else if (jobLock.created()) {
				created.add(jobLock.lease());
			}
		}
		if (degraded) {
			// 显式 WARN：把"锁设施坏了"与"锁没生效"在日志里分开（关掉开关走的是 DISABLED，不打这条）
			log.warn("资源锁设施不可用，按放行处理 taskId={} job={} owner={}（Redis 抖动不阻断业务动作）", taskId,
					jobType, owner);
			return new LockHandle(LockOutcome.UNAVAILABLE, List.copyOf(created), this);
		}
		return new LockHandle(LockOutcome.ACQUIRED, List.copyOf(created), this);
	}

	/**
	 * 目标资源上是否还挂着锁（<b>只读</b>；对账器 {@code ToolUnknownReconciler} 的第三跳）。
	 *
	 * <p>不判 owner：对账问的是"这个任务上还有没有动作在进行"，而不是"是不是我"。
	 */
	public boolean isHeld(long taskId, String jobType) {
		if (!enabled() || this.leaseService == null || taskId <= 0L) {
			return false;
		}
		try {
			if (this.leaseService.isHeld(LeaseKeys.task(taskId))) {
				return true;
			}
			return jobType != null && !jobType.isBlank() && this.leaseService.isHeld(LeaseKeys.job(taskId, jobType));
		}
		catch (RuntimeException ex) {
			log.debug("资源锁只读探针失败 taskId={} job={}：{}", taskId, jobType, ex.getMessage());
			return false;
		}
	}

	/** 归还本句柄创建的锁（反向：先作业级、后任务级；只放自己创建的那一任）。 */
	void release(List<Lease> created) {
		if (created == null || created.isEmpty() || this.leaseService == null) {
			return;
		}
		for (int i = created.size() - 1; i >= 0; i--) {
			Lease lease = created.get(i);
			try {
				if (!this.leaseService.release(lease)) {
					log.debug("资源锁释放未命中（已过期/已换主）key={} epoch={}", lease.key(), lease.epoch());
				}
			}
			catch (RuntimeException ex) {
				log.warn("资源锁释放失败 key={}（TTL 到期后自清）：{}", lease.key(), ex.getMessage());
			}
		}
	}

	/** 抢一把；设施异常一律折算成 {@code UNAVAILABLE}（调用方按降级放行）。 */
	private AcquireResult acquireQuietly(String key, Duration ttl, String owner) {
		try {
			return this.leaseService.acquire(key, ttl, owner);
		}
		catch (RuntimeException ex) {
			log.warn("资源锁抢用降级 key={} owner={}（Redis 不可用 ⇒ 放行）：{}", key, owner, ex.getMessage());
			return null;
		}
	}

}
