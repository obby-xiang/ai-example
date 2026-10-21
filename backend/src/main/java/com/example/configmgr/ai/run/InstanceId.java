package com.example.configmgr.ai.run;

import java.net.InetAddress;
import java.util.UUID;

/**
 * 本进程的实例标识（{@code instance-<host>-pid<pid>-<随机后缀>}）——<b>进程内唯一值</b>。
 *
 * <p>为什么要有这个类：实例标识此前由 {@link RunStore} 在构造时现算，而租约设施
 * （{@link LeaseService}）也需要"我是谁"才能判可重入/占用拒。两处各算一次就会得到两个不同的串：
 * 认领值（{@code <epoch>|<instanceId>}）、租约 owner、台账 {@code executedBy} 三者的"自己"
 * 判据随即分裂（跨实例场景下表现为"自己写的记录读回来不认识"）。取值口径统一到本类是
 * B23L1 批②a 的收口点，语义与 {@code RunStore.instanceId()} 的原有口径完全一致。
 */
public final class InstanceId {

	private static final String VALUE = "instance-" + host() + "-pid" + ProcessHandle.current().pid() + "-"
			+ UUID.randomUUID().toString().substring(0, 6);

	private InstanceId() {
	}

	/** 本进程的实例标识（同进程内恒定）。 */
	public static String get() {
		return VALUE;
	}

	private static String host() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			return "unknown";
		}
	}

}
